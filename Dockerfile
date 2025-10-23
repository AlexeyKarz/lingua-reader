# ---- extractor stage (needs 'java -jar' layertools) ----
FROM eclipse-temurin:23-jdk AS extractor
WORKDIR /app
ARG JAR=build/libs/multilang-reader-mono-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---- runtime stage ----
FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/application/ ./
EXPOSE 8080
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
