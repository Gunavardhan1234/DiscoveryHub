# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 --mount=type=bind,from=m2repo,target=/root/.m2/repository,rw mkdir -p /root/.m2 && printf '<toolchains><toolchain><type>jdk</type><provides><version>21</version><vendor>temurin</vendor></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>' "$JAVA_HOME" > /root/.m2/toolchains.xml && \
    mvn -q -pl tools/corpus-generator -am package -DskipTests && \
    mvn -q -pl tools/corpus-generator dependency:copy-dependencies -DincludeScope=runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S discoveryhub && adduser -S discoveryhub -G discoveryhub
COPY --from=build /workspace/tools/corpus-generator/target/corpus-generator-*.jar /app/generator.jar
COPY --from=build /workspace/tools/corpus-generator/target/dependency /app/libs
USER discoveryhub
ENTRYPOINT ["java","-cp","/app/generator.jar:/app/libs/*","com.smarsh.discoveryhub.generator.CorpusGenerator"]
