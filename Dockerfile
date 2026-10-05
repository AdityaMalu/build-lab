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
RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" | tar -C /usr/local -xz
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

# pre-build the Go standard library into the runner's cache so the first test run is fast
RUN setpriv --reuid=runner --regid=runner --clear-groups \
    env HOME=/tmp GOCACHE=/opt/gocache CGO_ENABLED=0 GOTOOLCHAIN=local GOTELEMETRY=off go build std

ENV LAB_MODE=hosted \
    PORT=8080 \
    LAB_PARALLEL_RUNS=1 \
    LAB_TEST_HEAP=200m \
    LAB_GOCACHE=/opt/gocache
EXPOSE 8080

CMD ["java", "-Xmx150m", "-XX:+UseSerialGC", "server/LabServer.java"]
