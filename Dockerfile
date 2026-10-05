# Multi-stage Spring Boot API image
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B -DskipTests package \
  && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S novora && adduser -S novora -G novora \
  && mkdir -p /data/uploads && chown -R novora:novora /data
USER novora

COPY --from=build /workspace/app.jar /app/app.jar

# Keep the JVM inside small containers (e.g. Render's 512 MB instances)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENV STORAGE_DIR=/data/uploads

EXPOSE 4000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
