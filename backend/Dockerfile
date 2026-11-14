# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI, not here: they start an embedded PostgreSQL, which refuses to run as root in a build container.
RUN ./mvnw -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre
RUN groupadd --system payflow && useradd --system --gid payflow --no-create-home payflow
WORKDIR /app
COPY --from=build /workspace/target/payflow-lab-backend-*.jar app.jar
USER payflow
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
