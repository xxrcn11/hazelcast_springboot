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

---

# Part 3. 일반 환경(비 Docker) 배포 시나리오 검토

## 3-1. 배포 시나리오 전제

```
JVM 옵션:
  -Dhazelcast.shutdownhook.policy=GRACEFUL     ← JVM Shutdown Hook에서 graceful shutdown 실행
  -Dhazelcast.graceful.shutdown.max.wait=60    ← 최대 60초 대기

복구 정책: PARTIAL_RECOVERY_MOST_COMPLETE
Shutdown: node3 → node2 → node1 (순차)
Startup: 3개 노드 동시
```

환경: 일반 Linux 서버에서 `java -jar` 직접 실행 (nohup, systemd, 커스텀 스크립트 등)

## 3-2. 결론 먼저

**현재 제공되는 stop.sh / start.sh를 그대로 사용하면 안 된다.**

일반 환경에서도 Docker 환경과 동일한 근본 문제가 있다: **stop.sh가 60초 graceful shutdown을 충분히 기다리지 않고, start.sh가 클러스터 ACTIVE 상태를 확인하지 않는다.**

## 3-3. JVM 옵션 평가 ✅

```
-Dhazelcast.shutdownhook.policy=GRACEFUL
-Dhazelcast.graceful.shutdown.max.wait=60
```

일반 환경에서 동작 메커니즘:

```
kill <PID> (SIGTERM)
    ↓
JVM Shutdown Hook 트리거
    ↓
Hazelcast GRACEFUL shutdown 로직 실행
    ├── 파티션 마이그레이션 (다른 노드로)
    ├── 진행 중 연산 완료 대기
    ├── Persistence 디스크 flush
    └── 리소스 해제
    ↓
최대 60초 대기
    ↓
프로세스 정상 종료 → JVM exit
```

**핵심:** JVM이 **자발적으로 종료**하는 데 최대 60초가 걸린다. stop.sh가 이 시간을 기다려주지 않으면 무용지물.

## 3-4. 순차 Shutdown 분석 (node3 → node2 → node1) ✅

```
[Step 1] node3에 SIGTERM
  $ kill $(cat /var/run/hazelcast-node3.pid)
  ├── Shutdown Hook 트리거
  ├── node3의 primary 파티션을 node1/node2로 마이그레이션
  ├── Persistence 디스크에 최종 상태 flush
  └── JVM 종료 (< 60초)

  ✅ 이 시점 클러스터: [node1 ✅ node2 ✅] 정상 서비스

[Step 2] node2에 SIGTERM
  ├── node2의 primary 파티션을 node1으로 마이그레이션
  └── 종료

  ✅ 이 시점 클러스터: [node1 ✅] 단독 운영

[Step 3] node1에 SIGTERM
  ├── 마이그레이션 대상 없음 (혼자)
  ├── 모든 파티션 데이터를 자기 디스크에 기록
  │   (node1 Persistence가 가장 완전한 최신 데이터 보유)
  └── 종료
```

**순차 Shutdown의 데이터 관점 결과:**

| 노드 | Persistence 디스크 상태 | 데이터 최신성 |
|------|----------------------|------------|
| node1 | **전체 파티션 데이터 보유** (이관 받은 것 포함) | 가장 최신 |
| node2 | node3 이관 받은 것 + 자기 것 | 중간 |
| node3 | 최초 자기 몫 (이관 후 stale) | 가장 오래됨 |

## 3-5. 동시 Startup 분석 ⚠️

**PARTIAL_RECOVERY_MOST_COMPLETE 정책 동작:**

