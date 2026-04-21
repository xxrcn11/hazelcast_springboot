# Hazelcast Persistence 클러스터 재기동 운영 가이드

## 개요

Persistence가 설정된 3노드 Hazelcast 클러스터의 로직 변경 반영 및 재기동 시 운영 가이드.

- **Part 1**: 3노드 클러스터 로직 반영 재기동 순서 (이론)
- **Part 2**: 실제 배포 환경의 현실적인 문제와 대응 (실무)

---

# Part 1. 3노드 클러스터 로직 반영 재기동 순서

## 1-1. 상황 전제

- 3개 노드: `node1` (마스터), `node2`, `node3`
- Persistence 활성화됨
- 로직 수정된 JAR 배포 완료 상태
- 각 노드의 Persistence Volume은 독립적

## 1-2. 방식 선택: Full Restart vs Rolling Restart

먼저 수정된 로직의 종류를 확인해야 한다.

| 수정 범위 | 권장 방식 |
|----------|----------|
| **Jet Pipeline 로직 변경** | **Full Restart** 필수 |
| **EntryProcessor, Scheduler 로직 변경** | **Full Restart** 권장 |
| **MapStore, REST API 등 기타 로직** | Rolling Restart 가능 |
| **Hazelcast 설정(hazelcast.xml) 변경** | Full Restart 필수 |

**이유:** Jet Pipeline은 `jet.getJob("SessionJetPipelineJob") != null` 체크로 **이미 실행 중이면 제출을 건너뛰므로**, Rolling Restart 시 새 로직이 반영되지 않는다.

```java
// SessionJetPipelineConfig.java:34
if (jet.getJob("SessionJetPipelineJob") != null) {
    log.info("[SessionJetPipeline] Job 'SessionJetPipelineJob' is already submitted or running...");
    return;  // ← 새 로직이 있어도 덮어쓰지 않음
}
```

**따라서 대부분의 경우 Full Restart를 권장한다.**

---

## 1-3. Full Restart 순서 (권장)

### Phase 1: 사전 준비

```
[0분] 배포 전 체크리스트
  ├── 새 JAR 3개 노드에 업로드 완료 확인
  ├── hazelcast.xml 변경 사항 확인
  ├── docker-compose.yml 변경 사항 확인
  └── Persistence Volume 백업 (선택, 안전 장치)
       └── docker run --rm -v hz-node1-persistence:/src -v $(pwd):/backup \
                      alpine tar czf /backup/node1-$(date +%Y%m%d_%H%M).tar.gz -C /src .
```

### Phase 2: 중지 순서 (역순 종료)

**핵심 원칙:** **마스터를 가장 마지막에 종료** — 마스터가 종료 과정을 조율하도록 한다.

```
[1분] node3 graceful shutdown
  $ docker stop hz-node3
  ├── Hazelcast가 자동으로 graceful shutdown 프로토콜 실행
  ├── node3의 primary 파티션들을 node1/node2로 마이그레이션
  ├── Persistence에 최종 상태 기록
  └── 프로세스 종료

[2분] node3 종료 확인
  $ docker logs hz-node3 --tail 20
  └── "Hazelcast Shutdown is completed" 메시지 확인

[3분] node2 graceful shutdown
  $ docker stop hz-node2
  └── 동일 프로세스

[4분] node1 (마스터) graceful shutdown
  $ docker stop hz-node1
  ├── 이 시점에 클러스터 완전 종료
  ├── 모든 데이터 Persistence 디스크에 flush
  └── 서비스 다운 시점 ⚠️
```

**⚠️ 하지 말 것:**
- `docker kill` (SIGKILL): Persistence 데이터 불일치 가능
- 전체 동시 중지: 파티션 마이그레이션 과정 건너뛰어 데이터 복구 실패 위험

### Phase 3: 시작 순서 (마스터 먼저)

**핵심 원칙:** **마스터부터 시작** — 조인 순서상 먼저 기동하는 노드가 마스터가 된다.

#### 방식 A: `FULL_RECOVERY_ONLY` 정책인 경우

