# Hazelcast 재시작과 Persistence 전환 검토 문서

## 개요

Hazelcast 클러스터 재시작 시 데이터 보존 방안 및 Persistence 적용에 따른 영향도 분석 문서.

- **Part 1**: 세션 클러스터링 연속성 검토
- **Part 2**: MapStore 제외 맵들에 Persistence 적용 시 예상 문제점 검토

---

# Part 1. 세션 클러스터링 연속성 검토

## 1-1. 세션 클러스터링의 실체

Spring Session의 세션 클러스터링은 **`bt_sessions` 맵 하나에 전적으로 의존**한다. `SessionJetPipelineConfig`는 세션 클러스터링 자체와는 무관하며, bt_sessions의 변경 이벤트를 읽어 **파생 집계 맵**(M_SYSSE001I 등)을 만드는 역할만 한다.

```
사용자 로그인 → Spring Session → bt_sessions.put(sessionId, SessionState)
                                        │
                                        │ (이 맵만 있으면 클러스터 세션 공유 성립)
                                        │
                                        └─► Event Journal
                                              └─► SessionJetPipelineConfig (파생 집계용, 부차적)
```

## 1-2. 현재 `bt_sessions` 설정 상태

현재 `hazelcast.xml`의 bt_sessions 설정:

```xml
<map name="bt_sessions">
    <time-to-live-seconds>36000</time-to-live-seconds>
    <event-journal enabled="true">...</event-journal>
    <!-- ⚠️ data-persistence 설정 없음 -->
</map>
```

**Persistence 설정이 없는 상태**이므로, 현재 기준으로 재시작하면:

```
재시작 전: bt_sessions = { "sess_1": ..., "sess_2": ..., "sess_3": ... }  (3명 로그인 중)
                   │
재시작 중: 모든 노드 중지 → 메모리 휘발
                   │
재시작 후: bt_sessions = { }  (빈 맵)
                   │
결과: 3명 모두 강제 로그아웃 → 재로그인 필요 ❌
```

## 1-3. bt_sessions에 Persistence를 추가한 경우

```xml
<map name="bt_sessions">
    <time-to-live-seconds>36000</time-to-live-seconds>
    <event-journal enabled="true">...</event-journal>
    <data-persistence enabled="true">     <!-- 추가 -->
        <fsync>false</fsync>
    </data-persistence>
</map>
```

### 재시작 시나리오

```
재시작 전: bt_sessions = { "sess_1" (TTL 8h남음), "sess_2" (TTL 3h남음) }
                   │
                   │ 디스크에 실시간 기록
                   ▼
재시작 중: 디스크 파일 유지
                   │
재시작 후: 디스크 → 복원 → bt_sessions = { "sess_1" (TTL 8h), "sess_2" (TTL 3h) }
                   │
결과: 사용자들이 로그인 상태 유지 ✅
```

**Hazelcast는 TTL의 남은 시간까지 복원**하므로, 재시작에 소요된 시간만큼 잃을 뿐 세션 만료 타이밍은 그대로 유지된다.

## 1-4. Jet Pipeline 상태 손실이 세션 클러스터링에 미치는 영향

재시작 시 불가피하게 잃는 것들:

| 손실 대상 | 세션 클러스터링 영향 |
|----------|---------------------|
| `SessionInfo` (mapStateful 내부 상태) | **없음** — 집계 버퍼일 뿐, 로그인 상태와 무관 |
| Event Journal (링버퍼) | **없음** — 재시작 중 발생한 이벤트는 유실되나, 사용자 로그인 상태는 bt_sessions에 있음 |
| Jet Job | **없음** — 재시작 후 새 Pipeline 제출되면 이후 이벤트부터 정상 처리 |

단, **파생 집계 맵의 부작용**이 있을 수 있다:

```
재시작 전 bt_sessions에 "sess_X"가 LOGIN만 들어온 상태 (SessionInfo 누적 중)
재시작 후 Event Journal이 비어있으므로 SessionInfo가 재구성되지 않음
    ↓
이 세션은 사용자 클러스터링은 정상 동작하지만,
M_SYSSE001I/M_SYSSE002I 등 파생 맵에는 반영되지 않은 상태로 남을 수 있음
```

이미 완성되어 파생 맵에 기록된 세션 데이터는 **해당 맵들도 Persistence를 걸면 함께 복원**되므로 문제없다.

## 1-5. Pipeline 수정/추가 시의 영향

### 시나리오

```
기존 Pipeline (v1):
  bt_sessions → Step 1~6 → M_SYSSE001I, M_SYSSE002I, M_SYSSE014I, M_SYSSE015I

수정 Pipeline (v2):
  bt_sessions → Step 1~6 (로직 수정) → 동일 맵 (변경 없음)
               + Step 7 (신규 로직) → M_NEW_MAP (신규 맵)
```