```
3개 서버에서 동시에 start.sh 실행
  $ nohup java $JAVA_OPTS -jar /opt/app/hazelcast.jar > /var/log/hazelcast.log 2>&1 &
    ↓
각 노드가 자기 Persistence 디스크 검증 시작
    ↓
validation-timeout-seconds(기본 120초) 동안 서로를 TCP로 발견/조인
    ↓
파티션 버전 메타데이터를 비교
    ↓
MOST_COMPLETE: node1의 데이터가 가장 완전 → 채택
    ↓
node2/node3의 stale 데이터는 auto-remove-stale-data=true에 의해 제거
    ↓
node1에서 node2/node3로 파티션 재분배
    ↓
클러스터 ACTIVE ✅
```

**특이점:**
1. 초기 데이터는 node1에 집중 → rebalancing 후 분산
2. node2/node3의 Persistence 데이터는 대부분 버려짐 (이미 stale)
3. 마스터는 비결정적 (먼저 클러스터 형성하는 노드가 마스터)

## 3-6. 일반 환경 stop.sh의 전형적 문제 ❌

### 패턴 A: 짧은 sleep + SIGKILL

```bash
# 현재 stop.sh (추정)
#!/bin/bash
PID=$(cat /var/run/hazelcast.pid)
kill $PID           # SIGTERM
sleep 10            # 10초 대기
kill -9 $PID 2>/dev/null  # 살아있으면 강제 종료
```

**치명적 문제:**
```
kill $PID (SIGTERM 전송)
    ↓
JVM Shutdown Hook 트리거 → Hazelcast graceful shutdown 시작 (60초 계획)
    ↓
10초 경과 → sleep 종료
    ↓
kill -9 $PID → SIGKILL 💀
    ↓
JVM 강제 종료
    ├── 파티션 마이그레이션 중단
    ├── Persistence 디스크 flush 미완료
    └── 다음 기동 시 데이터 불일치/손상
```

### 패턴 B: pkill + 즉시 반환

```bash
# 현재 stop.sh (추정)
#!/bin/bash
pkill -f "hazelcast.jar"
echo "stopped"
```

**문제점:** SIGTERM은 전송되지만 완료 확인이 전혀 없음. start.sh가 이어서 실행되면 이전 프로세스가 아직 shutdown 중일 수 있음.

### 패턴 C: kill + 프로세스 대기 루프

```bash
# 현재 stop.sh (추정)
#!/bin/bash
PID=$(pidof -s java)
kill $PID
while kill -0 $PID 2>/dev/null; do sleep 1; done
```

**그나마 나은 편이지만:**
- graceful shutdown이 hang되면 무한 대기
- 로그 기반 완료 확인 없음
- 정상 종료인지 오류 종료인지 구분 불가

### 패턴 D: systemctl stop (systemd 관리)

```bash
# 현재 stop.sh (추정)
#!/bin/bash
systemctl stop hazelcast
```

**systemd의 기본 `TimeoutStopSec`는 90초**라 다행히 60초 graceful을 허용한다. **단, 서비스 유닛 파일에서 이 값이 더 짧게 설정되어 있으면 같은 문제 발생.**

확인 방법:
```bash
systemctl show hazelcast | grep TimeoutStopUSec
```

## 3-7. 일반 환경 start.sh의 전형적 문제 ❌

### 패턴 A: nohup + 즉시 반환

```bash
# 현재 start.sh (추정)
#!/bin/bash
cd /opt/app
nohup java $JAVA_OPTS -jar hazelcast.jar > /var/log/hazelcast.log 2>&1 &
echo $! > /var/run/hazelcast.pid
echo "started"
exit 0
```

**문제점:**
```
배포 시스템이 3개 서버에 동시에 start.sh 실행
    ↓
각 서버의 start.sh는 JVM을 백그라운드 실행하고 즉시 반환
    ↓
배포 시스템: "3개 다 exit 0 → 배포 완료" ✅ (잘못된 판단)
    ↓
실제로는:
  - JVM 아직 초기화 중
  - Persistence 복원 중
  - 클러스터 형성 중 (validation timeout 대기)
  - Jet Pipeline 아직 제출 안 됨
    ↓
후속 배포 (API 서버, 앱 서버 등) Hazelcast 연결 시도 → 실패 🔥
```

