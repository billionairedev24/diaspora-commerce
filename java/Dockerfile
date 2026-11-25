# syntax=docker/dockerfile:1.7
# Shared image build for every Java service. Build context: java/
#   docker build -f Dockerfile --build-arg SERVICE=catalog -t catalog-service java
#
# Small, clean and quick on purpose:
#   - own JRE: `jlink` keeps only the JDK modules the jars use (jdeps + a fixed list): ~61 MB instead of the ~280 MB temurin JRE image
#   - distroless base (glibc, libstdc++, CA certificates, no shell or package manager): ~25 MB
#   - foreign-platform native libraries (macOS/Windows/BSD, ppc/s390x/riscv/...) and GraalVM metadata are stripped from the third-party
#     jars (tools/StripNatives.java), the Temurin-only test server and the macOS/Windows netty natives are excluded in apps/core/pom.xml
#   - serial GC, C1-only JIT, small thread stacks, no perf data file: ~335 MB -> ~275 MB resident after start instead of ~620 MB
#     with ZGC. Services with real CPU load can override JAVA_TOOL_OPTIONS (drop -XX:TieredStopAtLevel=1) in their values file.
#   - three layers that change at different speeds: third-party jars (rarely), our own *-SNAPSHOT jars, the application jar. A code change
#     re-pushes our jars only (a few MB); the ~250 MB of libraries stay cached in the registry and on the node.
#   - the jar can come from outside: `--build-arg JAR_FROM=prebuilt` takes java/dist/app.jar (built by the workflow with a cached Maven
#     repository, see .github/actions/java-jar) instead of running Maven in Docker. The default (`source`) builds in Docker, as before.
#   - optional AppCDS (`--build-arg APPCDS=true`; leave it unset to turn it off: any non-empty value sets the JVM flag): a class data archive from a start-up training run at the final paths.
#     Measured on core (4 vCPU, up to the first database connect): 8.2 s -> 6.1 s (-25%), but +115 MB image (archive 100 MB + the JDK's
#     base archive 14.5 MB, and it changes with every build). Off by default: see docs/runbooks/release-1-go-live.md, "Image size".
# S268: base images are pinned by digest (tools/security/pin-base-images.sh resolves and rewrites them; --check verifies).

ARG JAR_FROM=source

FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS jdk

# --- 1a. jar built in Docker (default) ---------------------------------------------------------------------------------------------
FROM jdk AS jar-source
ARG SERVICE
# Release 1 (ADR 0011) builds the deployable from the same file: --build-arg PROJECT=apps/core JAR_GLOB='afrotransact-core*.jar'
# (the core workflow). Per-service builds keep the defaults.
ARG PROJECT=services/${SERVICE}
ARG JAR_GLOB='*-service-*.jar'
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -ntp -pl "${PROJECT}" -am package -DskipTests \
    && mkdir /out \
    && cp "$(ls ${PROJECT}/target/${JAR_GLOB} | grep -v -- '-plain.jar' | head -1)" /out/app.jar

# --- 1b. jar built by the workflow ---------------------------------------------------------------------------------------------------
FROM scratch AS jar-prebuilt
COPY dist/app.jar /out/app.jar

FROM jar-${JAR_FROM} AS jar

# --- 2. layers + JRE -----------------------------------------------------------------------------------------------------------------
FROM jdk AS layers
# Layer timestamps are clamped to SOURCE_DATE_EPOCH by the reproducible export; the jars get exactly that mtime here so the AppCDS archive
# (which records jar sizes and mtimes) still matches after the export.
ARG SOURCE_DATE_EPOCH=1767225600
ARG APPCDS=
COPY --from=jar /out/app.jar /app.jar
RUN java -Djarmode=tools -jar /app.jar extract --destination /x \
    && rm /app.jar \
    && f="$(ls /x | grep -v '^lib$' | head -1)" \
    && { [ "$f" = app.jar ] || mv "/x/$f" /x/app.jar; }
COPY tools/StripNatives.java /tools/StripNatives.java
RUN java /tools/StripNatives.java /x/lib

