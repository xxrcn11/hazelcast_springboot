package com.bt.hz.domain.sessions;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetService;

import com.hazelcast.jet.pipeline.*;
import com.hazelcast.map.EventJournalMapEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
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

        // 1단계 : bt_sessions에서 이벤트 인식
        StreamStage<EventJournalMapEvent<String, Object>> source = p.readFrom(
                Sources.mapJournal(
                        "bt_sessions",
                        JournalInitialPosition.START_FROM_OLDEST,
                        (EventJournalMapEvent<String, Object> e) -> e,
                        (EventJournalMapEvent<String, Object> e) -> true))
                .withIngestionTimestamps()
                .peek(e -> {
                    String msg = String.format("[SessionJetPipeline] Step 1 - Key: %s, Type: %s, Value: %s", e.getKey(),
                            e.getType(), e.getNewValue());
                    System.out.println(msg);
                    return msg;
                });

        // 2단계 : bt_sessions에 저장된 값들 추출, 부분적으로 들어오는 이벤트를 상태로 모아서 운반
        StreamStage<SessionEventTransport> parsedStream = source
                .groupingKey(EventJournalMapEvent::getKey)
                .<SessionInfo, SessionEventTransport>mapStateful(
                        java.util.concurrent.TimeUnit.HOURS.toMillis(11), // bt_sessions 10시간 TTL 고려하여 여유있게 11시간 설정
                        SessionInfo::new,
                        (state, sessionId, event) -> {
                            Object value = event.getNewValue();

                            // 로그아웃 감지: REMOVE 이벤트, EXPIRED 이벤트 또는 값이 null인 경우
                            if (event.getType() == com.hazelcast.core.EntryEventType.REMOVED ||
                                    event.getType() == com.hazelcast.core.EntryEventType.EXPIRED ||
                                    value == null) {
                                return new SessionEventTransport(sessionId, null, null, null, true, false);
                            }

                            try {
                                Object rLogin = null;
                                Object rLoginType = null;
                                Object rUserInfo = null;

                                // Spring Session 또는 Hazelcast Web SessionState의 속성 추출을 위해 다양한 접근 시도
                                try {
                                    if (value instanceof java.util.Map) {
                                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
                                        rLogin = map.get("LOGIN");
                                        rLoginType = map.get("LOGIN_TYPE");
                                        rUserInfo = map.get("USER_INFO");
                                        System.out.println(
                                                "[SessionJetPipeline] Session ID: " + sessionId + " (Map parsing)");
                                    } else {
                                        // 1. getAttribute(String) 단일 속성 가져오기 시도
                                        try {
                                            java.lang.reflect.Method getAttributeMethod = value.getClass()
                                                    .getMethod("getAttribute", String.class);
                                            rLogin = getAttributeMethod.invoke(value, "LOGIN");
                                            rLoginType = getAttributeMethod.invoke(value, "LOGIN_TYPE");
                                            rUserInfo = getAttributeMethod.invoke(value, "USER_INFO");
                                            System.out.println("[SessionJetPipeline] Session ID: " + sessionId
                                                    + " (getAttribute() parsing)");
                                        } catch (NoSuchMethodException e1) {
                                            // 2. getAttributes() 로 맵 가져오기 시도 (com.hazelcast.web.SessionState 특화)
                                            try {
                                                java.lang.reflect.Method getAttributesMethod = value.getClass()
                                                        .getMethod("getAttributes");
                                                Object attrsObj = getAttributesMethod.invoke(value);
                                                if (attrsObj instanceof java.util.Map) {
                                                    java.util.Map<?, ?> attrs = (java.util.Map<?, ?>) attrsObj;
                                                    rLogin = attrs.get("LOGIN");
                                                    rLoginType = attrs.get("LOGIN_TYPE");
                                                    rUserInfo = attrs.get("USER_INFO");
                                                    System.out.println("[SessionJetPipeline] Session ID: " + sessionId
                                                            + " (getAttributes() parsing)");
                                                }
                                            } catch (NoSuchMethodException e2) {
                                                // 3. 필드를 모두 뒤져서 Map 타입의 필드를 찾아 속성 추출 시도
                                                boolean found = false;
                                                for (java.lang.reflect.Field field : value.getClass()
                                                        .getDeclaredFields()) {
                                                    if (java.util.Map.class.isAssignableFrom(field.getType())) {
                                                        field.setAccessible(true);
                                                        java.util.Map<?, ?> attrs = (java.util.Map<?, ?>) field
                                                                .get(value);
                                                        if (attrs != null) {
                                                            rLogin = attrs.get("LOGIN");
                                                            rLoginType = attrs.get("LOGIN_TYPE");
                                                            rUserInfo = attrs.get("USER_INFO");
                                                            System.out.println("[SessionJetPipeline] Session ID: "
                                                                    + sessionId + " (Field '" + field.getName()
                                                                    + "' parsing)");
                                                            found = true;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (!found) {
                                                    System.out.println(
                                                            "[SessionJetPipeline] Value has no Map fields nor getAttribute methods: "
                                                                    + value.getClass().getName());
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    System.out.println("[SessionJetPipeline] Exception during attribute parsing: "
                                            + e.getMessage());
                                }

                                String newLogin = extractStringValue(rLogin);
                                String newLoginType = extractStringValue(rLoginType);
                                String newUserInfo = extractStringValue(rUserInfo);

                                System.out.println("[SessionJetPipeline] Extracted -> LOGIN: " + newLogin
                                        + ", LOGIN_TYPE: " + newLoginType + ", USER_INFO: " + newUserInfo);

                                boolean wasCompleteBefore = state.isComplete();

                                // 상태 업데이트
                                state.update(newLogin, newLoginType, newUserInfo);

                                // 처음 완성된 상태인지 체크 후 플래그 변경
                                boolean isNewLogin = false;
                                if (!wasCompleteBefore && state.isComplete() && !state.isCountProcessed) {
                                    state.isCountProcessed = true;
                                    isNewLogin = true;
                                }

                                System.out
                                        .println("[SessionJetPipeline] Session State Complete? " + state.isComplete() +
                                                ", isNewLogin? " + isNewLogin +
                                                ", isCountProcessed? " + state.isCountProcessed);

                                // 완성된 상태만 전달, 미완성 상태면 기록만 하고 스트림으로 내리지 않음 (null 반환)
                                if (state.isComplete()) {
                                    return new SessionEventTransport(sessionId, state.login, state.loginType,
                                            state.userInfo, false, isNewLogin);
                                } else {
                                    return null;
                                }

                            } catch (Exception e) {
                                log.error("Failed to extract session attributes for ID: {}", sessionId, e);
                            }
                            return null;
                        },
                        (state, sessionId, timestamp) -> new SessionEventTransport(sessionId, null, null, null, true,
                                false))
                .filter(e -> e != null);

        // 3단계 : 추출된 값들을 M_SYSSE001I 맵에 반영 (로그아웃 시 삭제, 완성된 정보만 업데이트)
        parsedStream.peek(dto -> {
            String msg = "[SessionJetPipeline] Step 3 Input -> Session ID: " + dto.sessionId +
                    ", isLogout: " + dto.isLogout +
                    ", isComplete: " + dto.isComplete() +
                    ", login: " + dto.login +
                    ", loginType: " + dto.loginType +
                    ", userInfo: " + dto.userInfo;
            System.out.println(msg);
            return msg;
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

        // ObjectMapper를 노드별로 한 번만 생성하여 재사용하기 위한 Jet ServiceFactory 구성
        com.hazelcast.jet.pipeline.ServiceFactory<?, com.fasterxml.jackson.databind.ObjectMapper> mapperService = com.hazelcast.jet.pipeline.ServiceFactories
                .sharedService(ctx -> {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                    mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);
                    return mapper;
                });

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
                        pojo.setUsername(userDto.getUsername());
                        pojo.setRole(userDto.getRole());
                        if (userDto.getLoginAt() != null) {
                            pojo.setLoginAt(userDto.getLoginAt());
                        }
                        pojo.setLoginType(String.valueOf(wrapper.transport.loginType));
                        // 로그인 시에는 logoutAt은 null 상태로 둠
                        return pojo;
                    }
                }));

        // 6단계 : M_SYSSE015I 맵 (시간대별 로그인 수 집계)
        wrapperStream.peek(wrapper -> {
            String msg = "[SessionJetPipeline] Step 6 Input -> Session ID: " + wrapper.transport.sessionId +
                    ", isLogout: " + wrapper.transport.isLogout +
                    ", isNewLogin: " + wrapper.transport.isNewLogin;
            System.out.println(msg);
            return msg;
        }).writeTo(Sinks.mapWithUpdating(
                "M_SYSSE015I",
                wrapper -> {
                    com.bt.hz.domain.sessions.models.SessionDto userDto = wrapper.sessionDto;
                    if (userDto != null && userDto.getLoginAt() != null && userDto.getLoginAt().length() >= 10) {
                        String loginAt = userDto.getLoginAt();
                        String ymd = loginAt.substring(0, 8); // yyyyMMdd
                        String hour = loginAt.substring(8, 10); // HH
                        return ymd + "_" + hour;
                    }
                    return "UNKNOWN_TIME";
                },
                (com.bt.hz.domain.sessions.models.SYSSE015I oldValue, SessionEventWrapper wrapper) -> {
                    // 로그아웃이거나 카운트 대상(완성된 최초 로그인)이 아니면 기존 값 유지 (no-op)
                    if (wrapper.transport.isLogout || !wrapper.transport.isNewLogin) {
                        return oldValue;
                    }

                    // 카운트 가능한 신규 로그인인 경우
                    if (oldValue == null) {
                        com.bt.hz.domain.sessions.models.SessionDto userDto = wrapper.sessionDto;
                        String ymd = "UNKNOWN";
                        String hour = "UNKNOWN";
                        if (userDto != null && userDto.getLoginAt() != null && userDto.getLoginAt().length() >= 10) {
                            String loginAt = userDto.getLoginAt();
                            ymd = loginAt.substring(0, 8); // yyyyMMdd
                            hour = loginAt.substring(8, 10); // HH
                        }

                        return new com.bt.hz.domain.sessions.models.SYSSE015I(ymd, hour, 1);
                    } else {
                        oldValue.setCnt(oldValue.getCnt() + 1);
                        return oldValue;
                    }
                }));

        return p;
    }

    private static String extractStringValue(Object value) {
        if (value == null)
            return null;
        if (value.getClass().getName().contains("HeapData")
                || value.getClass().getName().contains("serialization.Data")) {
            try {
                java.util.Iterator<HazelcastInstance> it = com.hazelcast.core.Hazelcast.getAllHazelcastInstances()
                        .iterator();
                if (it.hasNext()) {
                    HazelcastInstance hz = it.next();
                    java.lang.reflect.Method getSsMethod = null;
                    for (java.lang.reflect.Method m : hz.getClass().getMethods()) {
                        if (m.getName().equals("getSerializationService")) {
                            getSsMethod = m;
                            break;
                        }
                    }
                    if (getSsMethod != null) {
                        Object ss = getSsMethod.invoke(hz);
                        if (ss != null) {
                            java.lang.reflect.Method toObjectMethod = null;
                            for (java.lang.reflect.Method m : ss.getClass().getMethods()) {
                                if (m.getName().equals("toObject") && m.getParameterCount() == 1) {
                                    toObjectMethod = m;
                                    break;
                                }
                            }
                            if (toObjectMethod != null) {
                                Object deserialized = toObjectMethod.invoke(ss, value);
                                return deserialized == null ? null : String.valueOf(deserialized);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize Data object", e);
            }
        }
        return String.valueOf(value);
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

        public void update(String newLogin, String newLoginType, String newUserInfo) {
            if (newLogin != null)
                this.login = newLogin;
            if (newLoginType != null)
                this.loginType = newLoginType;
            if (newUserInfo != null)
                this.userInfo = newUserInfo;
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