### 패턴 B: systemctl start

```bash
# 현재 start.sh (추정)
#!/bin/bash
systemctl start hazelcast
```

**문제점:** systemd는 프로세스가 **기동된 것**은 감지하지만, Hazelcast 클러스터가 **ACTIVE 상태가 된 것**은 확인하지 않는다. systemctl은 `Type=simple`이면 fork 직후 성공 반환.

## 3-8. 현재 스크립트 사용 시 문제 시나리오

현재 stop.sh / start.sh를 그대로 쓸 경우 실제로 일어날 수 있는 일:

| 단계 | 문제 | 결과 |
|------|------|------|
| node3 stop (10초 타임아웃) | graceful shutdown 중 SIGKILL | 파티션 마이그레이션 중단 |
| node2 stop (같은 문제) | Persistence flush 미완료 | 디스크 데이터 손상 |
| node1 stop (치명적!) | **모든 데이터 보유 상태에서 강제 종료** | **node1 Persistence 손상** |
| 동시 start | 모든 노드 디스크 불완전 | 복구 정책 적용해도 손상된 데이터뿐 |
| Recovery | "most complete"가 다 깨진 데이터 | **영구 데이터 유실** 💀 |

**특히 node1 shutdown 시점이 가장 위험.** node1은 모든 파티션의 primary를 혼자 갖고 있으므로 Persistence flush에 시간이 가장 오래 걸리는데, 10초 타임아웃은 이를 보장할 수 없다.

## 3-9. 필수 수정 사항

### 3-9-1. 수정된 stop.sh (일반 환경)

```bash
#!/bin/bash
# stop.sh - Hazelcast graceful shutdown for plain Linux

set -e

PID_FILE="/var/run/hazelcast.pid"
LOG_FILE="/var/log/hazelcast.log"
GRACEFUL_WAIT=75  # JVM max.wait(60) + 여유 15초

# PID 확인
if [ ! -f "$PID_FILE" ]; then
    echo "[$(date)] PID 파일 없음: 이미 종료됨"
    exit 0
fi

PID=$(cat "$PID_FILE")

# 프로세스 생존 확인
if ! kill -0 "$PID" 2>/dev/null; then
    echo "[$(date)] 프로세스(PID=$PID) 이미 종료됨"
    rm -f "$PID_FILE"
    exit 0
fi

echo "[$(date)] Graceful shutdown 시작 (PID=$PID, max_wait=${GRACEFUL_WAIT}s)"

# SIGTERM 전송 → JVM Shutdown Hook 트리거
kill -TERM "$PID"

# 최대 GRACEFUL_WAIT초 동안 프로세스 종료 대기
ELAPSED=0
while kill -0 "$PID" 2>/dev/null; do
    if [ $ELAPSED -ge $GRACEFUL_WAIT ]; then
        echo "[$(date)] ❌ ${GRACEFUL_WAIT}초 내 정상 종료 실패 (강제 종료 필요)"
        break
    fi
    sleep 1
    ELAPSED=$((ELAPSED + 1))

    # 10초마다 진행 로그
    if [ $((ELAPSED % 10)) -eq 0 ]; then
        echo "[$(date)] Graceful shutdown 진행 중... (${ELAPSED}/${GRACEFUL_WAIT}초)"
    fi
done

# 종료 로그 확인
if tail -100 "$LOG_FILE" 2>/dev/null | grep -q "Hazelcast Shutdown is completed"; then
    echo "[$(date)] ✅ Graceful shutdown 정상 완료 (${ELAPSED}초 소요)"
    rm -f "$PID_FILE"
    exit 0
else
    # 프로세스가 죽었지만 정상 완료 로그 없는 경우
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "[$(date)] ⚠️ 프로세스는 종료되었으나 정상 완료 로그 없음 (로그 확인 필요)"
        rm -f "$PID_FILE"
        exit 1
    fi

    # 프로세스가 아직 살아있으면 SIGKILL
    echo "[$(date)] 🔥 SIGKILL 강제 종료 (데이터 손상 위험!)"
    kill -9 "$PID"
    rm -f "$PID_FILE"
    exit 1  # 배포 시스템에서 실패로 감지
fi
```

