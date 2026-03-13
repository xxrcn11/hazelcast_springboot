package com.bt.hz.domain.sessions.mapstore;

import com.bt.hz.domain.sessions.mapper.BCB001IMapper;
import com.bt.hz.domain.sessions.models.BCB001I;
import com.bt.hz.config.SpringContextHolder;
import com.hazelcast.map.MapStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hazelcast MapStore 구현체
 * Hazelcast -> DB 방향의 CUD 및 기동 시 DB -> Hazelcast 적재 역할을 수행합니다.
 *
 * <p>Hazelcast가 리플렉션을 통해 기본 생성자로 초기화하므로 {@code @Transactional}이 동작하지
 * 않습니다. 대신 {@link TransactionTemplate}을 사용하여 모든 DB 접근을 Spring 트랜잭션
 * 컨텍스트 안에서 실행합니다. (MyBatis-Spring "synchronization is not active" 경고 해소)
 */
@Slf4j
public class BCB001IMapStore implements MapStore<String, BCB001I> {

    public BCB001IMapStore() {
        // Hazelcast가 리플렉션을 통해 인스턴스화 할 수 있도록 기본 생성자 제공
    }

    private BCB001IMapper getMapper() {
        BCB001IMapper mapper = SpringContextHolder.getBean(BCB001IMapper.class);
        if (mapper == null) {
            throw new IllegalStateException("SpringContext is not fully initialized yet.");
        }
        return mapper;
    }

    /**
     * 쓰기 작업용 트랜잭션 템플릿 (기본 전파 속성: REQUIRED)
     */
    private TransactionTemplate txWrite() {
        return new TransactionTemplate(
                SpringContextHolder.getBean(PlatformTransactionManager.class));
    }

    /**
     * 읽기 전용 트랜잭션 템플릿 (read-only = true)
     */
    private TransactionTemplate txRead() {
        TransactionTemplate tt = new TransactionTemplate(
                SpringContextHolder.getBean(PlatformTransactionManager.class));
        tt.setReadOnly(true);
        return tt;
    }

    // ==========================================
    // Store 메서드 (Hazelcast -> DB CUD)
    // ==========================================

    @Override
    public void store(String key, BCB001I value) {
        log.debug("[BCB001IMapStore] store key: {}", key);
        txWrite().execute(status -> {
            getMapper().insertOrUpdate(value);
            return null;
        });
    }

    @Override
    public void storeAll(Map<String, BCB001I> map) {
        log.debug("[BCB001IMapStore] storeAll keys: {}", map.keySet());
        txWrite().execute(status -> {
            for (BCB001I value : map.values()) {
                getMapper().insertOrUpdate(value);
            }
            return null;
        });
    }

    @Override
    public void delete(String key) {
        log.debug("[BCB001IMapStore] delete key: {}", key);
        txWrite().execute(status -> {
            getMapper().deleteById(key);
            return null;
        });
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        log.debug("[BCB001IMapStore] deleteAll keys: {}", keys);
        if (keys != null && !keys.isEmpty()) {
            txWrite().execute(status -> {
                getMapper().deleteAll(keys);
                return null;
            });
        }
    }

    // ==========================================
    // Load 메서드 (DB -> Hazelcast)
    // ==========================================

    @Override
    public BCB001I load(String key) {
        log.debug("[BCB001IMapStore] load key: {}", key);
        return txRead().execute(status -> getMapper().findById(key));
    }

    @Override
    public Map<String, BCB001I> loadAll(Collection<String> keys) {
        log.debug("[BCB001IMapStore] loadAll keys: {}", keys);
        if (keys == null || keys.isEmpty()) {
            return new HashMap<>();
        }
        return txRead().execute(status -> {
            Map<String, BCB001I> result = new HashMap<>();
            List<BCB001I> list = getMapper().findAllByIds(keys);
            for (BCB001I item : list) {
                result.put(item.getId(), item);
            }
            return result;
        });
    }

    @Override
    public Iterable<String> loadAllKeys() {
        log.debug("[BCB001IMapStore] loadAllKeys");
        // Hazelcast 클러스터 기동 시 어떤 키들을 메모리에 적재할지 전체 목록 반환
        return txRead().execute(status -> getMapper().findAllKeys());
    }
}
