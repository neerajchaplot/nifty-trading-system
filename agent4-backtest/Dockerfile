FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/agent4-backtest-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
