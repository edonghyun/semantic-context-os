# SCOS server image for the local demo. Build context is the repository root (see compose.yaml).
# Stage 1 builds the boot JAR with the Gradle wrapper; stage 2 runs it on a JRE. No credentials are baked in.
FROM eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d AS build
WORKDIR /src
COPY backend/ backend/
RUN --mount=type=cache,target=/root/.gradle \
    cd backend && ./gradlew --no-daemon --quiet bootJar

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --system --uid 10001 scos
COPY --from=build /src/backend/build/libs/scos-backend.jar /app/scos-backend.jar
USER scos
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/scos-backend.jar"]
