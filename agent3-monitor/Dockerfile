FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/agent3-monitor-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