```
[5분] node1 시작
  $ docker start hz-node1
  ├── Persistence 디스크 검증 시작
  ├── 다른 노드 UUID 정보와 매칭 대기 (자기 혼자서는 진행 안 함)
  └── 🔄 "Waiting for cluster members to form" 상태 대기

[6분] node2 시작
  $ docker start hz-node2
  ├── node1에 조인
  └── 🔄 여전히 FULL_RECOVERY_ONLY 대기 중

[7분] node3 시작
  $ docker start hz-node3
  ├── 3개 노드 모두 모임 → 파티션 검증 완료
  ├── 각 노드 디스크에서 자신의 파티션 데이터 로드
  ├── 클러스터 STATE: ACTIVE 전환
  └── ServerBootstrapListener 트리거 (node1이 마스터로서 초기화 실행)
       ├── ClusterReadinessCoordinator: 각 노드 UUID 등록
       ├── 클러스터 동기화 대기 (모든 노드 Spring Ready)
       ├── MappingSqlRunner: SQL Mapping 등록
       ├── MapStoreLoader: BCB001I DB에서 loadAll
       ├── SessionJetPipelineConfig: 새 로직으로 Pipeline 제출 ✨
       └── MSYSSE015IScheduler: 스케줄러 등록

[8분] 클러스터 정상화 ✅
```

#### 방식 B: `PARTIAL_RECOVERY_MOST_COMPLETE` 정책인 경우

3개 노드를 **가능한 빠르게 연속 시작**한다.

```
[5분+0초]  $ docker start hz-node1 && docker start hz-node2 && docker start hz-node3

[5~7분] 모든 노드 거의 동시 시작
  └── 모두 같은 시점에 디스크 검증 시작 → 파티션 정보 교환 → 복원

[7분] 클러스터 ACTIVE ✅
```

**주의:** PARTIAL 정책에서도 **3개가 모두 모이기 전에 기동되면** 일부 파티션 데이터가 빠진 채로 시작될 수 있다. 전체 재시작 시에는 **빠르게 연속 시작**하는 것이 안전하다.

### Phase 4: 검증

```
[8분] 새 로직 정상 반영 확인
  $ docker logs hz-node1 | grep "SessionJetPipeline"
  └── "[SessionJetPipeline] Job submitted successfully" 로그 확인

  $ docker exec hz-node1 hz-cli cluster shell
  > jobs
  └── SessionJetPipelineJob이 새 ID로 기동되었는지 확인

  $ docker logs hz-node1 | grep "cluster is ready"
  └── 클러스터 동기화 완료 로그 확인

  Management Center 접속 (http://localhost:8080)
  └── 데이터 복원 상태 및 맵 크기 확인
```

---

## 1-4. Rolling Restart 순서 (무중단, 제한적)

**적용 가능한 경우:** Jet Pipeline/Scheduler 로직 변경이 **없을 때**만

### 핵심 원칙

- **마스터(node1)를 가장 마지막에 재시작**
- 마스터가 비마스터로 강등된 상태에서 업데이트 완료 후 재시작
- 한 노드씩 순차 진행

### 순서

```
[1분] node3 재시작
  $ docker stop hz-node3
  ├── node3의 파티션 → node1/node2로 마이그레이션 (backup에서 primary로 승격)
  ├── node3의 Persistence 디렉토리는 그대로 유지
  └── 서비스는 node1/node2에서 계속 제공 ✅

  $ docker rm hz-node3 && docker-compose up -d hz-node3
  ├── 새 JAR로 재기동
  ├── 클러스터 조인 → 자기 Persistence 디스크 + 다른 노드 partition 조합
  │   (auto-remove-stale-data=true면 충돌 시 자동 정리)
  ├── 파티션 재분배 (node1/node2에서 일부가 node3로 이동)
  └── ServerBootstrapListener: 마스터가 아니므로 Pipeline/Scheduler 초기화 skip ✅

[3분] node3 정상 조인 확인
  $ docker logs hz-node3 | grep "Cluster members after"
  └── 3개 멤버 모두 보이는지 확인

[4분] node2 재시작 (동일 절차)
  $ docker stop hz-node2
  $ docker rm hz-node2 && docker-compose up -d hz-node2

[7분] node1 (마스터) 재시작 ⭐ 가장 마지막
  $ docker stop hz-node1
  ├── 마스터 역할이 node2 또는 node3로 이양
  │   (현재 기준 oldest member가 새 마스터)
  └── 파티션 마이그레이션

  $ docker rm hz-node1 && docker-compose up -d hz-node1
  ├── node1 새 JAR로 기동
  ├── 다른 노드보다 늦게 조인 → 마스터 아님
  └── ServerBootstrapListener: 마스터 체크에서 skip됨 ✅

[10분] 모든 노드 새 JAR로 업데이트 완료
  ├── 서비스 중단 없이 배포 완료 ✅
  └── BUT: Jet Pipeline은 여전히 OLD 로직으로 실행 중 ⚠️
```

