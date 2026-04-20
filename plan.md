# Hazelcast - Application 분리 계획서

## 1. 현재 구조 분석

### 현재 아키텍처: Embedded (일체형)

```
┌─────────────────────────────────────────────────┐
│           Hazelcast Docker Container             │
│                                                  │
│  hazelcast/hazelcast:5.5.0 Base Image            │
│  ┌────────────────────────────────────────────┐  │
│  │  /opt/hazelcast/lib/                       │  │
│  │  ├── hazelcast-springboot-0.0.1.jar        │  │  ← 애플리케이션 JAR
│  │  ├── spring-boot-*.jar                     │  │  ← Spring Boot 의존성
│  │  ├── mybatis-*.jar                         │  │  ← MyBatis 의존성
│  │  ├── mysql-connector-j-*.jar               │  │  ← DB 드라이버
│  │  └── jackson-*.jar                         │  │  ← JSON 처리
│  └────────────────────────────────────────────┘  │
│                                                  │
│  기동 순서:                                       │
│  1. Hazelcast 시작 (HazelcastApplication.main)   │
│  2. LifecycleListener → Spring Context 초기화     │
│  3. 클러스터 동기화 → MapStore/Jet/스케줄러 초기화    │
└─────────────────────────────────────────────────┘
```

### 핵심 결합 포인트 (Coupling Points)

| 구성요소 | 실행 위치 | Spring 의존 | Hazelcast 서버 필수 |
|---------|----------|-----------|-------------------|
| `SpringBootStrapListener` | Hazelcast 서버 | O (SpringApplicationBuilder) | O (LifecycleListener) |
| `BCB001IMapStore` | Hazelcast 서버 | O (MyBatis, TransactionTemplate) | O (MapStore 인터페이스) |
| `SessionJetPipelineConfig` | Hazelcast 서버 | O (@Configuration, HazelcastInstance 주입) | O (Jet Pipeline) |
| `MSYSSE015IScheduler` | Hazelcast 서버 | X | O (IScheduledExecutorService) |
| `MSYSSE015IEntryProcessor` | Hazelcast 서버 | X | O (EntryProcessor) |
| `ServerDataSerializableFactory` | Hazelcast 서버 | X | O (직렬화) |
| Data Models (BCB001I 등) | Hazelcast 서버 | X | O (IdentifiedDataSerializable) |
| `MappingSqlRunner` | Hazelcast 서버 | O (Spring Component) | O (Hazelcast SQL) |
| `ClusterReadinessCoordinator` | Hazelcast 서버 | O (ApplicationRunner) | O (IMap) |
| `MapStoreLoaderRunner` | Hazelcast 서버 | O (Spring Component) | O (IMap.loadAll) |

---

## 2. 목표 아키텍처: Hazelcast 클러스터 + Client Application 분리

```
┌─────────────────────────────┐     ┌─────────────────────────────┐
│   Hazelcast Cluster (서버)    │     │   Spring Boot App (클라이언트)  │
│                             │     │                             │
│  hazelcast:5.5.0 + 서버JAR   │     │  Spring Boot 3.4.3          │
│                             │     │                             │
│  포함:                       │ 5701│  포함:                       │
│  ├ Data Models (공통)        │◄────┤  ├ Data Models (공통)        │
│  ├ SerializableFactory      │     │  ├ REST/비즈니스 로직          │
│  ├ MapStore (DB 연동)        │     │  ├ Hazelcast Client 설정     │
│  ├ EntryProcessor           │     │  ├ Jet Pipeline 제출(선택적)  │
│  ├ Jet Pipeline 정의         │     │  └ MyBatis/DB 접근 (자체)    │
│  └ Scheduler                │     │                             │
│                             │     │  독립적 배포/재시작 가능!       │
│  ※ 재시작 불필요              │     │                             │
└─────────────────────────────┘     └─────────────────────────────┘
         │                                      │
         │  MapStore를 통한 DB 접근               │  직접 DB 접근
         ▼                                      ▼
    ┌──────────┐                          ┌──────────┐
    │  MySQL   │ ◄────── 동일 DB ────────► │  MySQL   │
    └──────────┘                          └──────────┘
```

---

## 3. 멀티 모듈 프로젝트 구조

