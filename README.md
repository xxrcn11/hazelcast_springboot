# Hazelcast Jet Pipeline: Session Event Stream Processing

이 문서는 `SessionJetPipelineConfig.java`에 구현된 Hazelcast Jet 파이프라인의 각 단계별 동작 원리와 핵심 API(`mapStateful`, `Sinks.mapWithUpdating`) 사용 방식 및 핵심 문제 해결 전략에 대한 자세한 설명을 담고 있습니다.

## 0. Job(파이프라인) 중복 실행 방지
클러스터 모드(다중 노드)로 기동 시, 각 노드가 스프링부트 올라오는 시점에 파이프라인을 개별적으로 제출하면 동일한 작업을 하는 Jet Job이 클러스터에 N개 등록됩니다. (예: 3대 노드 = 3개 Job)
이렇게 되면 하나의 세션 이벤트에 대해 3개의 Job이 각각 처리하여, 결과적으로 **동일 값 업데이트나 집계 건수(Count)가 3배로 부풀려지는 현상**이 발생합니다.
이를 막기 위해 파이프라인 제출 전 `JobConfig`를 통해 사전에 정해진 이름(`SessionJetPipelineJob`)을 부여하고, `jet.getJob()`로 해당 이름의 Job이 이미 있는지 검사하는 **이중 제출 방어 로직**이 도입되어 있습니다.

---

## 1. 1단계: Source (이벤트 수신)
`bt_sessions` 맵에서 발생하는 `EventJournalMapEvent`를 시간 순서대로 읽어들이는 최초 단계입니다. 수신되는 이벤트는 Map 자료구조뿐만 아니라 Hazelcast 내부의 직렬화 포맷인 `HeapData` 형태로 들어올 수도 있으며, 파이프라인 최적화 로깅 원칙에 따라 DEBUG 모드에서만 상태를 출력합니다.

---

## 2. 1.5단계: 역직렬화 및 기초 데이터 추출 (`mapUsingService`)
종종 Hazelcast Web Filter 등으로 생성된 세션 정보가 자바 Map이 아니라 Hazelcast 내부 포맷인 `SessionState`나 `com.hazelcast.nio.serialization.Data` 형태(예: `HeapData`)로 들어올 수 있습니다.
이 과정의 부하를 줄이기 위해, 파이프라인 외곽에 미리 정의해둔 **`ServiceFactory`(`ssFactory`)를 통해** 클러스터 노드가 공유하는 `SerializationService`를 곧바로 받아와 활용합니다. 과거에는 리플렉션을 통해 동적으로 서비스 객체를 찾았으나, 현재는 `HazelcastInstance`를 직접 캐스팅하여 획득함으로써 구조가 대폭 단순화 되었습니다.
이 단계에서 추출된 속성(`LOGIN`, `LOGIN_TYPE`, `USER_INFO`)들은 `ExtractedSessionEvent`라는 DTO로 포장되어 다음 단계로 전달됩니다.

---

## 3. 2단계: `mapStateful` (부분 데이터 조립 및 로그아웃 판별)
단편적으로 쪼개져서 수신되는 1.5단계의 역직렬화 결과들을 세션 ID 기준으로 묶어(groupingKey) 하나의 완성된 상태(`SessionInfo`)로 추출 및 조립합니다. 기존에는 이 단계에서 역직렬화까지 수행했지만 현재는 상태 관리 및 응집에만 집중합니다.

### 3.1. TTL (상태 유지 시간)
```java
java.util.concurrent.TimeUnit.HOURS.toMillis(11)
```
* **역할**: Jet 엔진이 특정 세션(`sessionId`)의 메모리 상태를 얼마나 오랫동안 보관할지 결정합니다. (`bt_sessions` 맵의 TTL 10시간보다 넉넉하게 설정)