### Rolling Restart 후 Jet Pipeline 갱신 (필요 시)

로직 변경이 Jet Pipeline에 영향을 주는 경우, Rolling Restart 후 추가 조치:

```
[11분] 기존 Jet Job 수동 취소
  $ docker exec hz-node1 hz-cli job cancel SessionJetPipelineJob

[12분] 현재 마스터 노드에서 새 Job 제출 트리거
  옵션 1: 마스터 노드 한 번 더 재시작 (Pipeline 제출 트리거)
  옵션 2: REST API나 JMX를 통해 initPipeline() 수동 호출
```

---

## 1-5. 재기동 순서 요약 (시각화)

### Full Restart (권장)

```
  중지 순서 (역순):         시작 순서 (정순):
  ┌────────┐               ┌────────┐
  │ node3  │ ⬇ 먼저        │ node1  │ ⬆ 먼저 (마스터 지정)
  └────────┘               └────────┘
  ┌────────┐               ┌────────┐
  │ node2  │ ⬇              │ node2  │ ⬆
  └────────┘               └────────┘
  ┌────────┐               ┌────────┐
  │ node1  │ ⬇ 마지막       │ node3  │ ⬆ 마지막
  └────────┘               └────────┘
```

### Rolling Restart

```
  순차 재시작 (한 번에 한 노드):
  ┌────────┐
  │ node3  │ ⟲ 1회차 (non-master 먼저)
  └────────┘
  ┌────────┐
  │ node2  │ ⟲ 2회차
  └────────┘
  ┌────────┐
  │ node1  │ ⟲ 3회차 (마스터 마지막)
  └────────┘
```

---

## 1-6. 재기동 시 절대 하지 말 것

1. **마스터(node1)를 첫 번째로 중지 안 함** (Full Restart에서)
   - 다른 노드들이 마스터 이양 대기 중 혼란
   - Graceful shutdown 프로토콜 지연

2. **`docker kill` (SIGKILL) 사용 금지**
   - Persistence 데이터 불일치
   - 다음 기동 시 `auto-remove-stale-data` 없으면 복원 실패

3. **3개 노드 동시 기동 후 한 노드만 먼저 기동 완료되는 상황 방치**
   - 먼저 올라온 노드가 "잘못된 마스터"로 혼자 클러스터 형성
   - 다른 노드는 조인 실패 → split-brain

4. **Persistence Volume 삭제하고 재기동**
   - 모든 데이터 유실
   - 의도한 것이 아니라면 절대 금지

5. **마스터에서만 JAR 업데이트하고 다른 노드 스킵**
   - 직렬화 호환성 깨짐 → 클러스터 조인 불가
   - 반드시 **모든 노드 동일 버전**으로 배포

---

## 1-7. 최종 권장 순서

**Jet Pipeline 로직 변경 포함 시 (대부분의 경우):**

```
1. node3 stop → 2. node2 stop → 3. node1(마스터) stop
           ↓ 전체 중지 완료
4. node1 start → 5. node2 start → 6. node3 start
           ↓ 빠른 연속 시작 (10초 이내)
7. 클러스터 ACTIVE 확인
8. 새 Pipeline 로그 확인 (SessionJetPipelineJob 신규 제출)
9. Management Center에서 데이터 복원 검증
```

이 순서가 **데이터 안전성과 새 로직 반영 확실성** 모두 보장한다.

---

# Part 2. 실제 배포 환경의 현실적인 문제와 대응

## 2-1. 배포 시스템 현황 (전제)

실제 프로젝트의 배포 시스템:
- 빌드 서버에서 JAR 생성
- 배포 대상 서버로 JAR 복사
- 각 노드에 **동시 접속하여 stop.sh 실행**
- **10초 후 start.sh 실행**

이 방식은 Stateless 서비스에는 적합하지만, Hazelcast 같은 **Stateful Cluster**에서는 심각한 문제를 일으킨다.

---

## 2-2. 현재 방식의 잠재 문제들

### 문제 1: 불완전한 Graceful Shutdown

```
모든 노드에 stop.sh 동시 실행
    ↓
각 노드는 다른 노드로 파티션 마이그레이션 시도
    ↓
하지만 이미 모든 노드가 shutdown 중 → 마이그레이션 대상 없음
    ↓
각 노드가 "migration target 없음" 타임아웃 대기
    ↓
10초는 너무 짧음 (Hazelcast 기본 graceful shutdown은 30초+)
    ↓
타임아웃 전에 start.sh 실행되면 이전 프로세스와 충돌 가능 ⚠️
```

