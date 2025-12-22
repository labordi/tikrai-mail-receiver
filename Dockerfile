FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -u 10001 -m appuser
USER 10001
COPY --from=build /app/target/tikrai-mail-receiver-0.1.0.jar app.jar
EXPOSE 8080 2525
ENTRYPOINT ["java","-jar","/app/app.jar"]
