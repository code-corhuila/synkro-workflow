FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY workflow-core/pom.xml workflow-core/pom.xml
COPY workflow-adapters/pom.xml workflow-adapters/pom.xml
COPY workflow-app/pom.xml workflow-app/pom.xml
RUN mvn -B -q -pl workflow-core,workflow-adapters,workflow-app dependency:go-offline || true
COPY . .
RUN mvn -B -q -pl workflow-app -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
RUN apk upgrade --no-cache && apk del gnupg
RUN addgroup -S synkro && adduser -S synkro -G synkro
COPY --from=build /src/workflow-app/target/workflow-app-*.jar /app/workflow-app.jar
USER synkro
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/workflow-app.jar"]