### 문제 2: Persistence 디스크 쓰기 미완료

```
stop.sh 실행 (SIGTERM)
    ↓
Hazelcast가 메모리 → 디스크 flush 시작
    ↓
fsync=false라면 OS 페이지 캐시에만 기록된 상태
    ↓
컨테이너 종료되면서 OS 캐시도 같이 사라질 수 있음
    ↓
Persistence 데이터 불완전 상태로 종료 🔥
```

### 문제 3: 기동 시 Cluster Formation 레이스

```
3개 노드 동시 start
    ↓
각 노드가 서로를 TCP로 탐지 시도
    ↓
시나리오 A (정상):
  node1이 제일 먼저 리스닝 시작 → node2/3가 조인 → 클러스터 형성

시나리오 B (문제):
  node2가 빨리 기동되어 혼자 클러스터 형성 시도
  → PARTIAL_RECOVERY_MOST_COMPLETE라면 혼자 기동 시도
  → node1의 파티션 일부만 갖고 시작
  → node1/3가 나중에 조인하면 데이터 충돌
  → auto-remove-stale-data=true면 node1/3의 데이터 삭제됨 💀
```

### 문제 4: Jet Pipeline은 새 로직으로 갱신되지 않음

```
재기동 후 master(node1)의 SpringBootStrapListener 실행
    ↓
Jet Job "SessionJetPipelineJob" 이미 존재 확인 (다른 노드에 있거나 아직 제거 안 됨)
    ↓
if (jet.getJob("SessionJetPipelineJob") != null) return;
    ↓
새 로직이 반영되지 않음 ⚠️
```

### 문제 5: 클러스터 상태 미확인으로 배포 성공 처리

```
배포 시스템의 판단: start.sh 반환 코드 0 = 성공
    ↓
실제로는 아직 클러스터 형성 중이거나 데이터 복원 중
    ↓
후속 배포(다른 서비스)가 연결 시도 → 실패 🔥
```

---

## 2-3. 실제 운영 환경의 대응 패턴

### 패턴 1: 배포 스크립트 개선 (최소 필수)

#### stop.sh 개선안

```bash
#!/bin/bash
# stop.sh - Hazelcast graceful shutdown

CONTAINER_NAME=${1:-hz-node1}
GRACEFUL_TIMEOUT=60  # Hazelcast 기본값 + 여유

echo "[$(date)] Starting graceful shutdown of ${CONTAINER_NAME}"

# 1. Docker stop은 SIGTERM 후 timeout 초 대기, 이후 SIGKILL
docker stop --time=${GRACEFUL_TIMEOUT} ${CONTAINER_NAME}

# 2. 종료 로그 확인
if docker logs ${CONTAINER_NAME} --tail 50 2>&1 | grep -q "Hazelcast Shutdown is completed"; then
    echo "[$(date)] ✅ Graceful shutdown completed"
    exit 0
else
    echo "[$(date)] ⚠️ Graceful shutdown NOT confirmed in logs"
    exit 1  # 배포 시스템에서 실패로 감지
fi
```

#### start.sh 개선안

```bash
#!/bin/bash
# start.sh - Hazelcast startup with health check

CONTAINER_NAME=${1:-hz-node1}
MAX_WAIT_SECONDS=300  # 5분 대기

echo "[$(date)] Starting ${CONTAINER_NAME}"
docker start ${CONTAINER_NAME}

# 1. 클러스터 ACTIVE 상태 확인 (heartbeat)
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT_SECONDS ]; do
    if docker logs ${CONTAINER_NAME} --tail 100 2>&1 | grep -q "cluster is ready"; then
        echo "[$(date)] ✅ Cluster ready"
        break
    fi

    if docker logs ${CONTAINER_NAME} --tail 100 2>&1 | grep -q "Failed to initialize cluster"; then
        echo "[$(date)] ❌ Cluster initialization failed"
        exit 1
    fi

    sleep 5
    ELAPSED=$((ELAPSED + 5))
done

if [ $ELAPSED -ge $MAX_WAIT_SECONDS ]; then
    echo "[$(date)] ❌ Timeout waiting for cluster ready"
    exit 1
fi
```

---

### 패턴 2: 배포 시스템에 순차 실행 옵션 추가

