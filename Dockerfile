FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home --home-dir /app appuser && mkdir -p /data/chunks && chown -R appuser:appuser /app /data
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "mkdir -p /data/chunks && chown -R appuser:appuser /data/chunks && exec su -p -s /bin/sh appuser -c '/opt/java/openjdk/bin/java $JAVA_OPTS -jar /app/app.jar'"]
