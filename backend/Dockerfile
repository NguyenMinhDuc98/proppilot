# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && mv target/proppilot-*.jar app.jar

# ---- run ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 proppilot
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER proppilot
EXPOSE 8080
# Small-heap, fast-start JVM flags so it fits free-tier hosts (512 MB RAM).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["java", "-jar", "app.jar"]