### 3-9-2. 수정된 start.sh (일반 환경)

```bash
#!/bin/bash
# start.sh - Hazelcast startup with cluster active verification

set -e

APP_HOME="/opt/app"
PID_FILE="/var/run/hazelcast.pid"
LOG_FILE="/var/log/hazelcast.log"
HZ_REST_PORT=5701
MAX_WAIT=300  # 최대 5분 대기 (Persistence 복원 + validation timeout 고려)

# 이미 실행 중인지 확인
if [ -f "$PID_FILE" ] && kill -0 "$(cat $PID_FILE)" 2>/dev/null; then
    echo "[$(date)] 이미 실행 중 (PID=$(cat $PID_FILE))"
    exit 1
fi

echo "[$(date)] Hazelcast 노드 시작..."

# JVM 기동
cd "$APP_HOME"
nohup java $JAVA_OPTS \
    -Dhazelcast.shutdownhook.policy=GRACEFUL \
    -Dhazelcast.graceful.shutdown.max.wait=60 \
    -jar hazelcast.jar \
    > "$LOG_FILE" 2>&1 &

NEW_PID=$!
echo $NEW_PID > "$PID_FILE"
echo "[$(date)] JVM 기동 (PID=$NEW_PID)"

# 1단계: 프로세스 생존 확인 (최초 5초)
sleep 5
if ! kill -0 "$NEW_PID" 2>/dev/null; then
    echo "[$(date)] ❌ JVM이 기동 직후 종료됨 (로그 확인 필요)"
    tail -30 "$LOG_FILE"
    exit 1
fi

# 2단계: 클러스터 ACTIVE 상태 대기
echo "[$(date)] 클러스터 ACTIVE 상태 대기 중..."
ELAPSED=0
CLUSTER_READY=false

while [ $ELAPSED -lt $MAX_WAIT ]; do
    # 프로세스 여전히 살아있는지 확인
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo "[$(date)] ❌ 기동 중 JVM 비정상 종료"
        tail -50 "$LOG_FILE"
        exit 1
    fi

    # Hazelcast Health REST API 확인 (5701 포트)
    if curl -sf "http://localhost:${HZ_REST_PORT}/hazelcast/health/cluster-state" 2>/dev/null | grep -q "ACTIVE"; then
        echo "[$(date)] ✅ 클러스터 ACTIVE 확인 (${ELAPSED}초 소요)"
        CLUSTER_READY=true
        break
    fi

    # 로그 기반 확인 (curl 사용 불가 시 백업)
    if tail -200 "$LOG_FILE" 2>/dev/null | grep -q "cluster is ready"; then
        echo "[$(date)] ✅ 클러스터 준비 완료 (로그 기반, ${ELAPSED}초 소요)"
        CLUSTER_READY=true
        break
    fi

    # Persistence 복원 실패 감지
    if tail -200 "$LOG_FILE" 2>/dev/null | grep -qE "Persistence.*failed|Recovery failed|Hot Restart.*failed"; then
        echo "[$(date)] ❌ Persistence 복원 실패"
        tail -50 "$LOG_FILE"
        exit 1
    fi

    sleep 5
    ELAPSED=$((ELAPSED + 5))

    # 30초마다 진행 상황 로그
    if [ $((ELAPSED % 30)) -eq 0 ]; then
        echo "[$(date)] 클러스터 준비 대기 중... (${ELAPSED}/${MAX_WAIT}초)"
    fi
done

if [ "$CLUSTER_READY" != "true" ]; then
    echo "[$(date)] ❌ ${MAX_WAIT}초 내 클러스터 ACTIVE 되지 않음"
    tail -50 "$LOG_FILE"
    exit 1
fi

# 3단계: Jet Pipeline 제출 확인 (master 노드인 경우)
sleep 30  # 부트스트랩 완료 여유 시간
if tail -500 "$LOG_FILE" 2>/dev/null | grep -q "Job submitted successfully"; then
    echo "[$(date)] ✅ Jet Pipeline 제출 확인 (master 노드)"
elif tail -500 "$LOG_FILE" 2>/dev/null | grep -qE "not the master|Skipping Jet pipeline"; then
    echo "[$(date)] ✅ Non-master 노드, Pipeline 제출 건너뜀 (정상)"
else
    echo "[$(date)] ⚠️ Jet Pipeline 상태 불명 (수동 확인 필요)"
fi

echo "[$(date)] ✅ 배포 완료"
exit 0
```