### 3.2. 매핑 함수 조립 및 조기 방출 차단
* **로그아웃/만료 감지**: 전송된 이벤트에 `isLogout=true` 신호가 켜져 있으면 로그아웃으로 판별하여 즉시 다음으로 넘깁니다.
* **상태 업데이트**: 수신한 부분 이벤트의 정보들을 통해 기존 상태에 `update`를 호출합니다.
* **완성 상태(Complete) 검증**: 추출된 `LOGIN`, `LOGIN_TYPE`, `USER_INFO` 세 가지 정보가 모두 수집되어야만 완성된 상태(`isComplete() == true`)로 판별합니다.
* **중복 카운트 방지 (`isNewLogin`)**: `sessionId`별로 메모리에 유지되는 `SessionInfo`의 이전 상태를 검사합니다. 방금 막 처음으로 필수 3가지 값이 모두 모여 완성되었다면, `isNewLogin = true` 플래그를 세팅하여 단 1회만 하위 스트림으로 내보냅니다. (이 플래그 덕분에 하위 집계 단계에서 이벤트가 여러 번 나뉘어 들어와도 카운트가 중복 상승하지 않습니다)
* **조기 방출 차단 (최적화)**: `sessionInfo.isComplete()`가 `false`라면 `null`을 리턴하여, 미완성 이벤트가 하위 스트림으로 흘러가는 것을 원천 차단합니다.

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
* **동작 (중복 카운팅 핵심 제어)**: 2단계 상태 머신에서 **최초로 해당 세션이 완성된 찰나에만 켜서 보낸 `isNewLogin == true` 플래그를 엄격히 검사**합니다. 이 플래그가 없거나 이미 로그아웃된 세션이 다시 들어오는 경우 등엔 완전히 무시(`oldValue` 그대로 리턴)하고, 오직 해당 플래그가 켜진 상태에서만 기존 카운트를 `+1` 시킵니다.
    > 💡 **참고**: 0단계의 JobName 설정 방어로 Job 복제로 인한 중복이 차단되었고, 2단계의 상태 머신 설계로 세션의 복수 이벤트에 대한 중복이 차단되었기 때문에, 이 단계에서는 안심하고 카운트를 증가시킬 수 있습니다.

---

## 5. 업데이트(UPDATE) 이벤트의 하위 맵 전파 메커니즘
`bt_sessions`에 이미 존재하는 세션의 내부 정보(예: `USER_INFO` 내의 권한(Role) 변경 등)가 수정되어 **`UPDATED` 이벤트**가 발생하더라도, 하위 캐싱 맵(`M_SYSSE001I`, `M_SYSSE002I`, `M_SYSSE014I`)에는 최신 값으로 정확하게 덮어쓰기(업데이트) 됩니다.

### 5.1. 동작 원리
1. **이벤트 감지**: 1단계 Source(`EventJournalMapEvent`)는 `ADDED` 이벤트 뿐만 아니라 `UPDATED` 이벤트도 모두 캡처하여 스트림으로 흘려보냅니다.
2. **상태 갱신**: 2단계 `mapStateful` 연산자는 `sessionId`를 키로 하여 기존에 쥐고 있던 메모리 객체(`SessionInfo`)를 꺼낸 뒤, 추출된 최신 값으로 업데이트(`state.update()`)합니다.
   > ⚠️ 이때 이미 이전에 1회 완성되었던 세션이므로 `isNewLogin = false`로 마킹되어 하위로 내려갑니다.
3. **최신 값 덮어쓰기**:
   - **3단계 (`M_SYSSE001I`)**: 기존 값(`oldValue`)을 무시하고, `wrapper`에서 꺼낸 최신 정보들로 새 POJO를 만들어 반환하므로 최신 상태로 덮어써집니다.
   - **4단계 (`M_SYSSE002I`)**: 최신 값으로 파싱된 `SessionDto` 객체 자체를 통째로 반환하여 완전히 교체합니다.
   - **5단계 (`M_SYSSE014I`)**: 마찬가지로 로그인되어 있는 상태(업데이트 포함)라면 변경된 최신 `SessionDto`를 씌운 새 POJO를 반환하여 최신 상태로 반영합니다.
4. **카운트 중복 방지**: 6단계 `M_SYSSE015I`는 `isNewLogin == false`인 이벤트에 대해서는 집계하지 않고 기존 카운트(`oldValue`)를 그대로 유지하므로, 단순 정보 업데이트 이벤트로 인해 카운트가 중복 상승하는 부작용이 없습니다.

---

# Transaction 관리

`BCB001IMapStore`는 Hazelcast가 **리플렉션을 통해 기본 생성자로 직접 인스턴스화**하기 때문에 Spring Bean이 아닙니다. 따라서 `@Transactional` 어노테이션이 동작하지 않습니다. 대신 `SpringContextHolder`를 경유하여 `PlatformTransactionManager`를 직접 꺼내 `TransactionTemplate`으로 트랜잭션을 수동 제어합니다.

## 1. `PlatformTransactionManager`는 누가 등록하는가?

