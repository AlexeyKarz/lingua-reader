plugins {
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.6"
    id("java")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(23)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus registry so /actuator/prometheus exposes metrics
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("org.springframework.boot:spring-boot-starter-aop") // needed for @Timed on beans

    // (Optional but recommended) Spring Modulith — helps structure a modular monolith
    implementation("org.springframework.modulith:spring-modulith-starter-core:1.2.13")

    // parsing EPUB → XHTML/text
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")

    // HTML parsing for chapter/paragraph extraction
    implementation("org.jsoup:jsoup:1.18.1")

    // sentence splitting (Java-native)
    implementation("org.apache.opennlp:opennlp-tools:2.4.0")

    // DB access (Spring JDBC is simple and fast for batch inserts)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // pgvector Java helpers (maps float[] to pgvector type)
    implementation("com.pgvector:pgvector:0.1.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // (Optional later) Testcontainers for Postgres or external deps
    // testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    // testImplementation("org.testcontainers:postgresql:1.20.4")
}

tasks.test { useJUnitPlatform() }