# Our own snapshot jars (modules, platform libs) change with every commit; everything else rarely: two directories, two layers.
# Both end up in /app/lib, which is where the extracted application jar's manifest Class-Path points.
RUN mkdir /x/own-jars && mv /x/lib/*SNAPSHOT*.jar /x/own-jars/ && mv /x/lib /x/dep-jars \
    && find /x -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +

# Modules the service needs: jdeps finds what its jars reference; the fixed list covers what Spring, JDBC, TLS, Kafka,
# JMX/Micrometer and reflection-heavy libraries load at runtime without a static reference.
# (jdk.localedata and jdk.charsets are deliberately absent: the code uses Locale.US/ENGLISH and windows-1252, which java.base has.)
RUN set -eu; \
    found="$(jdeps --ignore-missing-deps --recursive --multi-release 25 --print-module-deps --class-path '/x/dep-jars/*:/x/own-jars/*' /x/app.jar 2>/dev/null || true)"; \
    base="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.management,jdk.unsupported,jdk.zipfs"; \
    cds=""; [ "${APPCDS}" = true ] && cds="--generate-cds-archive"; \
    jlink --add-modules "${found:+$found,}${base}" --strip-java-debug-attributes --no-man-pages --no-header-files \
          --exclude-files='**.debuginfo,**.diz' --compress=zip-9 ${cds} --output /jre; \
    rm -f /jre/lib/server/classes_nocoops.jsa

# --- 3. optional AppCDS training run (APPCDS=true) --------------------------------------------------------------------------------
# The paths here are the final ones (/opt/jre, /app/lib, /app/app.jar): the archive is only valid for the same class path and jar mtimes.
# The application fails to start (no database in the build): the classes it loaded up to there (Spring, Tomcat, Flyway, drivers) are
# archived, and the JVM falls back to normal class loading for the rest. A mismatch at run time only loses the speed-up, never the start.
FROM jdk AS cds
ARG SOURCE_DATE_EPOCH=1767225600
ARG APPCDS=
COPY --from=layers /jre /opt/jre
COPY --from=layers /x/dep-jars/ /app/lib/
COPY --from=layers /x/own-jars/ /app/lib/
COPY --from=layers /x/app.jar /app/app.jar
RUN mkdir /cds; \
    if [ "${APPCDS}" = true ]; then \
      SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:-UsePerfData -XX:+ExitOnOutOfMemoryError" \
        /opt/jre/bin/java -XX:ArchiveClassesAtExit=/cds/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off -jar /app/app.jar --server.port=8080 >/dev/null 2>&1 || true; \
      [ -s /cds/app.jsa ] || echo "AppCDS: no archive was produced, the image starts without it" >&2; \
      touch -h -d "@${SOURCE_DATE_EPOCH}" /cds/app.jsa 2>/dev/null || true; \
    fi

# --- 4. runtime ----------------------------------------------------------------------------------------------------------------------
FROM gcr.io/distroless/cc-debian12:nonroot@sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f
ARG APPCDS=
LABEL org.opencontainers.image.description="AfroTransact Java deployable: Spring Boot on a jlink-trimmed JRE, distroless, non-root" \
      org.opencontainers.image.base.name="gcr.io/distroless/cc-debian12:nonroot" \
      org.opencontainers.image.base.digest="sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f" \
      com.afrotransact.appcds="${APPCDS}"
WORKDIR /app
COPY --from=layers /jre /opt/jre
COPY --from=layers /x/dep-jars/ ./lib/
COPY --from=layers /x/own-jars/ ./lib/
COPY --from=layers /x/app.jar ./app.jar
COPY --from=cds /cds/ ./
USER 1000:1000
# -XX:TieredStopAtLevel=1 (C1 only) saves ~20% memory and CPU for the low-traffic services we run; -Xss512k is safe because
# request handling runs on virtual threads. MaxRAMPercentage leaves room for metaspace, code cache and native buffers.
# JDK_JAVA_OPTIONS (read by the java launcher, not overridden by the charts' JAVA_TOOL_OPTIONS) points at the AppCDS archive when there is one.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:-UsePerfData -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError" \
    JDK_JAVA_OPTIONS="${APPCDS:+-XX:SharedArchiveFile=/app/app.jsa}"
EXPOSE 8080
ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/app.jar", "--server.port=8080"]
