# syntax=docker/dockerfile:1.7
# Shared image build for every Java service. Build context: java/
#   docker build -f Dockerfile --build-arg SERVICE=catalog -t catalog-service java
# CI can alternatively use Jib (./mvnw -pl services/<svc> jib:build), which produces the same layout.
FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl services/${SERVICE} -am package -DskipTests \
    && cp services/${SERVICE}/target/*-service-*.jar /app.jar \
    && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 1000 app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 1000
ENV SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher", "--server.port=8080"]
