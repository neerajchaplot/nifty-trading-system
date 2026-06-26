FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY target/agent2-recommendation-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
