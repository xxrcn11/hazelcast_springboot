package com.bt.hz.bootstrap;

import com.bt.hz.config.MappingSQLs;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.sql.SqlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hazelcast SQL (Jet Engine) 환경에서 필요한 CREATE MAPPING 쿼리들을
 * 애플리케이션 시작 시 자동으로 등록해주는 실행 클래스.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MappingSqlRunner {

    private final HazelcastInstance hazelcastInstance;

    public void initMappingSqls() {
        // 3. 클러스터 환경을 감안하여 하나의 노드(마스터 노드)에서만 실행되도록 제어
        boolean isMasterNode = hazelcastInstance.getCluster().getMembers().iterator().next().localMember();

        if (!isMasterNode) {
            log.info("[MappingSqlRunner] 현재 노드는 클러스터 마스터가 아니므로 Mapping SQL 뷰 생성을 건너뜁니다.");
            return;
        }

        log.info("[MappingSqlRunner] 클러스터 마스터 노드로서 Mapping SQL 등록 배치를 시작합니다.");

        // 2. Hazelcast의 SqlService 획득
        SqlService sqlService = hazelcastInstance.getSql();

        // 1. MappingSQLs에 정의된 모든 mapping 쿼리 정보 가져오기
        List<String> mappings = MappingSQLs.getAllMappings();

        for (String sql : mappings) {
            try {
                // 4. 생성할 mapping SQL의 텍스트를 로그에 남기기
                log.info("[MappingSqlRunner] 실행 대기 중인 Mapping SQL:\n{}", sql);

                // SQL 실행
                sqlService.execute(sql);

                log.info("[MappingSqlRunner] Mapping SQL 등록 성공. {}", sql);
            } catch (Exception e) {
                log.error("[MappingSqlRunner] Mapping SQL 등록 중 오류 발생:\n{}", sql, e);
            }
        }

        log.info("[MappingSqlRunner] 전체 Mapping SQL 등록 절차를 완료했습니다.");
    }
}
