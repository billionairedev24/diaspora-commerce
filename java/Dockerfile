# syntax=docker/dockerfile:1.7
# Shared image build for every Java service. Build context: java/
#   docker build -f Dockerfile --build-arg SERVICE=catalog -t catalog-service java
#
# Small and lean on purpose (measured on seller-service, the largest default service, 2026-10-21):
#   - own JRE: `jlink` keeps only the JDK modules the service's jars use (about 60 MB instead of the ~280 MB temurin JRE image)
#   - distroless base (glibc, libstdc++, CA certificates, no shell or package manager): about 25 MB
#   - serial GC, C1-only JIT, small thread stacks, no perf data file: ~335 MB -> ~275 MB resident after start instead of ~620 MB
#     with ZGC. Services with real CPU load can override JAVA_TOOL_OPTIONS (drop -XX:TieredStopAtLevel=1) in their values file.
#   - two dependency layers (jars that change rarely, then the application jar) so a code change re-pushes ~1-5 MB
# CI can alternatively use Jib (./mvnw -pl services/<svc> jib:build); it keeps the same JVM flags but cannot slim the JRE.
FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl services/${SERVICE} -am package -DskipTests \
    && cp services/${SERVICE}/target/*-service-*.jar /app.jar \
    && java -Djarmode=tools -jar /app.jar extract --destination /x \
    && mv /x/*-service-*.jar /x/app.jar

# Modules the service needs: jdeps finds what its jars reference; the fixed list covers what Spring, JDBC, TLS, Kafka,
# JMX/Micrometer and reflection-heavy libraries load at runtime without a static reference.
RUN set -eu; \
    found="$(jdeps --ignore-missing-deps --recursive --multi-release 25 --print-module-deps --class-path '/x/lib/*' /x/app.jar 2>/dev/null || true)"; \
    base="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.management,jdk.unsupported,jdk.zipfs"; \
    jlink --add-modules "${found:+$found,}${base}" --strip-java-debug-attributes --no-man-pages --no-header-files \
          --compress=zip-6 --output /jre

FROM gcr.io/distroless/cc-debian12:nonroot
WORKDIR /app
COPY --from=build /jre /opt/jre
COPY --from=build /x/lib/ ./lib/
COPY --from=build /x/app.jar ./app.jar
USER 1000:1000
# -XX:TieredStopAtLevel=1 (C1 only) saves ~20% memory and CPU for the low-traffic services we run; -Xss512k is safe because
# request handling runs on virtual threads. MaxRAMPercentage leaves room for metaspace, code cache and native buffers.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:-UsePerfData -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/app.jar", "--server.port=8080"]
