# finplay-api 애플리케이션 이미지. Gradle로 bootJar를 빌드해 JRE 이미지에 담는다 (Java 17 툴체인).
# 빌드 단계 — 의존성 캐시를 위해 빌드 스크립트를 먼저 복사하고 소스는 나중에 복사한다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY config config
# 소스가 바뀌어도 의존성 레이어는 재사용된다. 네트워크가 없으면 여기서 실패한다.
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
# 테스트는 이 단계에서 돌리지 않는다 — Testcontainers가 빌드 컨테이너 안에서 Docker를 요구한다 (ADR-0003).
# 테스트는 작성자 로컬과 CI(spec 010)에서 ./gradlew build로 검증한다.
RUN ./gradlew --no-daemon bootJar

# 실행 단계
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --create-home --uid 1001 finplay
COPY --from=build /workspace/build/libs/*.jar app.jar
USER finplay

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