**주요 특징:**
- `curl` 기반 REST Health Check (1순위) + 로그 grep (백업)
- 기동 중 JVM 비정상 종료 감지
- Persistence 복원 실패 명시적 감지
- Jet Pipeline 제출 상태 확인

## 3-10. Hazelcast Health REST API 활용

Hazelcast가 제공하는 Health Check endpoint:

```bash
# 클러스터 상태 (ACTIVE, FROZEN, PASSIVE 등)
curl http://localhost:5701/hazelcast/health/cluster-state

# 노드 상태 (NODE_STATE_PASSIVE, NODE_STATE_ACTIVE 등)
curl http://localhost:5701/hazelcast/health/node-state

# 클러스터 안전 여부 (파티션 마이그레이션 완료 여부)
curl http://localhost:5701/hazelcast/health/cluster-safe

# 마이그레이션 진행 중 파티션 수
curl http://localhost:5701/hazelcast/health/migration-queue-size

# 클러스터 전체 멤버 수
curl http://localhost:5701/hazelcast/health/cluster-size
```

**활성화 조건:** `hazelcast.xml` 또는 JVM 옵션에 다음 설정 필요:

```xml
<!-- hazelcast.xml -->
<network>
    <rest-api enabled="true">
        <endpoint-group name="HEALTH_CHECK" enabled="true"/>
    </rest-api>
</network>
```

또는 JVM 옵션:
```
-Dhazelcast.http.healthcheck.enabled=true
```

현재 프로젝트는 `advanced-network`를 사용 중이므로 해당 네트워크 설정에 rest-api 추가 필요.

## 3-11. 배포 시스템 흐름 수정 권장

**현재:**
```
3개 서버 동시 접속 → 동시 stop.sh → 10초 대기 → 동시 start.sh
```

**권장:**
```
Step 1: JAR 배포 (병렬 가능)
    ssh node1 "cp new.jar /opt/app/hazelcast.jar" &
    ssh node2 "cp new.jar /opt/app/hazelcast.jar" &
    ssh node3 "cp new.jar /opt/app/hazelcast.jar" &
    wait

Step 2: 순차 stop (node3 → node2 → node1)
    ssh node3 "./stop.sh"    # 75초까지 대기, graceful 완료 확인
    ssh node2 "./stop.sh"
    ssh node1 "./stop.sh"    # 마지막 (데이터 전체 보유 상태에서 안전 종료)

Step 3: 동시 start (3개 노드)
    ssh node1 "./start.sh" &
    ssh node2 "./start.sh" &
    ssh node3 "./start.sh" &
    wait

    → 각 start.sh가 클러스터 ACTIVE 확인까지 대기
    → 모든 exit 0이면 배포 성공
    → 하나라도 exit 1이면 배포 실패 (롤백 검토)
```

## 3-12. systemd 사용 중인 경우

systemd로 관리하는 경우 unit 파일 수정도 필요:

