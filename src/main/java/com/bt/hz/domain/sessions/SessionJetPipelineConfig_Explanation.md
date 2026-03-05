# Hazelcast Jet Pipeline: Session Event Stream Processing

이 문서는 `SessionJetPipelineConfig.java`에 구현된 Hazelcast Jet 파이프라인의 각 단계별 동작 원리와 핵심 API(`mapStateful`, `Sinks.mapWithUpdating`) 사용 방식 및 핵심 문제 해결 전략에 대한 자세한 설명을 담고 있습니다.

## 0. Job(파이프라인) 중복 실행 방지
클러스터 모드(다중 노드)로 기동 시, 각 노드가 스프링부트 올라오는 시점에 파이프라인을 개별적으로 제출하면 동일한 작업을 하는 Jet Job이 클러스터에 N개 등록됩니다. (예: 3대 노드 = 3개 Job)
이렇게 되면 하나의 세션 이벤트에 대해 3개의 Job이 각각 처리하여, 결과적으로 **동일 값 업데이트나 집계 건수(Count)가 3배로 부풀려지는 현상**이 발생합니다.
이를 막기 위해 파이프라인 제출 전 `JobConfig`를 통해 사전에 정해진 이름(`SessionJetPipelineJob`)을 부여하고, `jet.getJob()`로 해당 이름의 Job이 이미 있는지 검사하는 **이중 제출 방어 로직**이 도입되어 있습니다.

---

## 1. 1단계: Source (이벤트 수신)
`bt_sessions` 맵에서 발생하는 `EventJournalMapEvent`를 시간 순서대로 읽어들이는 최초 단계입니다. 수신되는 이벤트는 Map 자료구조뿐만 아니라 Hazelcast 내부의 직렬화 포맷인 `HeapData` 형태로 들어올 수도 있습니다.

---

## 2. 2단계: `mapStateful` (부분 데이터 조립 및 로그아웃 판별)
단편적으로 쪼개져서 혹은 난해한 직렬화 형태로 들어오는 웹 세션 이벤트들을 하나의 완성된 상태(`SessionInfo`)로 추출 및 조립합니다.

### 2.1. 내부 데이터 직렬화/역직렬화 추출 (`extractStringValue`)
종종 Hazelcast Web Filter 등으로 생성된 세션 정보가 자바 Map이 아니라 Hazelcast 내부 포맷인 `com.hazelcast.nio.serialization.Data` 인터페이스의 인스턴스(예: `HeapData`)로 들어올 수 있습니다. 이를 파싱하기 위해 리플렉션(Reflection)을 이용해 현재 클러스터 인스턴스의 `SerializationService`를 동적으로 가져와 `toObject()`를 호출한 뒤 안전한 `String`으로 캐스팅하는 전처리 과정을 거칩니다.

### 2.2. TTL (상태 유지 시간)
```java
java.util.concurrent.TimeUnit.HOURS.toMillis(11)
```
* **역할**: Jet 엔진이 특정 세션(`sessionId`)의 메모리 상태를 얼마나 오랫동안 보관할지 결정합니다. (`bt_sessions` 맵의 TTL 10시간보다 넉넉하게 설정)

### 2.3. 매핑 함수 조립 및 조기 방출 차단
* **로그아웃/만료 감지**: 이벤트 타입이 `REMOVED` 거나 `EXPIRED`인 경우 `isLogout=true`인 신호를 방출합니다.
* **완성 상태(Complete) 검증**: 추출된 `LOGIN`, `LOGIN_TYPE`, `USER_INFO` 세 가지 정보가 모두 수집되어야만 완성된 상태(`isComplete() == true`)로 판별합니다.
* **중복 카운트 방지 (`isNewLogin`)**: `sessionId`별로 메모리에 유지되는 `SessionInfo`의 이전 상태를 검사합니다. 방금 막 처음으로 필수 3가지 값이 모두 모여 완성되었다면, `isNewLogin = true` 플래그를 세팅하여 단 1회만 하위 스트림으로 내보냅니다. (이 플래그 덕분에 6단계에서 이벤트가 여러 번 나뉘어 들어와도 카운트가 `1`만 증가하게 됩니다)
* **조기 방출 차단 (최적화)**: `state.isComplete()`가 `false`라면 `null`을 리턴하여 이벤트가 하위 스트림으로 흘러가는 것을 원천 차단합니다.