```
hazelcast-project/                          ← 루트 프로젝트 (Gradle multi-module)
├── settings.gradle                         ← include 'common', 'server', 'app'
├── build.gradle                            ← 공통 플러그인/버전 관리
│
├── common/                                 ← 공유 모듈 (서버 + 클라이언트 모두 사용)
│   ├── build.gradle
│   └── src/main/java/com/bt/hz/common/
│       ├── models/
│       │   ├── BCB001I.java
│       │   ├── SessionDto.java
│       │   ├── SYSSE001I.java
│       │   ├── SYSSE014I.java
│       │   └── SYSSE015I.java
│       └── serialization/
│           └── ServerDataSerializableFactory.java
│
├── server/                                 ← Hazelcast 서버 모듈
│   ├── build.gradle
│   ├── Dockerfile
│   ├── hazelcast.xml
│   └── src/main/java/com/bt/hz/server/
│       ├── mapstore/
│       │   └── BCB001IMapStore.java        ← DB 연동 (독자적 JDBC 또는 경량 DI)
│       ├── entryprocessor/
│       │   └── MSYSSE015IEntryProcessor.java
│       ├── pipeline/
│       │   └── SessionJetPipelineConfig.java
│       ├── scheduler/
│       │   └── MSYSSE015IScheduler.java
│       └── bootstrap/
│           ├── ServerBootstrapListener.java ← Spring 없이 Hazelcast만 초기화
│           ├── MappingSqlRunner.java
│           └── MapStoreLoaderRunner.java
│
├── app/                                    ← Spring Boot 클라이언트 모듈
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/main/java/com/bt/hz/app/
│       ├── HazelcastClientApplication.java ← Spring Boot 메인
│       ├── config/
│       │   └── HazelcastClientConfig.java  ← ClientConfig 설정
│       ├── domain/
│       │   └── sessions/
│       │       ├── SessionService.java     ← 비즈니스 로직
│       │       └── SessionController.java  ← REST API (필요 시)
│       └── mapper/
│           └── BCB001IMapper.java          ← MyBatis (앱 자체 DB 접근)
│
└── docker-compose.yml                      ← 전체 서비스 구성
```

---

## 4. 단계별 수정 계획

### 4.1단계: Gradle 멀티 모듈 프로젝트 구성

**작업 내용:**
1. 루트 `settings.gradle` 생성
   ```groovy
   rootProject.name = 'hazelcast-project'
   include 'common', 'server', 'app'
   ```

2. 루트 `build.gradle` 수정 - 공통 설정을 `subprojects` 블록으로 이동
   ```groovy
   subprojects {
       apply plugin: 'java'
       group = 'com.bt'
       version = '0.0.1-SNAPSHOT'
       java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
       repositories { mavenCentral() }
   }
   ```

3. 각 모듈별 `build.gradle` 작성 (의존성 분리)

**영향 범위:** 빌드 구성만 변경, 코드 변경 없음

---

### 4.2단계: common 모듈 분리

**이동 대상 파일:**
- `com.bt.hz.domain.sessions.models.BCB001I` → `com.bt.hz.common.models.BCB001I`
- `com.bt.hz.domain.sessions.models.SessionDto` → `com.bt.hz.common.models.SessionDto`
- `com.bt.hz.domain.sessions.models.SYSSE001I` → `com.bt.hz.common.models.SYSSE001I`
- `com.bt.hz.domain.sessions.models.SYSSE014I` → `com.bt.hz.common.models.SYSSE014I`
- `com.bt.hz.domain.sessions.models.SYSSE015I` → `com.bt.hz.common.models.SYSSE015I`
- `com.bt.hz.config.ServerDataSerializableFactory` → `com.bt.hz.common.serialization.ServerDataSerializableFactory`

