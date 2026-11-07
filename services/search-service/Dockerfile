# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mkdir -p /root/.m2 && printf '<toolchains><toolchain><type>jdk</type><provides><version>21</version><vendor>temurin</vendor></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>' "$JAVA_HOME" > /root/.m2/toolchains.xml && \
    mvn -q -pl services/search-service -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S discoveryhub && adduser -S discoveryhub -G discoveryhub
WORKDIR /app
COPY --from=build /workspace/services/search-service/target/search-service-*.jar app.jar
USER discoveryhub
EXPOSE 8083
ENTRYPOINT ["java","-jar","/app/app.jar"]
