package com.bt.hz.domain.sessions;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetService;

import com.hazelcast.jet.pipeline.*;
import com.hazelcast.map.EventJournalMapEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SessionJetPipelineConfig {

    private final HazelcastInstance hazelcastInstance;

    public void initPipeline() {
        try {
            JetService jet = hazelcastInstance.getJet();
            Pipeline p = buildPipeline();
            jet.newJob(p);
            log.info("[SessionJetPipeline] Job submitted successfully for bt_sessions.");
        } catch (Exception e) {
            log.error("[SessionJetPipeline] Failed to submit jet job.", e);
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
                .withIngestionTimestamps();

        // 2단계 : bt_sessions에 저장된 값들 추출하고 운반용 객체를 생성하고 여기에 추출한 값들을 담은 뒤에 다음 단계로 넘김
        StreamStage<SessionEventTransport> parsedStream = source.map(event -> {
            String sessionId = event.getKey();
            Object value = event.getNewValue();

            if (value == null) {
                return null;
            }

            try {
                // Spring Session의 MapSession 속성 추출을 위해 리플렉션 사용
                java.lang.reflect.Method getAttributeMethod = null;
                try {
                    getAttributeMethod = value.getClass().getMethod("getAttribute", String.class);
                } catch (NoSuchMethodException e) {
                    // value가 일반 Map으로 캐스팅 가능한 경우 (대비책)
                    if (value instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
                        return new SessionEventTransport(
                                sessionId,
                                map.get("LOGIN"),
                                map.get("LOGIN_TYPE"),
                                map.get("USER_INFO"));
                    }
                }

                if (getAttributeMethod != null) {
                    return new SessionEventTransport(
                            sessionId,
                            getAttributeMethod.invoke(value, "LOGIN"),
                            getAttributeMethod.invoke(value, "LOGIN_TYPE"),
                            getAttributeMethod.invoke(value, "USER_INFO"));
                }
            } catch (Exception e) {
                log.error("Failed to extract session attributes for ID: {}", sessionId, e);
            }
            return null;
        })
                // .peek(dto -> dto != null
                // ? String.format(
                // "Parsed SessionEventTransport: sessionId=%s, login=%s, loginType=%s,
                // userInfo=%s",
                // dto.sessionId, dto.login, dto.loginType, dto.userInfo)
                // : "Parsed SessionEventTransport: null")
                .filter(java.util.Objects::nonNull);

        // 3단계 : 추출된 값들을 Map<String, String>에 담고 이걸 value로 하고, sessionId를 key로 하는
        // M_SYSSE001I 맵에 반영
        parsedStream.map(dto -> {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            map.put("LOGIN", String.valueOf(dto.login));
            map.put("LOGIN_TYPE", String.valueOf(dto.loginType));
            map.put("USER_INFO", String.valueOf(dto.userInfo));
            map.put("SESSION_ID", dto.sessionId);
            return com.hazelcast.jet.Util.entry(dto.sessionId, map);
        })
                // .peek(dto -> dto != null
                // ? String.format(
                // "apply to M_SYSSE001I")
                // : "apply to M_SYSSE001I: null")
                .writeTo(Sinks.map("M_SYSSE001I"));

        // ObjectMapper를 노드별로 한 번만 생성하여 재사용하기 위한 Jet ServiceFactory 구성
        com.hazelcast.jet.pipeline.ServiceFactory<?, com.fasterxml.jackson.databind.ObjectMapper> mapperService = com.hazelcast.jet.pipeline.ServiceFactories
                .sharedService(ctx -> {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                    mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);
                    return mapper;
                });

        // 4단계 : 추출된 값들 중 USER_INFO 값을 M_SYSSE002I에 반영
        parsedStream.mapUsingService(mapperService, (mapper, dto) -> {
            if (dto.userInfo == null || "null".equals(String.valueOf(dto.userInfo)))
                return null;
            try {
                com.bt.hz.domain.sessions.models.SessionDto sessionDto = mapper.readValue(String.valueOf(dto.userInfo),
                        com.bt.hz.domain.sessions.models.SessionDto.class);
                return com.hazelcast.jet.Util.entry(dto.sessionId, sessionDto);
            } catch (Exception e) {
                log.error("Failed to parse USER_INFO into SessionDto for ID: {}", dto.sessionId, e);
                return null;
            }
        })
                // .peek(dto -> dto != null ? String.format("apply to M_SYSSE002I") : "apply to
                // M_SYSSE002I: null")
                .filter(entry -> entry != null)
                .writeTo(Sinks.map("M_SYSSE002I"));

        return p;
    }

    // 운반용 객체 (DTO)
    public static class SessionEventTransport implements java.io.Serializable {
        public String sessionId;
        public Object login;
        public Object loginType;
        public Object userInfo;

        public SessionEventTransport(String sessionId, Object login, Object loginType, Object userInfo) {
            this.sessionId = sessionId;
            this.login = login;
            this.loginType = loginType;
            this.userInfo = userInfo;
        }
    }
}