---

## 3. `wrapperStream` 생성 (JSON 파싱 및 시간 포맷 변환)
```java
StreamStage<SessionEventWrapper> wrapperStream = parsedStream.mapUsingService(mapperService, (mapper, dto) -> { ... })
```
* **역할**: 클러스터 노드마다 1개씩만 `ObjectMapper`를 재사용하도록 Jet의 `ServiceFactory`(`mapUsingService`)를 이용합니다. 매번 JSON 파서 객체를 생성하는 오버헤드를 막습니다.
* **시간 포맷팅**: `SessionDto`와 향후 집계될 이력 테이블을 위해 넘겨받은 원본 JSON의 날짜 문자열(예: `yyyyMMddHHmmss` 포맷)을 각각 용도에 맞게 재가공하기 쉽도록 String 값으로 파싱해둡니다.

---

## 4. 4단계~6단계: `Sinks.mapWithUpdating` (분기 처리형 대상 맵 반영)
2단계에서 판별된 `isLogout` 여부나 `isNewLogin` 플래그를 바탕으로 분기 처리하여 집계 맵들을 업데이트합니다.

### 4.1. 3단계/4단계: `M_SYSSE001I`, `M_SYSSE002I` (캐싱 목적 맵)
* **목적**: 파싱된 `SessionDto` 및 `SessionEventTransport` 추출 정보들을 단순히 캐싱하여 SQL 조회가 가능하게 합니다.
* **동작**: `isLogout` 신호를 받으면 해당 노드를 맵에서 삭제(`null` 리턴)하고, 정상 로그인이면 통째로 맵의 값으로 저장(갱신)합니다.

### 4.2. 5단계: `M_SYSSE014I` (이력 POJO 업데이트)
* **목적**: 이력 관리용 POJO이며 특수하게 **로그아웃 시간(`logoutAt`)** 만료 추적을 수행합니다.
* **동작 (로그인)**: `SessionDto`와 전파된 `loginType`을 조합하여 새 객체를 넣습니다. 날짜 문자열 파싱 등을 수행합니다. (`logoutAt`은 일단 비워둡니다)
* **동작 (로그아웃)**: 삭제 신호(`isLogout`)가 오면 맵을 지우는 대신, 맵에서 버려져야 할 이전 세션(`oldValue`)을 꺼내온 뒤, **`logoutAt` 필드를 현재 UTC 시간의 `yyyyMMddHHmmss` 포맷 문자열로 업데이트한 후 덮어씁니다.** 이로 인해 로그인/로그아웃 이력을 SQL로 온전히 조회할 수 있게 됩니다.

### 4.3. 6단계: `M_SYSSE015I` (시간대별 로그인 카운트 집계)
* **목적**: 사용자 로그인 시각 중 `yyyyMMdd`와 `HH`를 키워드로 삼아 시간대별 로그인 횟수를 집계합니다.
* **동작 원리 (원자적 업데이트)**: Hazelcast는 분산 락 프리(Lock-free) 환경에서 내부적인 EntryProcessor를 사용하여 스레드 안전하게 카운트 값을 변경합니다.
* **동작 (중복 카운팅 핵심 제어)**: 2단계 상태 머신에서 **최초로 해당 세션이 완성된 찰나에만 켜서 보낸 `isNewLogin == true` 플래그를 엄격히 검사**합니다. ഈ 플래그가 없거나 이미 로그아웃된 세션이 다시 들어오는 경우 등엔 완전히 무시(`oldValue` 그대로 리턴)하고, 오직 해당 플래그가 켜진 상태에서만 기존 카운트를 `+1` 시킵니다.
    > 💡 **참고**: 0단계의 JobName 설정 방어로 Job 복제로 인한 중복이 차단되었고, 2단계의 상태 머신 설계로 세션의 복수 이벤트에 대한 중복이 차단되었기 때문에, 이 단계에서는 안심하고 카운트를 증가시킬 수 있습니다.
