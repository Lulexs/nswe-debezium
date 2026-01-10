FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw

COPY src ./src

RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:25-jdk-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]