동시 실행 대신 **순차 + 대기** 패턴으로 전환.

```bash
#!/bin/bash
# deploy-cluster.sh - 배포 오케스트레이션 스크립트

NODES=("hz-node3" "hz-node2" "hz-node1")  # 마스터 마지막
NEW_JAR="/path/to/new-hazelcast-app.jar"

echo "[$(date)] === Cluster deployment start ==="

# 1. JAR 배포 (전 노드 동시 가능, 아직 재기동 안 함)
for node in "${NODES[@]}"; do
    scp ${NEW_JAR} ${node}:/opt/app/lib/ &
done
wait

# 2. 재기동 (순차)
for node in "${NODES[@]}"; do
    echo "[$(date)] Restarting ${node}..."

    ssh ${node} "./stop.sh" || { echo "Stop failed on ${node}"; exit 1; }
    ssh ${node} "./start.sh" || { echo "Start failed on ${node}"; exit 1; }

    # 클러스터 안정화 대기 (파티션 재분배)
    echo "[$(date)] Waiting 30s for cluster stabilization..."
    sleep 30

    # 건강 상태 확인
    ssh ${node} "curl -sf http://localhost:5701/hazelcast/health/cluster-state" \
        | grep -q "ACTIVE" || { echo "Cluster not ACTIVE after ${node}"; exit 1; }
done

echo "[$(date)] === Cluster deployment complete ==="
```

---

### 패턴 3: Hazelcast 전용 Cluster Shutdown

동시 stop의 **가장 안전한 방법**은 `hz-cli`로 클러스터 전체에 통합 shutdown 명령을 내리는 것이다.

```bash
#!/bin/bash
# cluster-shutdown.sh - 마스터 노드에서만 실행

# 1. 마스터 노드에 붙어서 전체 클러스터 shutdown 명령 실행
ssh hz-node1 "docker exec hz-node1 hz-cli --config /opt/hazelcast/config/hazelcast.xml \
    cluster shutdown --cluster-name=dev"

# 이 명령은:
# ├── 모든 노드에 동기화된 shutdown 신호 전파
# ├── 파티션 마이그레이션 건너뜀 (전체 종료이므로)
# ├── Persistence에 최종 상태 저장 후 순차 종료
# └── 배포 시스템이 stop.sh를 개별 호출할 필요 없음

echo "[$(date)] Cluster-wide shutdown completed"
```

---

### 패턴 4: Cluster State 기반 배포

Hazelcast의 Cluster State를 활용하여 **트래픽 격리 후 재기동**.

```bash
#!/bin/bash
# stateful-deploy.sh

# 1. FROZEN 상태로 전환 (새 연결 차단, 기존 처리는 계속)
ssh hz-node1 "docker exec hz-node1 hz-cli cluster change-state FROZEN"

# 2. 모든 노드 stop/start (동시에 해도 안전)
for node in hz-node3 hz-node2 hz-node1; do
    ssh ${node} "./stop.sh" &
done
wait

sleep 10

for node in hz-node1 hz-node2 hz-node3; do
    ssh ${node} "./start.sh" &
done
wait

# 3. 클러스터 복귀 대기
sleep 60

# 4. ACTIVE 상태로 복귀 (트래픽 수용)
ssh hz-node1 "docker exec hz-node1 hz-cli cluster change-state ACTIVE"
```

**Cluster State별 동작:**

| 상태 | 새 연결 | 읽기 | 쓰기 | 파티션 마이그레이션 |
|------|:------:|:----:|:----:|:---------------:|
| ACTIVE | ✅ | ✅ | ✅ | ✅ |
| FROZEN | ❌ | ✅ | ✅ | ❌ |
| PASSIVE | ❌ | ✅ | ❌ | ❌ |
| NO_MIGRATION | ✅ | ✅ | ✅ | ❌ |

---

### 패턴 5: 배포 파이프라인에 Jet Job 관리 포함

Jet Pipeline 로직 변경 시 반드시 포함.

```bash
#!/bin/bash
# deploy-with-jet-reset.sh

# 1. 기존 Jet Job 취소 (배포 전)
ssh hz-node1 "docker exec hz-node1 hz-cli job cancel SessionJetPipelineJob --force"
sleep 5

# 2. 클러스터 재기동 (위 패턴 중 선택)
./stateful-deploy.sh

# 3. 재기동 후 Pipeline 자동 재제출 여부 확인
ssh hz-node1 "docker logs hz-node1 --tail 200 | grep 'Job submitted successfully'"
```