프로젝트 코드 어디에도 `@Bean`으로 `PlatformTransactionManager`를 직접 선언한 곳은 없습니다. **Spring Boot Auto-configuration**이 자동으로 처리합니다.

`build.gradle`의 MyBatis 스타터 의존성이 내부적으로 `spring-boot-starter-jdbc`를 포함합니다:

```gradle
implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4'
```

`application.yml`에 `datasource` 설정이 존재하면, Spring Boot는 `DataSourceTransactionManagerAutoConfiguration`을 활성화하여 `DataSourceTransactionManager`(`PlatformTransactionManager` 구현체) Bean을 자동 생성하고 `ApplicationContext`에 등록합니다.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/testdb?...
    username: testuser
    password: testpassword
    driver-class-name: com.mysql.cj.jdbc.Driver
```

## 2. `SpringContextHolder`에 `ApplicationContext`가 주입되는 과정

`SpringContextHolder`는 `ApplicationContextAware`를 구현하고 `@Component`로 선언되어 있습니다:

```java
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;  // Spring이 앱 기동 시 자동 호출
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (context == null) return null;
        return context.getBean(beanClass);
    }
}
```

Spring 컨테이너는 `SpringContextHolder` Bean을 생성한 직후, `ApplicationContextAware` 인터페이스를 감지하고 **`setApplicationContext()`를 자동 호출**하여 `context` static 필드에 전체 `ApplicationContext`를 주입합니다.

## 3. `BCB001IMapStore`에서 Bean을 꺼내는 과정

`BCB001IMapStore`는 `txWrite()` / `txRead()` 호출 시점마다 `SpringContextHolder`를 경유하여 Bean을 수동으로 조회합니다:

```java
// 쓰기 트랜잭션 (REQUIRED 전파)
private TransactionTemplate txWrite() {
    return new TransactionTemplate(
            SpringContextHolder.getBean(PlatformTransactionManager.class));
}

// 읽기 전용 트랜잭션
private TransactionTemplate txRead() {
    TransactionTemplate tt = new TransactionTemplate(
            SpringContextHolder.getBean(PlatformTransactionManager.class));
    tt.setReadOnly(true);
    return tt;
}
```

## 4. 전체 흐름

```
[Spring Boot 기동]
        │
        ▼
DataSourceTransactionManagerAutoConfiguration 활성화
(mybatis-spring-boot-starter → spring-boot-starter-jdbc 포함)
        │ application.yml의 datasource 설정 감지
        ▼
DataSourceTransactionManager Bean 생성 → ApplicationContext에 등록
        │
        │ ApplicationContextAware 인터페이스 처리
        ▼
SpringContextHolder.setApplicationContext(ctx) 자동 호출
        │ static context 필드에 ctx 저장
        ▼
[Hazelcast가 BCB001IMapStore를 리플렉션으로 생성]
        │
        ▼
txWrite() / txRead() 호출 시
SpringContextHolder.getBean(PlatformTransactionManager.class)
        │
        ▼