```ini
# /etc/systemd/system/hazelcast.service
[Unit]
Description=Hazelcast Node
After=network.target

[Service]
Type=simple
User=hazelcast
WorkingDirectory=/opt/app
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/app/hazelcast.jar

# 핵심: graceful shutdown을 위한 타임아웃 설정
TimeoutStopSec=75             # ← 필수: JVM max.wait(60) + 여유
KillSignal=SIGTERM            # ← SIGTERM으로 JVM Shutdown Hook 트리거
SendSIGKILL=yes               # ← TimeoutStopSec 후에만 SIGKILL
KillMode=mixed

Restart=no                    # 자동 재시작 금지 (배포 시스템이 관리)
SuccessExitStatus=143         # SIGTERM에 의한 정상 종료를 성공으로 인정

[Install]
WantedBy=multi-user.target
```

**`TimeoutStopSec=75`가 핵심.** 기본값 90초면 괜찮지만, 일부 배포판/설정에서 30초로 오버라이드되는 경우 문제 발생.

확인 명령:
```bash
systemctl show hazelcast | grep -E "TimeoutStop|KillSignal|SendSIGKILL"
```

## 3-13. 일반 환경 최종 요약

| 항목 | 사용자 설정 평가 | 문제 유무 |
|------|---------------|---------|
| JVM 옵션 (GRACEFUL + 60초) | ✅ 적절 | 없음 |
| 순차 Shutdown 순서 (3→2→1) | ✅ 적절 | 없음 |
| PARTIAL_RECOVERY_MOST_COMPLETE | ✅ 동시 start에 적합 | 없음 |
| 동시 Start 전략 | ✅ 가능 | 없음 (조건부) |
| **현재 stop.sh 그대로 사용** | ❌ | **kill 후 대기 시간 부족으로 graceful 완료 보장 안 됨** |
| **현재 start.sh 그대로 사용** | ❌ | **JVM 기동만 확인, 클러스터 ACTIVE 미확인** |
| systemd 사용 시 TimeoutStopSec | 확인 필요 | 기본 90초면 OK, 30초면 수정 필수 |

## 3-14. 최종 판단

> **stop.sh는 반드시 수정**해야 한다. SIGTERM 후 **최소 75초(60+여유)** 대기하며 프로세스 종료와 완료 로그를 확인해야 한다. 10초 후 SIGKILL을 보내는 방식이라면 JVM에 설정한 `max.wait=60`이 무용지물이다.
>
> **start.sh도 수정 권장**한다. `nohup java -jar ... &`만 실행하고 즉시 반환하면 배포 성공을 오판한다. **Hazelcast REST API의 `/hazelcast/health/cluster-state` 응답이 `ACTIVE`가 될 때까지 대기**하는 로직이 필요하다.
>
> **systemd를 사용한다면 `TimeoutStopSec=75` 이상** 설정도 확인하자. 이 값이 짧으면 systemd가 직접 SIGKILL을 보낸다.
>
> `auto-remove-stale-data=true`와 REST API 활성화 설정 확인도 필수다.

## 3-15. 일반 환경 체크리스트

- [ ] stop.sh에서 SIGTERM 후 **75초 이상** 대기 로직 추가
- [ ] stop.sh에서 **"Hazelcast Shutdown is completed"** 로그 확인
- [ ] start.sh에서 **REST Health API (`cluster-state=ACTIVE`)** 확인
- [ ] start.sh에서 로그 기반 백업 확인 로직 포함
- [ ] start.sh에서 Persistence 복원 실패 명시적 감지
- [ ] start.sh에서 Jet Pipeline 제출 상태 확인
- [ ] systemd 사용 시 `TimeoutStopSec=75` 이상 확인
- [ ] `hazelcast.xml`에 `rest-api` + `HEALTH_CHECK` endpoint-group 활성화
- [ ] 배포 시스템 흐름을 **순차 stop + 동시 start**로 변경
- [ ] `auto-remove-stale-data=true` 설정 확인