---

## 2-4. 실무에서 널리 쓰이는 3가지 아키텍처

### 아키텍처 A: 단순한 개선 (중소 규모)

기존 stop.sh/start.sh에 다음만 추가:
- stop 전에 마스터에게 `cluster shutdown` 명령
- start 후 health check로 ACTIVE 상태 확인
- 배포 시스템이 순차 실행 지원하도록 변경

**비용:** 낮음 | **안정성:** 중간

---

### 아키텍처 B: Hazelcast Management Center + 외부 오케스트레이션

```
빌드 서버
   │
   │ 배포 요청
   ▼
Jenkins/GitLab CI 파이프라인
   │
   ├── 1. Management Center에 cluster.shutdown() REST 호출
   ├── 2. 전 노드 JAR 교체 (Ansible/SCP 병렬)
   ├── 3. 각 노드 순차 기동 (Ansible playbook)
   ├── 4. Health Check API 폴링 (ACTIVE까지)
   ├── 5. Jet Job 재제출 확인
   └── 6. 성공 시 배포 완료 마킹
```

**비용:** 중간 | **안정성:** 높음 | 가장 일반적

---

### 아키텍처 C: Kubernetes Operator (대규모)

Hazelcast Platform Operator 사용.

```yaml
apiVersion: hazelcast.com/v1alpha1
kind: Hazelcast
metadata:
  name: hazelcast-prod
spec:
  clusterSize: 3
  persistence:
    baseDir: /opt/hazelcast/persistence
    clusterDataRecoveryPolicy: PartialRecoveryMostComplete
  updateStrategy:
    type: RollingUpdate
    maxUnavailable: 1   # 한 번에 1개만 업데이트
```

Operator가 자동으로:
- 한 노드씩 drain → stop → restart → 검증
- 파티션 마이그레이션 완료까지 대기
- 이상 감지 시 롤백
- Jet Job lifecycle 관리

**비용:** 높음 (초기 구축) | **안정성:** 매우 높음

---

## 2-5. 당장 취할 수 있는 개선 방향

### 1단계 (즉시 가능): stop.sh / start.sh에 확인 로직 추가

```bash
# stop.sh 끝에:
grep "Hazelcast Shutdown is completed" /var/log/hazelcast.log || exit 1

# start.sh 끝에:
for i in {1..60}; do
    curl -sf http://localhost:5701/hazelcast/health/cluster-state | grep ACTIVE && break
    sleep 5
done
```

### 2단계 (배포 시스템 협의): 동시 실행 → 순차 실행 옵션 추가 요청

### 3단계 (중장기): `hz-cli cluster shutdown` 활용한 통합 shutdown 패턴 도입

### 4단계 (선택): Operator 기반 관리로 전환

---

## 2-6. 현실적인 최소 조치 요약

| 항목 | 현재 | 권장 |
|------|------|------|
| Stop 방식 | 개별 노드 동시 SIGTERM | **마스터에서 `cluster shutdown`** 명령 |
| Start 방식 | 10초 후 동시 | 순차 시작 + health check |
| 확인 방식 | 프로세스 생존 | **ACTIVE 상태 확인** |
| Jet Pipeline | 무관리 | 배포 전 **job cancel** |
| Persistence fsync | false | **bt_sessions는 true** 권장 (운영) |
| 배포 성공 판단 | start.sh 종료 | **클러스터 ACTIVE + Job Running** 확인 |

---

## 2-7. 핵심 메시지

Hazelcast는 "그냥 stop/start"로 다룰 수 없는 **상태 저장 클러스터(Stateful Cluster)** 다. 일반적인 Stateless 배포 파이프라인 그대로 사용하면 반드시 문제가 발생한다. 배포 시스템에 Hazelcast 특화 로직을 **반드시 추가**해야 한다.

### 필수 변경 체크리스트

- [ ] 동시 stop/start를 **순차 stop/start**로 변경
- [ ] 10초 대기를 **graceful shutdown 완료 확인**으로 변경
- [ ] start.sh에 **cluster-state ACTIVE 확인** 로직 추가
- [ ] Jet Pipeline 로직 변경 배포 시 **job cancel 단계** 추가
- [ ] 배포 성공 기준을 **프로세스 생존 → 클러스터 ACTIVE + Job Running**으로 변경
- [ ] 배포 실패 시 **롤백 절차** 문서화 (Persistence 백업 활용)
- [ ] Persistence Volume 주기적 백업 스케줄 수립
