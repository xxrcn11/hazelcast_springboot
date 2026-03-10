package com.bt.hz.domain.sessions;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetService;

import com.hazelcast.jet.pipeline.*;
import com.hazelcast.map.EventJournalMapEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SessionJetPipelineConfig {

    private final HazelcastInstance hazelcastInstance;

    public void initPipeline() {
        try {
            JetService jet = hazelcastInstance.getJet();

            // 마스터 노드(클러스터에서 가장 오래된 멤버)에서만 파이프라인을 제출하도록 방어하여 동시 제출 경쟁(Race Condition)을 원천 차단
            com.hazelcast.cluster.Member masterMember = hazelcastInstance.getCluster().getMembers().iterator().next();
            if (!masterMember.localMember()) {
                log.info("[SessionJetPipeline] Current node is not the master. Skipping Jet pipeline submission.");
                return;
            }

            // Job 이름 지정하여, 클러스터 내에서 오직 1개의 Job만 돌도록 방어
            com.hazelcast.jet.config.JobConfig jobConfig = new com.hazelcast.jet.config.JobConfig();
            jobConfig.setName("SessionJetPipelineJob");

            if (jet.getJob("SessionJetPipelineJob") != null) {
                log.info(
                        "[SessionJetPipeline] Job 'SessionJetPipelineJob' is already submitted or running in the cluster.");
                return;
            }

            Pipeline p = buildPipeline();
            jet.newJob(p, jobConfig);
            log.info("[SessionJetPipeline] Job submitted successfully for bt_sessions.");
        } catch (Exception e) {
            log.warn("[SessionJetPipeline] Failed to submit jet job or job already exists.", e.getMessage());
        }
    }

    private Pipeline buildPipeline() {
        Pipeline p = Pipeline.create();

        // =========================================================================
        // [ServiceFactory 공통 선언 영역]
        // 파이프라인 각 노드(Processor)에서 재사용할 서비스 객체의 생성 방법(Recipe)을 선언합니다.
        // =========================================================================

        // 1. 역직렬화를 위한 SerializationService 팩토리
        com.hazelcast.jet.pipeline.ServiceFactory<?, com.hazelcast.internal.serialization.SerializationService> ssFactory = com.hazelcast.jet.pipeline.ServiceFactories
                .sharedService(ctx -> {
                    HazelcastInstance hz = ctx.hazelcastInstance();
                    if (hz instanceof com.hazelcast.instance.impl.HazelcastInstanceProxy) {
                        return ((com.hazelcast.instance.impl.HazelcastInstanceProxy) hz).getSerializationService();
                    } else if (hz instanceof com.hazelcast.instance.impl.HazelcastInstanceImpl) {
                        return ((com.hazelcast.instance.impl.HazelcastInstanceImpl) hz).getSerializationService();
                    } else {
                        throw new IllegalStateException(
                                "Unsupported HazelcastInstance type: " + hz.getClass().getName());
                    }
                });

        // 2. ObjectMapper를 노드별로 한 번만 생성하여 재사용하기 위한 팩토리
        com.hazelcast.jet.pipeline.ServiceFactory<?, com.fasterxml.jackson.databind.ObjectMapper> mapperService = com.hazelcast.jet.pipeline.ServiceFactories
                .sharedService(ctx -> {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                    mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);
                    return mapper;
                });

        // =========================================================================
        // [파이프라인 단계(Step) 구성 영역]
        // =========================================================================

        // 1단계 : bt_sessions에서 이벤트 인식
        StreamStage<EventJournalMapEvent<String, Object>> source = p.readFrom(
                Sources.mapJournal(
                        "bt_sessions",
                        JournalInitialPosition.START_FROM_OLDEST,
                        (EventJournalMapEvent<String, Object> e) -> e,
                        (EventJournalMapEvent<String, Object> e) -> true))
                .withIngestionTimestamps()
                .peek(e -> {
                    org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
                    if (l.isDebugEnabled()) {
                        l.debug("[SessionJetPipeline] Step 1 - Key: {}, Type: {}, Value: {}", e.getKey(), e.getType(),
                                e.getNewValue());
                    }
                    return null;
                });

        // 1.5단계 : 역직렬화 수행 (노드별로 공유되는 SerializationService 활용)
        StreamStage<ExtractedSessionEvent> extractedStream = source.mapUsingService(ssFactory, (ss, event) -> {
            Object value = event.getNewValue();
            if (event.getType() == com.hazelcast.core.EntryEventType.REMOVED ||
                    event.getType() == com.hazelcast.core.EntryEventType.EXPIRED ||
                    value == null) {
                return new ExtractedSessionEvent(event.getKey(), true, null, null, null);
            }
            java.util.Map<?, ?> attrs = extractSessionStateAttributes(value);
            String login = null;
            String loginType = null;
            String userInfo = null;
            if (attrs != null) {
                login = deserializeString(ss, attrs.get("LOGIN"));
                loginType = deserializeString(ss, attrs.get("LOGIN_TYPE"));
                userInfo = deserializeString(ss, attrs.get("USER_INFO"));
            }
            return new ExtractedSessionEvent(event.getKey(), false, login, loginType, userInfo);
        });

        // 2단계 : bt_sessions에 저장된 값들 추출, 부분적으로 들어오는 이벤트를 상태로 모아서 운반
        StreamStage<SessionEventTransport> parsedStream = extractedStream
                .groupingKey(ExtractedSessionEvent::getSessionId)
                .<SessionInfo, SessionEventTransport>mapStateful(
                        java.util.concurrent.TimeUnit.HOURS.toMillis(11), // bt_sessions 10시간 TTL 고려하여 여유있게 11시간 설정
                        SessionInfo::new,
                        (sessionInfo, sessionId, event) -> {
                            // 로그아웃 감지
                            if (event.isLogout) {
                                return new SessionEventTransport(sessionId, null, null, null, true, false);
                            }

                            try {
                                boolean wasCompleteBefore = sessionInfo.isComplete();

                                // 상태 업데이트 (기존에는 역직렬화를 여기서 수행했으나, 이제 미리 파싱된 값을 받음)
                                sessionInfo.update(event.rLogin, event.rLoginType, event.rUserInfo);

                                // 처음 완성된 상태인지 체크 후 플래그 변경
                                boolean isNewLogin = false;
                                if (!wasCompleteBefore && sessionInfo.isComplete() && !sessionInfo.isCountProcessed) {
                                    sessionInfo.isCountProcessed = true;
                                    isNewLogin = true;
                                }

                                org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
                                if (l.isDebugEnabled()) {
                                    l.debug("[SessionJetPipeline] Session State Complete? {}, isNewLogin? {}, isCountProcessed? {}",
                                            sessionInfo.isComplete(), isNewLogin, sessionInfo.isCountProcessed);
                                }

                                // 완성된 상태만 전달, 미완성 상태면 기록만 하고 스트림으로 내리지 않음 (null 반환)
                                if (sessionInfo.isComplete()) {
                                    return new SessionEventTransport(sessionId, sessionInfo.login,
                                            sessionInfo.loginType,
                                            sessionInfo.userInfo, false, isNewLogin);
                                } else {
                                    return null;
                                }

                            } catch (Exception e) {
                                log.error("Failed to extract session attributes for ID: {}", sessionId, e);
                            }
                            return null;
                        },
                        (sessionInfo, sessionId, timestamp) -> new SessionEventTransport(sessionId, null, null, null,
                                true,
                                false))
                .filter(e -> e != null);

        // 3단계 : 추출된 값들을 M_SYSSE001I 맵에 반영 (로그아웃 시 삭제, 완성된 정보만 업데이트)
        parsedStream.peek(dto -> {
            org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
            if (l.isDebugEnabled()) {
                l.debug("[SessionJetPipeline] Step 3 Input -> Session ID: {}, isLogout: {}, isComplete: {}, login: {}, loginType: {}, userInfo: {}",
                        dto.sessionId, dto.isLogout, dto.isComplete(), dto.login, dto.loginType, dto.userInfo);
            }
            return null;
        }).writeTo(Sinks.mapWithUpdating(
                "M_SYSSE001I",
                dto -> dto.sessionId,
                (com.bt.hz.domain.sessions.models.SYSSE001I oldValue, SessionEventTransport dto) -> {
                    if (dto.isLogout)
                        return null; // 삭제
                    if (!dto.isComplete())
                        return oldValue; // 상태 유지

                    com.bt.hz.domain.sessions.models.SYSSE001I pojo = new com.bt.hz.domain.sessions.models.SYSSE001I();
                    pojo.setLogin(String.valueOf(dto.login));
                    pojo.setLoginType(String.valueOf(dto.loginType));
                    pojo.setUserInfo(String.valueOf(dto.userInfo));
                    pojo.setSessionId(dto.sessionId);
                    return pojo;
                }));

        // EventTransport -> sessionDto 파싱 및 운반용 래퍼로 변환
        StreamStage<SessionEventWrapper> wrapperStream = parsedStream.mapUsingService(mapperService, (mapper, dto) -> {
            if (dto.isLogout)
                return new SessionEventWrapper(dto, null);
            if (!dto.isComplete() || dto.userInfo == null || "null".equals(String.valueOf(dto.userInfo)))
                return null;

            try {
                com.bt.hz.domain.sessions.models.SessionDto sessionDto = mapper.readValue(String.valueOf(dto.userInfo),
                        com.bt.hz.domain.sessions.models.SessionDto.class);
                return new SessionEventWrapper(dto, sessionDto);
            } catch (Exception e) {
                log.error("Failed to parse USER_INFO into SessionDto for ID: {}", dto.sessionId, e);
                return null;
            }
        }).filter(w -> w != null);

        // 4단계 : 추출된 값들 중 USER_INFO 값을 M_SYSSE002I에 반영 (로그아웃 시 삭제)
        wrapperStream.writeTo(Sinks.mapWithUpdating(
                "M_SYSSE002I",
                wrapper -> wrapper.transport.sessionId,
                (com.bt.hz.domain.sessions.models.SessionDto oldValue, SessionEventWrapper wrapper) -> {
                    if (wrapper.transport.isLogout)
                        return null; // 삭제
                    return wrapper.sessionDto;
                }));

        // 5단계 : M_SYSSE014I 맵 (Hazelcast SQL 지원을 위한 POJO 타입 저장)
        // 외부의 concurrent wirte가 없다면 현재 상태 유지하고 있다면 6단계처럼 mapWithEntryProcessor를 사용하는
        // 방법으로 수정할 것!
        wrapperStream.writeTo(Sinks.mapWithUpdating(
                "M_SYSSE014I",
                wrapper -> wrapper.transport.sessionId,
                (com.bt.hz.domain.sessions.models.SYSSE014I oldValue, SessionEventWrapper wrapper) -> {
                    if (wrapper.transport.isLogout) {
                        // 로그아웃 시: 기존 값이 있다면 logoutAt만 업데이트해서 유지, 없으면 null 리턴
                        if (oldValue == null) {
                            return null;
                        }
                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                                .ofPattern("yyyyMMddHHmmss");
                        oldValue.setLogoutAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(formatter));
                        return oldValue;
                    } else {
                        // 로그인 시: SessionDto와 loginType 기반으로 새로운 SYSSE014I 객체 생성
                        com.bt.hz.domain.sessions.models.SessionDto userDto = wrapper.sessionDto;
                        com.bt.hz.domain.sessions.models.SYSSE014I pojo = new com.bt.hz.domain.sessions.models.SYSSE014I();
                        pojo.setUserId(userDto.getUserId());
                        pojo.setUserName(userDto.getUserName());
                        pojo.setRole(userDto.getRole());
                        pojo.setNum(userDto.getNum());
                        pojo.setAge(userDto.getAge());
                        if (userDto.getLoginAt() != null) {
                            pojo.setLoginAt(userDto.getLoginAt());
                        }
                        pojo.setLoginType(String.valueOf(wrapper.transport.loginType));
                        // 로그인 시에는 logoutAt은 null 상태로 둠
                        return pojo;
                    }
                }));

        // 6단계 : M_SYSSE015I 맵 (시간대별 로그인 수 집계)
        // mapWithEntryProcessor를 사용하여 외부 스케줄러의 EntryProcessor와 파티션 레벨에서 직렬화되어 원자적으로 실행됨
        wrapperStream.peek(wrapper -> {
            org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
            if (l.isDebugEnabled()) {
                l.debug("[SessionJetPipeline] Step 6 Input -> Session ID: {}, isLogout: {}, isNewLogin: {}",
                        wrapper.transport.sessionId, wrapper.transport.isLogout, wrapper.transport.isNewLogin);
            }
            return null;
        }).writeTo(Sinks.mapWithEntryProcessor(
                "M_SYSSE015I",
                wrapper -> {
                    com.bt.hz.domain.sessions.models.SessionDto userDto = wrapper.sessionDto;
                    if (userDto != null && userDto.getLoginAt() != null && userDto.getLoginAt().length() >= 10) {
                        String loginAt = userDto.getLoginAt();
                        return loginAt.substring(0, 8) + "_" + loginAt.substring(8, 10);
                    }
                    return "UNKNOWN_TIME";
                },
                wrapper -> {
                    String ymd = "UNKNOWN";
                    String hour = "UNKNOWN";
                    com.bt.hz.domain.sessions.models.SessionDto userDto = wrapper.sessionDto;
                    if (userDto != null && userDto.getLoginAt() != null && userDto.getLoginAt().length() >= 10) {
                        String loginAt = userDto.getLoginAt();
                        ymd = loginAt.substring(0, 8);
                        hour = loginAt.substring(8, 10);
                    }
                    final String fYmd = ymd;
                    final String fHour = hour;
                    final boolean noOp = wrapper.transport.isLogout || !wrapper.transport.isNewLogin;
                    return (com.hazelcast.map.EntryProcessor<String, com.bt.hz.domain.sessions.models.SYSSE015I, Void>) entry -> {
                        if (noOp)
                            return null;
                        com.bt.hz.domain.sessions.models.SYSSE015I current = entry.getValue();
                        if (current == null) {
                            entry.setValue(new com.bt.hz.domain.sessions.models.SYSSE015I(fYmd, fHour, 1));
                        } else {
                            entry.setValue(new com.bt.hz.domain.sessions.models.SYSSE015I(
                                    current.getStdYmd(), current.getStdHour(), current.getCnt() + 1));
                        }
                        return null;
                    };
                }));

        return p;
    }

    private static volatile java.lang.reflect.Method sessionStateGetAttributesMethod = null;

    private static java.util.Map<?, ?> extractSessionStateAttributes(Object sessionStateValue) {
        if (sessionStateValue == null)
            return null;
        try {
            if (sessionStateGetAttributesMethod == null) {
                synchronized (SessionJetPipelineConfig.class) {
                    if (sessionStateGetAttributesMethod == null) {
                        sessionStateGetAttributesMethod = sessionStateValue.getClass().getMethod("getAttributes");
                    }
                }
            }
            Object attrs = sessionStateGetAttributesMethod.invoke(sessionStateValue);
            if (attrs instanceof java.util.Map) {
                return (java.util.Map<?, ?>) attrs;
            }
        } catch (Exception e) {
            log.trace("Failed to extract attributes from SessionState", e);
        }
        return null;
    }

    private static Object deserializeObject(com.hazelcast.internal.serialization.SerializationService ss,
            Object source) {
        if (source == null)
            return null;
        if (source.getClass().getName().contains("HeapData")
                || source.getClass().getName().contains("serialization.Data")) {
            if (ss != null) {
                try {
                    return ss.toObject(source);
                } catch (Exception e) {
                    log.warn("Failed to deserialize Object in Jet pipeline", e);
                }
            }
        }
        return source;
    }

    private static String deserializeString(com.hazelcast.internal.serialization.SerializationService ss,
            Object source) {
        Object obj = deserializeObject(ss, source);
        return obj == null ? null : String.valueOf(obj);
    }

    // 상태 관리용 내부 클래스
    public static class SessionInfo implements java.io.Serializable {
        public String login;
        public String loginType;
        public String userInfo;
        public boolean isCountProcessed; // 5단계 M_SYSSE015I 횟수 카운트를 위해 추가

        public boolean isComplete() {
            return login != null && loginType != null && userInfo != null;
        }

        public void update(String rLogin, String rLoginType, String rUserInfo) {
            org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
            if (l.isDebugEnabled()) {
                l.debug("[SessionJetPipeline] Extracted -> LOGIN: {}, LOGIN_TYPE: {}, USER_INFO: {}", rLogin,
                        rLoginType, rUserInfo);
            }

            if (rLogin != null)
                this.login = rLogin;
            if (rLoginType != null)
                this.loginType = rLoginType;
            if (rUserInfo != null)
                this.userInfo = rUserInfo;
        }
    }

    // 운반용 객체 (DTO)
    public static class SessionEventTransport implements java.io.Serializable {
        public String sessionId;
        public String login;
        public String loginType;
        public String userInfo;
        public boolean isLogout;
        public boolean isNewLogin; // M_SYSSE015I 용 필드 (방금 처음으로 isComplete가 true가 된 로그인 상태인지)

        public SessionEventTransport() {
        }

        public SessionEventTransport(String sessionId, String login, String loginType, String userInfo,
                boolean isLogout, boolean isNewLogin) {
            this.sessionId = sessionId;
            this.login = login;
            this.loginType = loginType;
            this.userInfo = userInfo;
            this.isLogout = isLogout;
            this.isNewLogin = isNewLogin;
        }

        public boolean isComplete() {
            return login != null && loginType != null && userInfo != null;
        }
    }

    // 1.5단계 역직렬화 결과를 담는 중간 DTO
    public static class ExtractedSessionEvent implements java.io.Serializable {
        public String sessionId;
        public boolean isLogout;
        public String rLogin;
        public String rLoginType;
        public String rUserInfo;

        public ExtractedSessionEvent() {
        }

        public ExtractedSessionEvent(String sessionId, boolean isLogout, String rLogin, String rLoginType,
                String rUserInfo) {
            this.sessionId = sessionId;
            this.isLogout = isLogout;
            this.rLogin = rLogin;
            this.rLoginType = rLoginType;
            this.rUserInfo = rUserInfo;
        }

        public String getSessionId() {
            return sessionId;
        }
    }

    // 단계 간 운반용 래퍼 객체
    public static class SessionEventWrapper implements java.io.Serializable {
        public SessionEventTransport transport;
        public com.bt.hz.domain.sessions.models.SessionDto sessionDto;

        public SessionEventWrapper() {
        }

        public SessionEventWrapper(SessionEventTransport transport,
                com.bt.hz.domain.sessions.models.SessionDto sessionDto) {
            this.transport = transport;
            this.sessionDto = sessionDto;
        }
    }
}
