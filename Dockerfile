# Build Lab, hosted mode (multi-user website) with Java, Python, Go and C++.
#
# The server runs as root only so it can start each test run as the unprivileged "runner" user
# (setpriv) with resource limits (prlimit). Submitted code additionally runs under Java's security
# manager or a seccomp filter installed by the language's test harness. No secrets live in this image.
FROM eclipse-temurin:21-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 g++ ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

ARG GO_VERSION=1.23.4
# amd64 (Render, most PCs) or arm64 (Oracle Ampere, Apple silicon): dpkg names match Go's
RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-$(dpkg --print-architecture).tar.gz" | tar -C /usr/local -xz
ENV PATH=/usr/local/go/bin:$PATH

# the unprivileged user that compiles and runs submitted code
RUN useradd --system --no-create-home --shell /usr/sbin/nologin runner \
 && mkdir -p /opt/gocache && chown runner:runner /opt/gocache

WORKDIR /app
COPY projects ./projects
COPY testkit ./testkit
COPY server ./server
COPY web ./web
# app files stay owned by root and read-only for everyone else

ENV LAB_MODE=hosted \
    PORT=8080 \
    LAB_PARALLEL_RUNS=1 \
    LAB_TEST_HEAP=200m \
    LAB_GOCACHE=/opt/gocache \
    LAB_CPP_KIT=/opt/cppkit
EXPOSE 8080

# compiled once here instead of on every start (each queued run starts a JVM in its own container)
RUN javac -d /app/classes server/LabServer.java

# fill the compile caches so even the first run after a restart is fast: the C++ kit objects and
# precompiled header (/opt/cppkit), and the Go build cache (/opt/gocache, written as the runner user)
# with everything each project's test binary needs
RUN java -cp /app/classes LabServer warmup

# no arguments: the website (Render, or the api role with LAB_ROLE=api)
# "run-job /job": one queued run, inside a fresh gVisor container started by a worker
ENTRYPOINT ["java", "-Xmx150m", "-XX:+UseSerialGC", "-cp", "/app/classes", "LabServer"]
