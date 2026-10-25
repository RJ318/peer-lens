FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 peerlens
WORKDIR /app
COPY --from=build /app/target/peer-lens-api.jar app.jar
USER peerlens
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
