FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Retry build up to 3 times if Maven Central fails
RUN for i in 1 2 3; do \
      mvn -q -DskipTests clean package && break || \
      (echo "Build attempt $i failed, retrying..." && sleep 5); \
    done

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -u 10001 -m appuser
USER 10001
COPY --from=build /app/target/tikrai-mail-receiver-0.1.0.jar app.jar
EXPOSE 8080 2525
ENTRYPOINT ["java","-jar","/app/app.jar"]