### 결론: 문제없음

1. **기존 맵 데이터 복구** — 문제 없음
   - Persistence가 복원하는 것은 맵의 데이터(바이트)이지, Pipeline의 로직이 아님
   - Pipeline 코드가 바뀌어도 맵에 저장된 데이터의 직렬화 형태와는 무관

2. **Jet Pipeline 자체** — Persistence 대상이 아님
   - Jet Job 정의/상태는 Persistence 저장 대상이 아님
   - 재시작 후 Pipeline은 어차피 새로 제출(submit)해야 하며, 이때 v2 코드가 실행됨

3. **Pipeline 내부 stateful 상태** — 재시작 시 초기화됨
   - `mapStateful`의 `SessionInfo`는 재시작 시 초기화
   - Pipeline 특성상 정상 동작이며, 이후 새 이벤트부터 처리

## 1-6. 세션 클러스터링 연속성 요약

| 조건 | 세션 클러스터링 연속성 |
|------|---------------------|
| **현재 설정 (Persistence 없음)** | ❌ 재시작 시 모든 세션 유실, 사용자 강제 로그아웃 |
| **bt_sessions에 Persistence 추가 + FULL_RECOVERY_ONLY** | ✅ 문제없음, 사용자 로그인 상태 유지 |
| 새 Pipeline 추가/수정 (모델 변경 없음) | ✅ 세션 클러스터링에 영향 없음 |
| 신규 모델 + 신규 MapStore만 추가 | ✅ 기존 Persistence 데이터와 호환, 문제없음 |

**결론:** 세션 클러스터링을 재시작에도 유지하려면 bt_sessions에 Persistence 활성화가 필수이며, Pipeline 수정/추가는 세션 클러스터링에 영향을 주지 않는다.

---

# Part 2. MapStore 제외 맵들에 Persistence 적용 시 검토 사항

## 전제

현재 프로젝트에서 MapStore가 설정된 맵(`BCB001I`)을 제외한 나머지 맵들에 Hazelcast Persistence를 적용하는 시나리오에 대한 재시작 시 예상 문제점 분석.

## 2-1. 대상 맵 정리

| 맵 이름 | MapStore | Persistence 적용 | 비고 |
|---------|---------|----------------|------|
| `bt_sessions` | X | ✅ 적용 | Spring Session, event journal 있음 |
| `M_SYSSE001I` | X | ✅ 적용 | Jet 집계 결과 |
| `M_SYSSE002I` | X | ✅ 적용 | Jet 집계 결과 |
| `M_SYSSE014I` | X | ✅ 적용 | Jet 집계 결과 |
| `M_SYSSE015I` | X | ✅ 적용 | Jet + 스케줄러 |
| `sys_spring_ready` | X | ✅ 적용 | 클러스터 부팅 동기화용 |
| `BCB001I` | O | ❌ 제외 | 재시작 시 DB에서 loadAll |

---

## 2-2. 문제점 1: Event Journal은 Persistence 대상이 아님 ⚠️

```xml
<map name="bt_sessions">
    <data-persistence enabled="true"/>    <!-- 맵 데이터는 디스크 저장 -->
    <event-journal enabled="true">         <!-- 링버퍼, 메모리 전용 -->
        <capacity>10000</capacity>
    </event-journal>
</map>
```

Event Journal은 **링버퍼(RingBuffer) 구조로 메모리에만 존재**하며, Persistence로 저장되지 않는다.

### 재시작 시나리오

```
재시작 전: bt_sessions = { "sess_A", "sess_B", "sess_C" }
           Event Journal = [ADDED sess_A, UPDATED sess_A, ADDED sess_B, ...]

재시작 후: bt_sessions = { "sess_A", "sess_B", "sess_C" }  ← Persistence로 복원
           Event Journal = [ ]                              ← 비어있음

Jet Pipeline 재제출:
  JournalInitialPosition.START_FROM_OLDEST로 시작
  → 비어있는 Journal의 "가장 오래된 위치"부터 읽음
  → 복원된 bt_sessions의 기존 엔트리들은 이벤트로 재발행되지 않음
  → SessionInfo 집계 상태 재구성 안 됨
```

### 영향

파생 맵들(M_SYSSE001I 등)도 Persistence로 함께 복원되므로 **실질적 데이터 손실은 없음**. 다만 재시작 당시 mapStateful에 쌓이던 **불완전 세션의 부분 집계는 소실**된다.

---

## 2-3. 문제점 2: `sys_spring_ready` 맵의 stale UUID 누적 ⚠️

`ClusterReadinessCoordinator`는 각 노드 UUID를 이 맵에 등록한다.

```java
// ClusterReadinessCoordinator.java:31
readyMap.put(localUuid, true);
```

### 문제

