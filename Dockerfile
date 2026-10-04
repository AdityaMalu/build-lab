# Build Lab, hosted mode (multi-user website).
# Visitors' code is compiled and run in short-lived, sandboxed JVMs inside this container.
FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY projects ./projects
COPY testkit ./testkit
COPY server ./server
COPY web ./web

# never run submitted code as root
RUN useradd --create-home --shell /usr/sbin/nologin lab && chown -R lab:lab /app
USER lab

ENV LAB_MODE=hosted \
    PORT=8080 \
    LAB_PARALLEL_RUNS=1 \
    LAB_TEST_HEAP=200m
EXPOSE 8080

CMD ["java", "-Xmx160m", "-XX:+UseSerialGC", "server/LabServer.java"]
