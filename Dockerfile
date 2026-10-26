FROM gradle:8.10.2-jdk23 AS build
WORKDIR /workspace
COPY . .
RUN gradle bootJar --no-daemon

# Extract Spring Boot layers from the freshly built jar.
FROM eclipse-temurin:23-jdk AS extractor
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:23-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-deu tesseract-ocr-ell tesseract-ocr-eng \
        tesseract-ocr-fra tesseract-ocr-rus tesseract-ocr-spa \
    && rm -rf /var/lib/apt/lists/*
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/application/ ./
EXPOSE 8080
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
