FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/agent1-market_analyst-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
