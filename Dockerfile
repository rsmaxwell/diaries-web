FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle
COPY diaries-web ./diaries-web

RUN mkdir -p diaries-responder \
    && chmod +x gradlew \
    && ./gradlew :diaries-web:test :diaries-web:shadowJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
WORKDIR /opt/diaries-web

RUN addgroup -S diaries \
    && adduser -S -G diaries -h /opt/diaries-web diaries

COPY --from=build \
    /workspace/diaries-web/build/libs/diaries-web-*-fat.jar \
    /opt/diaries-web/diaries-web.jar

USER diaries
EXPOSE 8082

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=12 \
    CMD wget --quiet --spider http://127.0.0.1:8082/health/ready || exit 1

ENTRYPOINT ["java", "-jar", "/opt/diaries-web/diaries-web.jar"]
CMD ["--config", "/config/diaries-web.json"]
