FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package && \
    find /app/target -name "*.jar" -not -name "*plain.jar" -exec cp {} /app/target/app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -u 10001 -m appuser
USER 10001
COPY --from=build /app/target/app.jar app.jar
EXPOSE 8080 2525
ENTRYPOINT ["java","-jar","/app/app.jar"]
