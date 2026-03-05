package com.bt.hz.bootstrap;

import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import lombok.extern.slf4j.Slf4j;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.bt.hz.HazelcastApplication;
import java.util.concurrent.CompletableFuture;

/**
 * Hazelcast 라이프사이클 이벤트를 수신하여 SpringContext를 초기화하거나 제거하는 리스너입니다.
 */
@Slf4j
public class SpringBootStrapListener implements LifecycleListener {

    private ConfigurableApplicationContext applicationContext;

    public SpringBootStrapListener() {
    }

    @Override
    public void stateChanged(LifecycleEvent event) {
        log.info("[Lifecycle] State changed: {}", event.getState());

        switch (event.getState()) {
            case STARTED:
                // STARTED 상태인 경우 SpringContext 초기화 메소드 호출
                initializeSpringContext();
                break;
            case SHUTTING_DOWN:
                // SHUTTING_DOWN 상태인 경우 SpringContext 제거 메소드 호출
                destorySpringContext();
                break;
            default:
                break;
        }
    }

    private void destorySpringContext() {
        if (applicationContext != null && applicationContext.isActive()) {
            log.info("[Bootstrap] Closing Spring Context to release resources...");
            applicationContext.close();
            applicationContext = null;
        }
    }

    private void initializeSpringContext() {
        try {
            HazelcastInstance hzInstance = Hazelcast.getAllHazelcastInstances().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Hazelcast instance is not fully initialized."));

            log.info("[Bootstrap] Starting Spring Context initialization...");

            // 1. SpringApplicationBuilder를 통해 완벽한 Spring Boot 환경 구성 (application.yml 로드 등)
            applicationContext = new SpringApplicationBuilder(HazelcastApplication.class)
                    .initializers(context -> {
                        // 2. Refresh 이전에 HazelcastInstance를 먼저 Singleton Bean으로 등록
                        context.getBeanFactory().registerSingleton("hazelcastInstance", hzInstance);
                    })
                    .run();

            log.info("[Bootstrap] Spring Boot Context is up and running.");

            // 4. 클러스터 전체 노드의 Spring Boot 구동이 완료될 때까지 대기 후 핵심 구성요소 초기화
            CompletableFuture.runAsync(() -> {
                try {
                    // 클러스터 동기화 대기
                    ClusterReadinessCoordinator coordinator = applicationContext
                            .getBean(ClusterReadinessCoordinator.class);
                    coordinator.awaitClusterReady();

                    log.info("[Bootstrap] 클러스터 동기화 완료. 추가 초기화 작업을 시작합니다.");

                    // SQL Mapping 뷰 등록
                    MappingSqlRunner sqlRunner = applicationContext.getBean(MappingSqlRunner.class);
                    sqlRunner.initMappingSqls();

                    // MapStore 데이터 Eager Load
                    MapStoreLoaderRunner mapLoader = applicationContext.getBean(MapStoreLoaderRunner.class);
                    mapLoader.initLoadMapData();

                    // Jet 파이프라인 초기화 (bt_sessions 이벤트 리스너 등)
                    com.bt.hz.domain.sessions.SessionJetPipelineConfig jetPipeline = applicationContext
                            .getBean(com.bt.hz.domain.sessions.SessionJetPipelineConfig.class);
                    jetPipeline.initPipeline();

                    // 테스트 데이터 주입
                    java.util.Map<String, String> data = new java.util.HashMap<>();
                    data.put("LOGIN", "test_login");
                    data.put("LOGIN_TYPE", "WEB");
                    data.put("USER_INFO",
                            "{\"userId\":\"1\", \"username\":\"u\", \"role\":\"admin\", \"loginAt\":\"20260306183700\"}");
                    hzInstance.getMap("bt_sessions").put("test_spring_session", data);
                    log.info("================================");
                    log.info("INSERTING TEST DATA INTO bt_sessions (after pipeline init)");
                    log.info("================================");

                } catch (Exception e) {
                    log.error("CRITICAL: Failed to initialize cluster components. Halting node.", e);
                    // Fail-Fast: 핵심 로직 실패 시 불안전한 상태로 노드 유지 방지
                    hzInstance.getLifecycleService().terminate();
                }
            });

        } catch (Exception e) {
            log.error("[Bootstrap] Spring Context initialization failed.", e);
            throw new RuntimeException("Failed to bootstrap Spring from Hazelcast", e);
        }
    }
}
