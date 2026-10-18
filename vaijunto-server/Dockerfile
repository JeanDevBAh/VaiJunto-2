FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /vaijunto

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

CMD [ "java", "-jar", "target/vaijunto-server-1.0-SNAPSHOT-jar-with-dependencies.jar" ]