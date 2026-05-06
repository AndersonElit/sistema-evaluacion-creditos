FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/infrastructure/entry-points/app/target/quarkus-app/ ./quarkus-app/
EXPOSE 8083
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
