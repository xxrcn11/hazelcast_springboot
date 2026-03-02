package com.bt.hz.bootstrap;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hazelcast 클러스터 기동 완료 후,
 * 등록된 MapStore(BCB001IMapStore)를 통해 DB 데이터를 Hazelcast IMap으로 선탑재하는 클래스입니다.
 * 이 클래스는 이제 ApplicationRunner를 구현하지 않으며, SpringBootStrapListener에서
 * 모든 클러스터 동기화가 확인된 후 수동으로 호출됩니다.
 */
@Slf4j
@Component
public class MapStoreLoaderRunner {

    private final HazelcastInstance hazelcastInstance;

    public MapStoreLoaderRunner(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
    }

    public void initLoadMapData() {
        // 클러스터 환경을 감안하여 하나의 노드(마스터 노드)에서만 Eager Load를 관장하도록 제어
        boolean isMasterNode = hazelcastInstance.getCluster().getMembers().iterator().next().localMember();

        if (!isMasterNode) {
            log.info("[MapStoreLoader] 현재 노드는 클러스터 마스터가 아니므로 MapStore Eager Load 트리거를 건너뜁니다.");
            return;
        }

        log.info("[MapStoreLoader] 클러스터 마스터 노드 식별 완료. BCB001I 맵 초기 데이터 적재(Eager Load)를 실행합니다...");

        try {
            IMap<Object, Object> bcb001iMap = hazelcastInstance.getMap("BCB001I");

            // 파라미터가 true일 경우: 기존 메모리 캐시에 같은 키가 있으면 DB 데이터로 덮어씌움
            // 파라미터가 false일 경우: 메모리에 없는 경우에만 DB 데이터로 채움
            bcb001iMap.loadAll(true);

            log.info("[MapStoreLoader] BCB001I 맵에 DB 데이터 적재 완료. 마스터가 관측한 현재 맵 사이즈: {}", bcb001iMap.size());
        } catch (Exception e) {
            log.error("[MapStoreLoader] 초기 데이터 적재 중 오류 발생", e);
        }
    }
}