- Hazelcast는 기동할 때마다 **새 UUID를 생성**한다
- Persistence 설정 시 **과거 기동분 UUID들이 디스크에 영구 누적**된다
- 재기동을 100회 하면 맵에 300개(3노드 × 100회)의 쓰레기 UUID가 쌓인다

### 동작 정상성

검증 로직은 현재 멤버 UUID만 체크하므로 동작상 오류는 없다.

```java
// 현재 클러스터 멤버의 UUID만 검사 → 과거 UUID는 매칭되지 않으니 무해
for (Member member : hazelcastInstance.getCluster().getMembers()) {
    if (readyMap.containsKey(member.getUuid().toString())) { ... }
}
```

### 권장 조치

이 맵은 **Persistence 대상에서 제외**한다. 클러스터 기동 동기화용 임시 데이터이므로 재시작 시 초기화되는 것이 오히려 정상.

```xml
<map name="sys_spring_ready">
    <!-- data-persistence 설정하지 않음 -->
</map>
```

또는 기동 초기에 `readyMap.clear()`를 호출하여 과거 데이터를 정리하는 방법도 있음.

---

## 2-4. 문제점 3: FULL_RECOVERY_ONLY 정책의 가용성 위험 ⚠️⚠️

```xml
<cluster-data-recovery-policy>FULL_RECOVERY_ONLY</cluster-data-recovery-policy>
```

### 시나리오

```
재시작 시도: hz-node1 ✅ hz-node2 ✅ hz-node3 ❌ (디스크 장애)
         ↓
         FULL_RECOVERY_ONLY 정책에 의해
         → 모든 노드 복귀까지 클러스터 시작 안 함
         → 서비스 전체 중단 상태 유지 😱
```

### 대안 정책 검토

| 정책 | 데이터 안전성 | 가용성 | 권장 상황 |
|------|-------------|--------|----------|
| `FULL_RECOVERY_ONLY` | 100% | 낮음 | 데이터 유실 절대 불가 |
| `PARTIAL_RECOVERY_MOST_RECENT` | 일부 유실 가능 | 높음 | 가용성 우선 |
| `PARTIAL_RECOVERY_MOST_COMPLETE` | 일부 유실 가능 | 중간 | 균형 |

하드웨어 장애 대비를 고려하면 `PARTIAL_RECOVERY_MOST_COMPLETE`가 더 현실적일 수 있다.

---

## 2-5. 문제점 4: Docker 컨테이너의 Volume 미설정 시 디스크 데이터 유실 ⚠️⚠️⚠️

**가장 치명적인 운영 함정**이다.

### 현재 상태

현재 `hazelcast-dcoker.yml`:

```yaml
hz-node1:
  volumes:
    - ./hazelcast.xml:/opt/hazelcast/config/hazelcast.xml  # 설정만 마운트
```

### Persistence 디렉토리가 Volume으로 마운트되지 않으면

```
컨테이너 실행 중: /opt/hazelcast/persistence/ 에 디스크 기록
         ↓
docker stop / docker rm 시점
         ↓
컨테이너 파일시스템 삭제 → Persistence 데이터도 함께 삭제 💀
         ↓
재시작해도 복원할 디스크 데이터 없음
```

### 필수 조치

반드시 Docker Volume을 마운트한다.

```yaml
hz-node1:
  volumes:
    - ./hazelcast.xml:/opt/hazelcast/config/hazelcast.xml
    - hz-node1-persistence:/opt/hazelcast/persistence   # 추가 필수
    - hz-node1-backup:/opt/hazelcast/persistence-backup # 추가 필수

volumes:
  hz-node1-persistence:
  hz-node2-persistence:
  hz-node3-persistence:
  hz-node1-backup:
  hz-node2-backup:
  hz-node3-backup:
```

### 주의사항

각 노드는 **고유한 Volume**을 가져야 한다. 공유 Volume 사용 시 파일 충돌이 발생한다.

---

## 2-6. 문제점 5: BCB001I와 다른 맵 간의 일시적 불일치 가능성 ⚠️

### 기동 순서의 차이

```
재시작 시 기동 순서:
1. Persistence 복원  → M_SYSSE001I, M_SYSSE002I 등 즉시 사용 가능
2. MapStore loadAll() → BCB001I 적재 (MapStoreLoaderRunner에서 실행, 다소 지연)
    ↓
이 사이 시간 동안:
  - M_SYSSE001I 등은 데이터 있음 ✅
  - BCB001I는 비어있음 ❌
```

### 현재 프로젝트에서의 영향

BCB001I는 Jet Pipeline이나 다른 맵에서 참조되지 않으므로 **현재 구조에서는 문제없음**. 단, 향후 BCB001I를 참조하는 로직이 추가되면 주의 필요.

---

## 2-7. 문제점 6: 스케줄러의 Missed Tick (Persistence와 무관한 일반 재시작 이슈)