**common/build.gradle:**
```groovy
dependencies {
    implementation "com.hazelcast:hazelcast:5.5.0"  // IdentifiedDataSerializable 인터페이스 필요
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

**수정 사항:**
- 모든 모델 클래스의 package 선언 변경
- `ServerDataSerializableFactory`의 import 경로 변경
- `hazelcast.xml`의 `data-serializable-factory` 클래스명 변경:
  ```xml
  <data-serializable-factory factory-id="1001">
      com.bt.hz.common.serialization.ServerDataSerializableFactory
  </data-serializable-factory>
  ```

**주의 사항:**
- `IdentifiedDataSerializable`의 `getFactoryId()`, `getClassId()` 값은 절대 변경하지 말 것
- 기존 Hazelcast 맵에 저장된 데이터와의 직렬화 호환성 유지 필수

---

### 4.3단계: server 모듈 분리 - Hazelcast 서버 전용 코드 이동

**이동 대상 파일:**
- `BCB001IMapStore` → `com.bt.hz.server.mapstore.BCB001IMapStore`
- `MSYSSE015IEntryProcessor` → `com.bt.hz.server.entryprocessor.MSYSSE015IEntryProcessor`
- `SessionJetPipelineConfig` → `com.bt.hz.server.pipeline.SessionJetPipelineConfig`
- `MSYSSE015IScheduler` → `com.bt.hz.server.scheduler.MSYSSE015IScheduler`
- `MappingSQLs` → `com.bt.hz.server.sql.MappingSQLs`
- `MappingSqlRunner` → `com.bt.hz.server.bootstrap.MappingSqlRunner`
- `MapStoreLoaderRunner` → `com.bt.hz.server.bootstrap.MapStoreLoaderRunner`

**server/build.gradle:**
```groovy
dependencies {
    implementation project(':common')
    implementation "com.hazelcast:hazelcast:5.5.0"
    
    // MapStore의 DB 접근을 위한 최소 의존성
    implementation 'com.mysql:mysql-connector-j:8.3.0'
    
    // JSON 파싱 (Jet Pipeline용)
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
}
```

---

### 4.4단계: BCB001IMapStore에서 Spring 의존성 제거 (핵심 변경)

**현재 문제점:**
- `BCB001IMapStore`가 `SpringContextHolder`를 통해 MyBatis Mapper와 TransactionManager를 가져옴
- Hazelcast 서버에서 Spring Context 없이 동작해야 함

**해결 방안 A: 직접 JDBC 사용 (권장)**

```java
// server/src/main/java/com/bt/hz/server/mapstore/BCB001IMapStore.java
public class BCB001IMapStore implements MapStore<String, BCB001I>, MapStoreFactory<String, BCB001I> {

    private DataSource dataSource;

    @Override
    public void init(HazelcastInstance hazelcastInstance, Properties properties, String mapName) {
        // hazelcast.xml의 <properties>에서 JDBC 접속 정보를 읽어서 DataSource 생성
        String url = properties.getProperty("jdbc.url");
        String user = properties.getProperty("jdbc.user");
        String password = properties.getProperty("jdbc.password");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public void store(String key, BCB001I value) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO BCB001I (id, name, age) VALUES (?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE name = VALUES(name), age = VALUES(age)")) {
            ps.setString(1, value.getId());
            ps.setString(2, value.getName());
            ps.setInt(3, value.getAge());
            ps.executeUpdate();
        }
    }
    // ... load, delete 등도 직접 JDBC로 구현
}
```

**hazelcast.xml MapStore 설정 변경:**
```xml
<map name="BCB001I">
    <map-store enabled="true">
        <class-name>com.bt.hz.server.mapstore.BCB001IMapStore</class-name>
        <write-delay-seconds>0</write-delay-seconds>
        <properties>
            <property name="jdbc.url">${SPRING_DATASOURCE_URL}</property>
            <property name="jdbc.user">${DB_USER}</property>
            <property name="jdbc.password">${DB_PASSWORD}</property>
        </properties>
    </map-store>
</map>
```

**해결 방안 B: Generic MapStore + GenericMapLoader (Hazelcast 5.2+)**

Hazelcast 5.2부터 제공되는 `GenericMapStore`를 사용하여 JDBC를 직접 연결:
```xml
<map name="BCB001I">
    <map-store enabled="true">
        <class-name>com.hazelcast.mapstore.GenericMapStore</class-name>
        <properties>
            <property name="external-data-store-ref">mysql-ds</property>
            <property name="table-name">BCB001I</property>
        </properties>
    </map-store>
</map>

<external-data-store name="mysql-ds">
    <class-name>com.hazelcast.datastore.JdbcDataStoreFactory</class-name>
    <properties>
        <property name="jdbcUrl">${SPRING_DATASOURCE_URL}</property>
        <property name="username">${DB_USER}</property>
        <property name="password">${DB_PASSWORD}</property>
    </properties>
</external-data-store>
```

---

### 4.5단계: SpringBootStrapListener → ServerBootstrapListener 변환

**현재:** Hazelcast STARTED → Spring Boot 기동 → 클러스터 동기화 → 초기화 순서 진행
**변경:** Hazelcast STARTED → 직접 초기화 (Spring 없이)

```java
// server/src/main/java/com/bt/hz/server/bootstrap/ServerBootstrapListener.java
public class ServerBootstrapListener implements LifecycleListener {

