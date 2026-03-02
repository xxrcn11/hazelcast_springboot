FROM hazelcast/hazelcast:5.5.0

# MongoDB Jet 커넥터 jar 제거 (내장된 모니터 스레드가 localhost:27017로 불필요한 연결 시도를 하는 것을 방지)
USER root
RUN rm -f /opt/hazelcast/lib/hazelcast-jet-mongodb-*.jar
USER hazelcast

# 1. 커스텀 hazelcast.xml 설정 파일 복사
COPY hazelcast.xml /opt/hazelcast/config/hazelcast.xml

# 2. 애플리케이션의 런타임 의존성(Spring, MyBatis 등) JAR 파일들 복사
COPY build/libs/dependencies/*.jar /opt/hazelcast/lib/

# 3. 직접 빌드한 애플리케이션 JAR 파일 복사
COPY build/libs/*.jar /opt/hazelcast/lib/

# 4. Hazelcast가 커스텀 설정 파일을 사용하도록 환경변수 지정
ENV JAVA_OPTS="-Dhazelcast.config=/opt/hazelcast/config/hazelcast.xml \
    -Dlogging.level.com.hazelcast.jet=WARN"
