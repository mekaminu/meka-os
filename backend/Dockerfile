# Sync service image (ADR-004). Built in CI from `./gradlew :backend:installDist`; no RUN steps, so building the
# linux/arm64 image on an x86 runner needs no emulation. Runs as an unprivileged user on a read-only root fs.
# The official eclipse-temurin image through AWS's public mirror of Docker Hub's official images (no account, no
# Docker Hub pull limits or timeouts; the same image the CI's Postgres comes from).
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre
COPY build/install/backend /app
USER 10001:10001
# http.keepAlive.time.server: keep an idle connection to AWS (Polly) open for 60 s instead of the JDK's 5, so the
# next piece of a spoken answer reuses it (voice slice 2).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp -Dhttp.keepAlive.time.server=60"
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