    @Override
    public void stateChanged(LifecycleEvent event) {
        if (event.getState() == LifecycleEvent.LifecycleState.STARTED) {
            initializeServerComponents();
        }
    }

    private void initializeServerComponents() {
        HazelcastInstance hz = Hazelcast.getAllHazelcastInstances().stream()
            .findFirst().orElseThrow();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 클러스터 동기화 대기 (Spring 없이 직접 IMap 사용)
                awaitClusterReady(hz);

                // 2. SQL Mapping 등록
                MappingSqlRunner.initMappingSqls(hz);

                // 3. MapStore Eager Load (MapStore가 자체적으로 JDBC 사용)
                hz.getMap("BCB001I").loadAll(true);

                // 4. Jet 파이프라인 초기화
                SessionJetPipelineConfig.initPipeline(hz);

                // 5. 스케줄러 등록
                MSYSSE015IScheduler.register(hz);

            } catch (Exception e) {
                hz.getLifecycleService().terminate();
            }
        });
    }

    private void awaitClusterReady(HazelcastInstance hz) throws InterruptedException {
        IMap<String, Boolean> readyMap = hz.getMap("sys_hz_ready");
        String localUuid = hz.getCluster().getLocalMember().getUuid().toString();
        readyMap.put(localUuid, true);

        while (true) {
            int clusterSize = hz.getCluster().getMembers().size();
            long readyCount = hz.getCluster().getMembers().stream()
                .filter(m -> readyMap.containsKey(m.getUuid().toString()))
                .count();
            if (readyCount >= clusterSize) break;
            Thread.sleep(1000);
        }
    }
}
```

**삭제 대상 (server에서 불필요):**
- `SpringBootStrapListener.java` (→ `ServerBootstrapListener`로 대체)
- `ClusterReadinessCoordinator.java` (→ `ServerBootstrapListener` 내부로 통합)
- `SpringContextHolder.java` (Spring 의존성 제거 후 불필요)
- `HazelcastConfig.java` (서버에서 Spring Bean 불필요)
- `HazelcastApplication.java` (→ Hazelcast 기본 기동 방식 사용)

---

### 4.6단계: SessionJetPipelineConfig에서 Spring 의존성 제거

**현재:** `@Configuration`, `@RequiredArgsConstructor`, `HazelcastInstance` 주입
**변경:** 순수 Java 클래스, `HazelcastInstance`를 파라미터로 받는 static 메서드

```java
// 변경 전
@Configuration
@RequiredArgsConstructor
public class SessionJetPipelineConfig {
    private final HazelcastInstance hazelcastInstance;
    public void initPipeline() { ... }
}

// 변경 후
public class SessionJetPipelineConfig {
    public static void initPipeline(HazelcastInstance hz) {
        JetService jet = hz.getJet();
        // ... 기존 파이프라인 로직 동일
    }
}
```

**변경 영역:**
- `@Configuration`, `@RequiredArgsConstructor` 어노테이션 제거
- `hazelcastInstance` 필드 → 메서드 파라미터로 변경
- 파이프라인 내부 로직(buildPipeline)은 변경 없음
- 내부 DTO 클래스들 (SessionInfo, SessionEventTransport 등) 변경 없음

---

### 4.7단계: MappingSqlRunner, MapStoreLoaderRunner Spring 의존성 제거

**MappingSqlRunner:**
```java
// 변경 전: @Component, @RequiredArgsConstructor, HazelcastInstance 주입
// 변경 후: static 메서드
public class MappingSqlRunner {
    public static void initMappingSqls(HazelcastInstance hz) {
        boolean isMaster = hz.getCluster().getMembers().iterator().next().localMember();
        if (!isMaster) return;
        
        SqlService sqlService = hz.getSql();
        for (String sql : MappingSQLs.getAllMappings()) {
            sqlService.execute(sql);
        }
    }
}
```

**MapStoreLoaderRunner:** → `ServerBootstrapListener`에서 직접 `hz.getMap("BCB001I").loadAll(true)` 호출로 대체 (별도 클래스 불필요)

---

### 4.8단계: server Dockerfile 수정

```dockerfile
FROM hazelcast/hazelcast:5.5.0

USER root
RUN rm -f /opt/hazelcast/lib/hazelcast-jet-mongodb-*.jar
USER hazelcast

# 1. Hazelcast 설정 파일
COPY server/hazelcast.xml /opt/hazelcast/config/hazelcast.xml

# 2. common 모듈 JAR (모델 + 직렬화)
COPY common/build/libs/common-*.jar /opt/hazelcast/lib/