`MSYSSE015IScheduler`는 매 정시에 실행된다.

```
재시작 시간대: 14:55 ~ 15:05 (10분 소요)
         ↓
15:00 정시 tick 실행 예정이었으나 클러스터 다운 상태
         ↓
scheduleAtFixedRate는 missed tick을 따라잡지 않음
         ↓
M_SYSSE015I[20260420_15]의 첫 집계가 누락되어
다음 정시(16:00)까지 빈 상태로 유지
```

### 대응

재시작 직후 한 번 즉시 실행을 추가하는 로직 보완 필요.

---

## 2-8. 문제점 7: bt_sessions 고빈도 쓰기에 대한 Disk I/O 부하

`bt_sessions`는 Spring Session의 특성상 **모든 HTTP 요청마다 업데이트**가 발생할 수 있는 고빈도 맵이다.

```xml
<map name="bt_sessions">
    <data-persistence enabled="true">
        <fsync>false</fsync>    <!-- OS 버퍼로만 쓰기, I/O 부하 완화 -->
    </data-persistence>
</map>
```

`fsync=false` 권장. 크래시 시 소량 데이터 유실 가능하나, 대신 I/O 부하 감소.

---

## 2-9. 문제점 8: 직렬화 호환성 - Spring Session 내부 클래스

`bt_sessions`에 저장되는 값은 Spring Session의 **`HazelcastSessionRepository.HazelcastSession`** 내부 클래스이다. 이 클래스는 사용자 정의 모델이 아니라 **라이브러리 제공 클래스**이므로:

- `spring-session-hazelcast` 라이브러리 버전 업그레이드 시 이 클래스의 직렬화 포맷이 변경될 수 있음
- Persistence로 저장된 기존 데이터와 호환되지 않을 경우 복원 실패
- **라이브러리 업그레이드 시 기존 Persistence 데이터 삭제 필요할 수 있음**

---

## 2-10. 권장 설정 (전체)

```xml
<!-- 클러스터 레벨 -->
<persistence enabled="true">
    <base-dir>/opt/hazelcast/persistence</base-dir>
    <backup-dir>/opt/hazelcast/persistence-backup</backup-dir>
    <cluster-data-recovery-policy>PARTIAL_RECOVERY_MOST_COMPLETE</cluster-data-recovery-policy>
    <auto-remove-stale-data>true</auto-remove-stale-data>
</persistence>

<!-- Persistence 적용 -->
<map name="bt_sessions">
    <data-persistence enabled="true">
        <fsync>false</fsync>
    </data-persistence>
</map>

<map name="M_SYSSE001I"> <data-persistence enabled="true"><fsync>false</fsync></data-persistence> </map>
<map name="M_SYSSE002I"> <data-persistence enabled="true"><fsync>false</fsync></data-persistence> </map>
<map name="M_SYSSE014I"> <data-persistence enabled="true"><fsync>false</fsync></data-persistence> </map>
<map name="M_SYSSE015I"> <data-persistence enabled="true"><fsync>false</fsync></data-persistence> </map>

<!-- Persistence 제외 (쓰레기 누적 방지) -->
<map name="sys_spring_ready">
    <!-- data-persistence 설정 안 함 -->
</map>

<!-- MapStore 맵은 제외 (사용자 조건) -->
<map name="BCB001I">
    <map-store enabled="true">...</map-store>
    <!-- data-persistence 설정 안 함 -->
</map>
```

---

## 2-11. 필수 체크리스트

- [ ] **Docker Volume 설정** (각 노드별 고유 Volume) - 최우선
- [ ] `sys_spring_ready` 맵 Persistence 제외
- [ ] `cluster-data-recovery-policy` 정책 결정 (가용성 vs 데이터 안전성)
- [ ] `fsync=false` 적용 (고빈도 맵 I/O 부하 방지)
- [ ] 스케줄러 missed tick 대응 로직 추가 검토
- [ ] Spring Session 라이브러리 업그레이드 시 Persistence 데이터 정리 절차 문서화
- [ ] Persistence 디렉토리 디스크 용량 모니터링 체계 수립

---

## 2-12. 사전 조건 확인

**Hazelcast Persistence는 Enterprise 라이센스가 필요한 기능**이다. 현재 프로젝트는 오픈소스 버전(`hazelcast:5.5.0`)을 사용하고 있으므로, 이 기능을 실제 적용하기 위해서는 Enterprise 라이센스 확보가 선행되어야 한다.

오픈소스 버전 하에서 유사한 효과를 얻기 위한 대안:
- **Rolling Restart** (노드 순차 재기동)
- **MapStore 추가** (DB를 영속 저장소로 활용)
- **외부 저장소로 주기적 스냅샷 + 기동 시 복원 로직 구현**
