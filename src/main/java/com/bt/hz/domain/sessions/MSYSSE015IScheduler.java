package com.bt.hz.domain.sessions;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.scheduledexecutor.IScheduledExecutorService;
import com.hazelcast.scheduledexecutor.IScheduledFuture;
import com.hazelcast.scheduledexecutor.NamedTask;
import com.hazelcast.scheduledexecutor.TaskUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Hazelcast {@link IScheduledExecutorService}를 이용하여 매시마다 M_SYSSE001I 세션 수를
 * 카운팅한 뒤 M_SYSSE015I 에 반영하는 스케줄러.
 *
 * <p>등록 규칙:
 * <ul>
 *   <li>마스터 노드(클러스터에서 가장 오래된 멤버)에서만 스케줄을 등록합니다.</li>
 *   <li>Task 이름 {@code MSYSSE015I_HOURLY}로 중복 등록을 방지합니다.</li>
 *   <li>현재 시각부터 다음 정시(+1h 절삭)까지를 initialDelay, 이후 1시간 간격으로 반복합니다.</li>
 * </ul>
 *
 * <p>Bean이 아닌 정적 유틸 클래스로 설계하여 {@link SpringBootStrapListener} 초기화 흐름에서
 * {@link #register(HazelcastInstance)} 를 직접 호출하도록 합니다.
 */
@Slf4j
public class MSYSSE015IScheduler {

    private static final String SCHEDULER_NAME = "msysse015i-scheduler";
    private static final String TASK_NAME = "MSYSSE015I_HOURLY";

    private MSYSSE015IScheduler() {
    }

    /**
     * 마스터 노드에서 한 번만 호출하면 됩니다.
     * SpringBootStrapListener 의 초기화 흐름 마지막 단계에서 호출합니다.
     */
    public static void register(HazelcastInstance hz) {
        // 마스터(가장 오래된 멤버)에서만 스케줄 등록
        com.hazelcast.cluster.Member master = hz.getCluster().getMembers().iterator().next();
        if (!master.localMember()) {
            log.info("[MSYSSE015IScheduler] 현재 노드는 마스터가 아니므로 스케줄 등록을 건너뜁니다.");
            return;
        }

        IScheduledExecutorService scheduler = hz.getScheduledExecutorService(SCHEDULER_NAME);

        // 동일 이름으로 이미 등록된 Task가 있으면 중복 등록 방지
        try {
            java.util.Map<com.hazelcast.cluster.Member, java.util.List<IScheduledFuture<Object>>> allFutures =
                    scheduler.getAllScheduledFutures();
            for (java.util.List<IScheduledFuture<Object>> futures : allFutures.values()) {
                for (IScheduledFuture<Object> f : futures) {
                    if (TASK_NAME.equals(f.getHandler().getTaskName())) {
                        log.info("[MSYSSE015IScheduler] 이미 등록된 스케줄 Task({})가 존재합니다. 중복 등록을 건너뜁니다.", TASK_NAME);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[MSYSSE015IScheduler] 기존 Task 조회 중 오류 발생 (무시하고 등록 진행): {}", e.getMessage());
        }

        // 다음 정시까지 남은 시간 계산 (initialDelay)
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        long initialDelaySeconds = java.time.Duration.between(now, nextHour).getSeconds();

        scheduler.scheduleAtFixedRate(
                TaskUtils.named(TASK_NAME, new HourlyTask()),
                initialDelaySeconds,
                3600L,
                TimeUnit.SECONDS
        );

        log.info("[MSYSSE015IScheduler] 스케줄 등록 완료. 첫 실행까지 {}초 후 (다음 정시: {}), 이후 1시간 간격으로 실행됩니다.",
                initialDelaySeconds, nextHour);
    }

    // -------------------------------------------------------------------------
    // 실제 실행 Task (Serializable 필수)
    // -------------------------------------------------------------------------

    /**
     * 매시마다 실행되는 Task.
     * Hazelcast 클러스터의 어떤 파티션(노드)에서 실행될 수 있으므로 반드시 {@link Serializable}을 구현합니다.
     */
    public static class HourlyTask implements Runnable, Serializable, NamedTask {

        private static final long serialVersionUID = 1L;

        @Override
        public String getName() {
            return TASK_NAME;
        }

        @Override
        public void run() {
            // HazelcastInstance는 TaskContext 또는 Hazelcast.getAllHazelcastInstances()를 통해 획득
            HazelcastInstance hz = com.hazelcast.core.Hazelcast.getAllHazelcastInstances()
                    .stream()
                    .findFirst()
                    .orElse(null);

            if (hz == null) {
                getLogger().warn("[MSYSSE015IScheduler] HazelcastInstance를 찾을 수 없어 작업을 건너뜁니다.");
                return;
            }

            try {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

                // stdYmd : yyyyMMdd
                String stdYmd = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                // stdHour : HH (2자리 문자열)
                String stdHour = now.format(DateTimeFormatter.ofPattern("HH"));

                // M_SYSSE001I 현재 항목 수 카운팅
                IMap<?, ?> sysse001iMap = hz.getMap("M_SYSSE001I");
                int count = sysse001iMap.size();

                getLogger().info("[MSYSSE015IScheduler] stdYmd={}, stdHour={}, count={}", stdYmd, stdHour, count);

                // stdYmd + "_" + stdHour 를 key로 EntryProcessor 실행
                String key = stdYmd + "_" + stdHour;
                IMap<String, com.bt.hz.domain.sessions.models.SYSSE015I> sysse015iMap =
                        hz.getMap("M_SYSSE015I");
                sysse015iMap.executeOnKey(key, new MSYSSE015IEntryProcessor(stdYmd, stdHour, count));

                getLogger().info("[MSYSSE015IScheduler] M_SYSSE015I 업데이트 완료. key={}, count={}", key, count);

            } catch (Exception e) {
                getLogger().error("[MSYSSE015IScheduler] M_SYSSE015I 업데이트 실패", e);
            }
        }

        private org.slf4j.Logger getLogger() {
            return org.slf4j.LoggerFactory.getLogger(MSYSSE015IScheduler.class);
        }
    }
}
