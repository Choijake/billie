FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew && ./gradlew clean bootJar

FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8081

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java","-jar","app.jar"]