DataSourceTransactionManager 반환 → TransactionTemplate 생성 후 실행
```

| 역할 | 담당 |
|---|---|
| `PlatformTransactionManager` **Bean 생성** | Spring Boot Auto-config (`DataSourceTransactionManagerAutoConfiguration`) |
| 등록 트리거 | `application.yml`의 `datasource` 설정 |
| `SpringContextHolder`에 context 주입 | Spring 컨테이너가 `ApplicationContextAware.setApplicationContext()` 자동 호출 |
| `PlatformTransactionManager` 조회 | `BCB001IMapStore.txWrite()/txRead()` → `SpringContextHolder.getBean()` |

---

# (부록) SessionState 직접 캐스팅 및 데이터 복원 방법

만약 `bt_sessions` 맵에서 꺼낸 `event.getNewValue()`를 리플렉션 없이 강제로 `com.hazelcast.web.SessionState` 타입으로 캐스팅했을 때, 내부의 직렬화된 데이터 속성(`USER_INFO` 등)을 원래 값(객체나 문자열)으로 복원하는 방법은 크게 세 가지로 요약할 수 있습니다.

## 방법 1: `SerializationService`를 이용한 정석 복원 (현재 코드 유사 방식 / 권장)
* **원리**: `SessionState`에서 속성을 꺼낸 값이 Hazelcast 내부 포맷(`Data` 또는 `HeapData`)일 경우, 인스턴스의 `SerializationService.toObject(data)`를 호출하여 원본 객체로 역직렬화합니다.
* **장단점**: 어떤 직렬화 방식(Portable, Kryo 등)으로 압축되었든 Hazelcast가 알아서 정확히 복원해주므로 가장 안정적이고 호환성이 높습니다. 단, `HazelcastInstance` 객체에 접근해야 하므로 익명 함수 내 클로저 캡처링 등의 선행 작업이 필요할 수 있습니다.

## 방법 2: Spring Session 기본 Serializer 직접 호출
* **원리**: Spring 환경(`spring-session-hazelcast`)이라면, 속성 값이 바이트 배열 등으로 들어있을 때 Spring 단에 등록된 커스텀 Deserializer(예: `JdkSerializationRedisSerializer`, `GenericJackson2JsonRedisSerializer`)를 명시적으로 가져와 `deserialize(bytes)`를 호출합니다.
* **장단점**: Spring Session이 자체 커스텀한 직렬화 전략과 100% 동일하게 복원 가능합니다. 하지만 Spring 및 직렬화 설정과 강하게 결합되어 환경이 바뀌면 파이프라인 코드도 대대적으로 수정해야 하는 위험이 있습니다.

## 방법 3: 강제 문자열 변환 후 JSON 구조 해석 (ObjectMapper Tree Parsing)
* **원리**: 꺼낸 값을 강제로 `String.valueOf()`나 UTF-8 문자열로 변환한 뒤, 그 안에 JSON 형태의 흔적이 보인다면 `ObjectMapper.readTree(json)` 등을 이용해 속성(`userId` 등)을 수동으로 파싱합니다.
* **장단점**: 프레임워크나 직렬화 엔진에 대한 이해 없이도 무식하지만 직관적으로 데이터를 우회 추출할 수 있습니다. 하지만 특정 직렬화 전략(바이너리나 압축 포맷)에서는 문자열이 완전히 깨져버리므로 아예 사용할 수 없는 치명적인 한계가 존재합니다.

따라서 파이프라인 노드 환경에서는 **방법 1(`SerializationService.toObject()`)**을 활용하는 것이 데이터 유실을 막는 가장 안전한 방법입니다.

---

# (부록) Hazelcast Jet 파이프라인 환경에서의 로깅 베스트 프랙티스

분산 시스템 및 Spring Boot 환경에서 파이프라인 요소에 로그를 남기는 방식인 `System.out.println`, `static final Logger`, `@Slf4j` 세 가지를 비교 분석하고, Jet 파이프라인 특성에 맞는 최적의 로깅 방식을 안내합니다.

## 로깅 방식 비교

1. **`System.out.println` (표준 출력)**
    * **과거 코드 (`peek` 블럭 내부 등)에서 사용되던 방식입니다.**
    * **장단점**: 람다 내부에서 외부 객체를 참조하지 않아 직렬화 에러를 유발하지 않고 즉시 결과를 볼 수 있는 장점이 있습니다. 하지만 모든 스레드가 거쳐야 하는 병목을 발생시켜 **치명적인 성능 저하**를 유발하며, 로그 레벨(INFO/DEBUG) 통제가 불가능합니다. 또한 분산 환경에서는 어느 노드의 콘솔에 출력될지 알 수 없어 파편화됩니다.

2. **`static final Logger log = LoggerFactory.getLogger(...)` (표준 SLF4J)**
    * **장단점**: 비동기 로깅으로 성능 저하가 적고 로그 레벨 제어가 가능합니다. 하지만 Jet 파이프라인이 클러스터 노드로 람다를 직렬화하여 전송할 때, 람다 내부에서 클래스 레벨의 `log` 인스턴스를 참조하면 **`NotSerializableException`** 에러가 발생할 위험이 있습니다.

3. **`@Slf4j` (Lombok 어노테이션)**
    * **현재 스프링 빈 초기화 영역(`initPipeline()`) 등에서 널리 쓰이는 방식입니다.**
    * **장단점**: 로컬(스프링 컨텍스트)에서 수행되는 클래스 밖 영역에서는 완벽하지만, 분산 노드로 배포되는 분산 익명 람다 내부에서 `log.info()` 등을 호출하게 되면 2번과 완전히 동일한 직렬화 문제를 일으킵니다.

## 결론 및 적용 가이드

Jet 파이프라인을 작성할 때는 로깅 발생 위치(로컬 vs 분산 워커)를 명확히 나누어 접근해야 합니다.

* **파이프라인 세팅 & 외곽 메서드 (`initPipeline` 등)**
   * 빈 초기화 영역이므로 `@Slf4j`를 적극 사용하여 `log.info(...)` 형태로 작성합니다.
* **파이프라인 실행 람다 내부 (`peek`, `map`, `filter` 블럭 안)**
   * 파이프라인 성능 저하와 JVM 직렬화 에러를 모두 피하기 위해서는, **해당 워커 노드의 로컬 인스턴스 안에서만 지연 초기화(Lazy init)** 하여 Logger를 직접 획득하는 방식을 사용해야 합니다.

**개선 및 적용된 예시 (파이프라인 내부 `peek` 단계)**
```java
// 개선된 Jet 파이프라인용 로깅 방식 (직렬화 우회 및 성능 최적화)
.peek(e -> {
    org.slf4j.Logger l = org.slf4j.LoggerFactory.getLogger(SessionJetPipelineConfig.class);
    if (l.isDebugEnabled()) {
        l.debug("[SessionJetPipeline] Step 1 - Key: {}, Type: {}, Value: {}",
                     e.getKey(), e.getType(), e.getNewValue());
    }
    return null; // Jet 특유의 콘솔 강제 중복 출력 방지
})
```
*실운영 환경에서는 이와 같이 `isDebugEnabled()` 판단과 `log.debug` 조합에 더해(Jet 중복 방지 `null` 리턴 포함) 파이프라인 처리 속도의 저하를 방지해야 합니다.*

---

# (부록) BCB001IMapStore: 기동 시 DB → Hazelcast 로딩 메커니즘

Hazelcast 클러스터가 기동될 때 `BCB001I` 맵에 연결된 `BCB001IMapStore`를 통해 DB 데이터를 메모리에 적재하는 흐름과 배치(batch) 관련 설정을 정리합니다.

## 1. 기동 시 로딩 순서

Hazelcast는 `MapStore`가 붙은 맵을 초기화할 때 아래 순서로 두 메서드를 **자동 호출**합니다. 개발자가 직접 호출할 필요는 없습니다.

```
1. loadAllKeys()   → DB에서 전체 키 목록 조회 (항상 1번만 호출)
          │
          ▼
   [key1, key2, ..., keyN]  (파티션 단위로 분배)
          │
          ▼
