package com.bt.hz.bootstrap;

import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Hazelcast 클러스터의 모든 노드가 Spring Boot Context 초기화를 완료했는지 동기화하는 코디네이터입니다.
 * 각 노드는 구동이 완료되면 sys_spring_ready 맵에 자신의 UUID를 등록하며,
 * 마스터 노드는 이 클래스의 awaitClusterReady()를 호출하여 전체 노드의 상태를 취합 후 다음 단계를 진행합니다.
 */
@Slf4j
@Component
public class ClusterReadinessCoordinator implements ApplicationRunner {

    private final HazelcastInstance hazelcastInstance;

    public ClusterReadinessCoordinator(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 모든 노드가 자신의 Spring Context 구동이 마쳤음을 클러스터 맵에 기록
        String localUuid = hazelcastInstance.getCluster().getLocalMember().getUuid().toString();
        IMap<String, Boolean> readyMap = hazelcastInstance.getMap("sys_spring_ready");
        readyMap.put(localUuid, true);

        log.info("[ClusterReadinessCoordinator] 현재 노드의 Spring Context 초기화 상태를 클러스터에 등록 완료: {}", localUuid);
    }

    /**
     * 클러스터 내의 모든 노드가 sys_spring_ready 맵에 등록완료 될 때까지 블로킹하며 대기합니다.
     * 클러스터 마스터 노드에서만 집중적으로 호출하여 후속 작업(MappingSql 생성, Eager Load 등)을 지휘할 때 사용합니다.
     */
    public void awaitClusterReady() {
        log.info("[ClusterReadinessCoordinator] 클러스터 멤버 전원의 Spring Context 구동 완료를 대기합니다...");

        IMap<String, Boolean> readyMap = hazelcastInstance.getMap("sys_spring_ready");

        try {
            while (true) {
                int clusterSize = hazelcastInstance.getCluster().getMembers().size();
                int readySize = 0;

                for (Member member : hazelcastInstance.getCluster().getMembers()) {
                    if (readyMap.containsKey(member.getUuid().toString())) {
                        readySize++;
                    }
                }

                if (readySize >= clusterSize) {
                    break;
                }

                log.info("[ClusterReadinessCoordinator] 아직 Spring Context가 구동되지 않은 노드가 있습니다. (대기 중... {} / {})",
                        readySize, clusterSize);
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ClusterReadinessCoordinator] 클러스터 동기화 대기 중 인터럽트가 발생했습니다.", e);
            throw new RuntimeException("Cluster readiness wait interrupted", e);
        }

        log.info("[ClusterReadinessCoordinator] 클러스터 전체 노드의 Spring Context가 모두 준비되었습니다!");
    }
}
