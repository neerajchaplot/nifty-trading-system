FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/agent5-execution-*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