2. loadAll(keys)   → 키 묶음을 받아 실제 데이터(Map<String, BCB001I>) 반환
```

| 메서드 | 호출 주체 | 호출 횟수 |
|--------|-----------|-----------|
| `loadAllKeys()` | Hazelcast 클러스터 마스터 1개 노드 | **항상 1번** |
| `loadAll(keys)` | 각 파티션 오너 노드 | 파티션 수 / 배치 크기에 따라 여러 번 |

## 2. `loadAll()`이 1건씩 호출되는 현상 원인

`loadAll()`에 키가 1개씩 넘어오는 경우는 두 가지 원인이 있습니다.

### 원인 1: `hazelcast.map.load.chunk.size` 미설정 (Hazelcast 5.x)

Hazelcast 5.x에서는 XML `<map-store>` 엘리먼트에 `load-batch-size` 항목이 **존재하지 않습니다.** 대신 시스템 프로퍼티로 설정합니다.

**기본값**: `1000`

**설정 방법 (hazelcast.xml properties 섹션에 추가):**
```xml
<properties>
    <property name="hazelcast.socket.bind.any">true</property>
    <property name="hazelcast.map.load.chunk.size">1000</property>
</properties>
```

**또는 JVM 옵션으로 지정:**
```
-Dhazelcast.map.load.chunk.size=1000
```

### 원인 2: 파티션 분배 로직

Hazelcast는 `loadAllKeys()`로 받은 키 목록을 **파티션 오너 노드에게 분배**한 뒤, 각 노드가 자신이 담당하는 파티션의 키에 대해 별도로 `loadAll()`을 호출합니다. 기본 파티션 수가 271개이므로, 데이터 건수가 적으면 파티션당 키가 1~2개 수준으로 잘게 나뉘어 `loadAll()`이 소량씩 여러 번 호출될 수 있습니다.

## 3. `loadAllKeys()` vs `loadAll()` 배치 설정 영향 비교

| 메서드 | `hazelcast.map.load.chunk.size` 영향 |
|--------|--------------------------------------|
| `loadAllKeys()` | ❌ 없음 — 항상 1번 호출 |
| `loadAll(keys)` | ✅ 있음 — chunk 크기만큼 묶어서 호출 |
