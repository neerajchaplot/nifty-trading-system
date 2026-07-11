# Hardened runtime image — non-root user, container-aware JVM heap.
# JAR is built by the Maven reactor in CI (monorepo needs sibling modules), then COPYed in.
FROM eclipse-temurin:21-jre-jammy

# Run as an unprivileged user — never as root.
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

WORKDIR /app
COPY --chown=spring:spring target/agent5-execution-*.jar app.jar

USER spring:spring
EXPOSE 8085

# MaxRAMPercentage lets the JVM size its heap from the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