# 3. server 모듈 JAR (MapStore, Pipeline 등)
COPY server/build/libs/server-*.jar /opt/hazelcast/lib/

# 4. server 런타임 의존성 (JDBC 드라이버, Jackson 등) - Spring 제외!
COPY server/build/libs/dependencies/*.jar /opt/hazelcast/lib/

ENV JAVA_OPTS="-Dhazelcast.config=/opt/hazelcast/config/hazelcast.xml"
```

**핵심 차이:** Spring Boot, MyBatis, spring-session 등의 JAR이 더 이상 Hazelcast 서버에 포함되지 않음

---

### 4.9단계: app 모듈 생성 - Spring Boot Client Application

**app/build.gradle:**
```groovy
plugins {
    id 'org.springframework.boot' version '3.4.3'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencies {
    implementation project(':common')
    
    // Hazelcast Client
    implementation "com.hazelcast:hazelcast:5.5.0"
    
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    
    // Spring Session + Hazelcast (Client 모드)
    implementation 'org.springframework.session:spring-session-hazelcast'
    
    // MyBatis + MySQL
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4'
    runtimeOnly 'com.mysql:mysql-connector-j'
    
    // Jackson
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
}
```

**HazelcastClientApplication.java:**
```java
@SpringBootApplication
public class HazelcastClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(HazelcastClientApplication.class, args);
    }
}
```

**HazelcastClientConfig.java:**
```java
@Configuration
public class HazelcastClientConfig {

    @Bean
    public HazelcastInstance hazelcastInstance() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName("dev");
        clientConfig.getNetworkConfig().addAddress("127.0.0.1:5701", "127.0.0.1:5702", "127.0.0.1:5703");
        
        // common 모듈의 직렬화 팩토리 등록
        clientConfig.getSerializationConfig()
            .addDataSerializableFactory(
                ServerDataSerializableFactory.FACTORY_ID,
                new ServerDataSerializableFactory());
        
        return HazelcastClient.newHazelcastClient(clientConfig);
    }
}
```

**application.yml (app 모듈):**
```yaml
spring:
  application:
    name: hazelcast-client-app
  datasource:
    url: jdbc:mysql://localhost:3306/testdb?useSSL=false&serverTimezone=UTC
    username: testuser
    password: testpassword
    driver-class-name: com.mysql.cj.jdbc.Driver
server:
  port: 8090
mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

---

### 4.10단계: Docker Compose 구성 변경

```yaml
version: '3.8'

services:
  # ===== Hazelcast 클러스터 (서버) =====
  hz-node1:
    build:
      context: .
      dockerfile: server/Dockerfile
    container_name: hz-node1
    ports:
      - "5701:5701"
    environment:
      - HZ_CLUSTERNAME=dev
      - JAVA_OPTS=-Dhazelcast.config=/opt/hazelcast/config/hazelcast.xml
          -Dhz.client.public.address=127.0.0.1:5701
      - DB_URL=jdbc:mysql://host.docker.internal:3306/testdb
      - DB_USER=testuser
      - DB_PASSWORD=testpassword
    extra_hosts:
      - "host.docker.internal:host-gateway"
    networks:
      - hazelcast-network

  hz-node2:
    build:
      context: .
      dockerfile: server/Dockerfile
    container_name: hz-node2
    ports:
      - "5702:5701"
    environment:
      - HZ_CLUSTERNAME=dev
      - JAVA_OPTS=-Dhazelcast.config=/opt/hazelcast/config/hazelcast.xml
          -Dhz.client.public.address=127.0.0.1:5702
      - DB_URL=jdbc:mysql://host.docker.internal:3306/testdb
      - DB_USER=testuser
      - DB_PASSWORD=testpassword
    extra_hosts:
      - "host.docker.internal:host-gateway"
    depends_on:
      - hz-node1
    networks:
      - hazelcast-network

  hz-node3:
    build:
      context: .
      dockerfile: server/Dockerfile
    container_name: hz-node3
    ports:
      - "5703:5701"
    environment:
      - HZ_CLUSTERNAME=dev
      - JAVA_OPTS=-Dhazelcast.config=/opt/hazelcast/config/hazelcast.xml
          -Dhz.client.public.address=127.0.0.1:5703
      - DB_URL=jdbc:mysql://host.docker.internal:3306/testdb
      - DB_USER=testuser
      - DB_PASSWORD=testpassword
    extra_hosts:
      - "host.docker.internal:host-gateway"
    depends_on:
      - hz-node1
      - hz-node2
    networks:
      - hazelcast-network

  # ===== Spring Boot Application (클라이언트) =====
  app:
    build:
      context: .
      dockerfile: app/Dockerfile
    container_name: hz-client-app
    ports:
      - "8090:8090"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/testdb
      - HZ_CLUSTER_ADDRESSES=hz-node1:5701,hz-node2:5701,hz-node3:5701
    extra_hosts:
      - "host.docker.internal:host-gateway"
    depends_on:
      - hz-node1
      - hz-node2
      - hz-node3
    networks:
      - hazelcast-network

  # ===== Management Center =====
  hz-mancenter:
    image: hazelcast/management-center:5.5
    container_name: hz-mancenter
    ports:
      - "8080:8080"
    environment:
      - MC_DEFAULT_CLUSTER=dev
      - MC_DEFAULT_CLUSTER_MEMBERS=hz-node1,hz-node2,hz-node3
    depends_on:
      - hz-node1
    networks:
      - hazelcast-network

networks:
  hazelcast-network:
    driver: bridge
```

---

### 4.11단계: hazelcast.xml 정리

**제거 항목:**
- `<listeners>` 섹션의 `SpringBootStrapListener` → `ServerBootstrapListener`로 변경
- `data-serializable-factory`의 클래스 경로 → common 모듈 경로로 변경
- `BCB001IMapStore`의 클래스 경로 → server 모듈 경로로 변경

**변경 예시:**
```xml
<listeners>
    <listener>com.bt.hz.server.bootstrap.ServerBootstrapListener</listener>
</listeners>

<serialization>
    <data-serializable-factories>
        <data-serializable-factory factory-id="1001">
            com.bt.hz.common.serialization.ServerDataSerializableFactory
        </data-serializable-factory>
    </data-serializable-factories>
</serialization>

<map name="BCB001I">
    <map-store enabled="true">
        <class-name>com.bt.hz.server.mapstore.BCB001IMapStore</class-name>
        <write-delay-seconds>0</write-delay-seconds>
        <properties>
            <property name="jdbc.url">${DB_URL}</property>
            <property name="jdbc.user">${DB_USER}</property>
            <property name="jdbc.password">${DB_PASSWORD}</property>
        </properties>
    </map-store>
</map>
```

---

### 4.12단계: HazelcastApplication.java 제거/변경

**현재:** `HazelcastApplication.main()`에서 Hazelcast 인스턴스를 직접 생성
**변경:** Hazelcast 공식 Docker 이미지의 기본 `com.hazelcast.core.server.HazelcastMemberStarter`를 사용

Hazelcast 공식 이미지는 자체적으로 `hazelcast.xml`을 읽어 인스턴스를 시작하므로, 별도의 main 클래스가 불필요합니다. `ServerBootstrapListener`가 `hazelcast.xml`의 `<listeners>`에 등록되어 있으므로 기동 시 자동으로 호출됩니다.

→ `HazelcastApplication.java` 삭제

---

## 5. 파일별 변경 요약표

| 현재 파일 | 조치 | 이동 위치 | 비고 |
|----------|------|----------|------|
| `HazelcastApplication.java` | **삭제** | - | Hazelcast 기본 기동 사용 |
| `SpringBootStrapListener.java` | **재작성** | `server` → `ServerBootstrapListener` | Spring 의존성 완전 제거 |
| `ClusterReadinessCoordinator.java` | **통합** | `ServerBootstrapListener` 내부 | 별도 클래스 불필요 |
| `SpringContextHolder.java` | **삭제** | - | Spring 제거 후 불필요 |
| `HazelcastConfig.java` | **삭제 (server)** | `app` → `HazelcastClientConfig` | Client 모드로 전환 |
| `BCB001IMapStore.java` | **재작성** | `server` | JDBC 직접 사용 |
| `SessionJetPipelineConfig.java` | **수정** | `server` | Spring 어노테이션 제거, static 메서드화 |
| `MSYSSE015IScheduler.java` | **이동** | `server` | 변경 없음 (이미 Spring 비의존) |
| `MSYSSE015IEntryProcessor.java` | **이동** | `server` | 변경 없음 |
| `MappingSQLs.java` | **이동** | `server` | 변경 없음 |
| `MappingSqlRunner.java` | **수정** | `server` | Spring 어노테이션 제거, static 메서드화 |
| `MapStoreLoaderRunner.java` | **삭제** | - | `ServerBootstrapListener`에서 직접 호출 |
| `BCB001I.java` 등 모델 | **이동** | `common` | package 경로만 변경 |
| `ServerDataSerializableFactory.java` | **이동** | `common` | package 경로만 변경 |
| `BCB001IMapper.java` (MyBatis) | **이동** | `app` | Client 앱에서 자체 DB 접근 |
| `BCB001IMapper.xml` (MyBatis XML) | **이동** | `app` | Client 앱에서 자체 DB 접근 |
| `SpringContext.java` | **이동** | `app` (필요 시) | Client 앱 전용 |
| `application.yml` | **분리** | `app` | Client 앱 전용 설정 |
| `hazelcast.xml` | **수정** | `server` | 클래스 경로 변경 |
| `Dockerfile` | **분리** | `server/Dockerfile`, `app/Dockerfile` | 각 모듈별 |
| `hazelcast-docker.yml` | **재작성** | `docker-compose.yml` | app 서비스 추가 |
| `build.gradle` | **분리** | 루트 + 모듈별 | 멀티 모듈 구조 |

---

## 6. 분리 후 장점

### 6.1 독립적 배포 (핵심 장점)
- Application을 수정/재배포해도 Hazelcast 클러스터를 재시작할 필요 없음
- Hazelcast 클러스터의 가동 시간(Uptime)이 Application 배포 주기와 무관하게 유지됨
- 비즈니스 로직 변경 시 Application 컨테이너만 교체하면 됨

### 6.2 독립적 스케일링
- Application 인스턴스 수를 Hazelcast 노드 수와 독립적으로 조정 가능
- 트래픽 증가 시 Application만 Scale-out, 데이터 증가 시 Hazelcast만 Scale-out
- 리소스(CPU, 메모리) 할당을 역할에 맞게 최적화 가능

### 6.3 장애 격리
- Application 장애(OOM, 버그 등)가 Hazelcast 클러스터에 전파되지 않음
- 현재 구조에서는 Spring Context 초기화 실패 시 Hazelcast 노드가 terminate됨 (`SpringBootStrapListener:107`)
- 분리 후 Application이 죽더라도 Hazelcast 데이터와 Jet 파이프라인은 계속 동작

### 6.4 관심사 분리 (Separation of Concerns)
- Hazelcast 서버: 데이터 저장, 스트림 처리, 스케줄링에 집중
- Application: 비즈니스 로직, REST API, 사용자 인터페이스에 집중
- 각 팀이 독립적으로 개발/테스트 가능

### 6.5 기술 스택 유연성
- Application 모듈은 Spring Boot 버전을 자유롭게 업그레이드 가능
- 향후 Application을 다른 프레임워크(Quarkus, Micronaut 등)로 교체 가능
- Hazelcast 버전 업그레이드 시 Application 코드 변경 최소화

### 6.6 Docker 이미지 크기 감소 (서버 측)
- Hazelcast 서버 이미지에서 Spring Boot, MyBatis 등 불필요한 의존성 제거
- 현재: Hazelcast + Spring Boot + MyBatis + MySQL Driver = ~200MB+
- 분리 후: Hazelcast + common + server (JDBC만) = ~150MB 미만

### 6.7 보안 강화
- Hazelcast 서버에 불필요한 웹 프레임워크(Spring Web 등)가 포함되지 않음
- 네트워크 레벨에서 Hazelcast 클러스터를 내부망에만 노출, Application만 외부 노출 가능
- Attack surface 감소

---

## 7. 분리 후 단점

### 7.1 네트워크 오버헤드 증가
- 현재: 동일 JVM 내 메서드 호출 (네트워크 비용 0)
- 분리 후: Application ↔ Hazelcast 간 TCP 통신 발생
- IMap.get/put 호출마다 네트워크 라운드트립 추가 (로컬 대비 ~0.5~2ms 지연)
- 대량 데이터 조회 시 직렬화/역직렬화 비용 증가

### 7.2 운영 복잡도 증가
- 관리해야 할 컨테이너/프로세스가 기존 3개(Hazelcast 노드) → 4개 이상(+ Application)으로 증가
- Docker Compose/Kubernetes 구성이 더 복잡해짐
- 모니터링 대상 증가 (Hazelcast 클러스터 + Application 각각 모니터링 필요)
- 장애 진단 시 네트워크 구간까지 점검 필요

### 7.3 개발 환경 복잡도 증가
- 멀티 모듈 프로젝트 구조 학습 필요
- 로컬 개발 시 Hazelcast 클러스터를 먼저 기동한 후 Application을 실행해야 함
- 현재 구조에서는 단일 프로세스로 모든 것이 기동되어 개발이 간편
- 디버깅 시 두 프로세스를 동시에 Attach해야 할 수 있음

### 7.4 BCB001IMapStore 재작성 비용
- 현재 MyBatis로 구현된 MapStore를 직접 JDBC로 재작성해야 함
- Spring의 트랜잭션 관리(`TransactionTemplate`) 기능을 수동으로 구현해야 함
- Connection Pool 관리(HikariCP 직접 설정 등)를 수동으로 해야 함
- 향후 MapStore가 추가될 때마다 같은 패턴을 반복해야 함

### 7.5 직렬화 호환성 관리 부담
- common 모듈의 모델을 변경할 경우 server와 app 양쪽 모두 재배포 필요
- 모델 필드 추가/변경 시 `IdentifiedDataSerializable`의 `readData`/`writeData` 호환성을 양쪽에서 맞춰야 함
- common 모듈 버전 불일치 시 직렬화 오류(ClassNotFoundException, 필드 불일치) 발생 가능

### 7.6 클러스터 접속 관리 부담
- Application이 Hazelcast Client로 접속하므로, 클러스터 주소 관리 필요
- 클러스터 노드가 추가/제거될 때 Client 설정도 업데이트 필요 (Smart Routing 사용 시 자동화 가능)
- 네트워크 파티션 시 Client 연결이 끊길 수 있고, 재접속 로직이 필요
- Client 수가 많아지면 Hazelcast 서버에 Client 연결 부하 발생

### 7.7 Spring Session 통합 복잡도
- 현재 `spring-session-hazelcast`가 embedded 모드로 동작
- Client 모드에서의 Spring Session 설정이 다소 다름 (`@EnableHazelcastHttpSession` + ClientConfig)
- bt_sessions 맵의 이벤트 저널 → Jet Pipeline 연동이 여전히 서버 측에서 동작하므로, Session 쓰기는 Client에서 하고 이벤트 처리는 서버에서 하는 비동기 구조가 됨

### 7.8 테스트 복잡도 증가
- 통합 테스트 시 Hazelcast 클러스터 + Application을 모두 기동해야 함
- 현재는 단일 프로세스에서 모든 것을 테스트 가능
- CI/CD 파이프라인에서 테스트 환경 구성이 더 복잡해짐

---

## 8. 위험 요소 및 주의 사항

### 8.1 데이터 마이그레이션 불필요
- Hazelcast 맵 데이터는 메모리 기반이므로, 클러스터 재구성 시 자연스럽게 새 구조로 전환
- MapStore(BCB001I)를 통한 DB 데이터는 동일 DB를 참조하므로 마이그레이션 불필요
- 단, 전환 시 클러스터 전체 재시작이 필요 (기존 직렬화 팩토리 클래스 경로 변경 때문)

### 8.2 직렬화 팩토리 클래스 경로 변경 주의
- `com.bt.hz.config.ServerDataSerializableFactory` → `com.bt.hz.common.serialization.ServerDataSerializableFactory`
- `hazelcast.xml`의 `data-serializable-factory` 설정과 Client 측 설정이 모두 동일한 Factory ID(1001)와 Type ID를 사용해야 함
- **Factory ID와 Type ID 값은 절대 변경하지 말 것** (1001, 1, 2, 3, 4)

### 8.3 Jet Pipeline 내부 클래스 직렬화
- `SessionInfo`, `SessionEventTransport`, `ExtractedSessionEvent`, `SessionEventWrapper` 등 내부 DTO 클래스들이 `java.io.Serializable`을 구현
- 이 클래스들은 Jet Pipeline 내부에서만 사용되므로 server 모듈에 남아야 함
- 클래스 경로가 변경되므로, 실행 중인 Jet Job이 있는 상태에서 업그레이드하면 직렬화 오류 발생
- **반드시 Jet Job을 중지한 후 클러스터를 업그레이드할 것**

### 8.4 단계적 전환 권장
- 한 번에 모든 변경을 적용하지 말고, 아래 순서로 점진적 전환 권장:
  1. 먼저 멀티 모듈 구조만 적용 (코드 이동 없이 빌드 구조만)
  2. common 모듈 분리 (모델 클래스 이동)
  3. server 모듈 분리 (MapStore JDBC 전환이 핵심)
  4. app 모듈 생성 및 Client 모드 연동 검증
  5. Docker Compose 전환 및 통합 테스트
