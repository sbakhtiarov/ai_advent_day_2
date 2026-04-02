FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle gradle
COPY build.gradle.kts build.gradle.kts
COPY settings.gradle.kts settings.gradle.kts
COPY gradle.properties gradle.properties
COPY src src

RUN chmod +x gradlew
RUN ./gradlew --no-daemon jvmFatJar

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*-all.jar /app/agent-api.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/agent-api.jar"]
