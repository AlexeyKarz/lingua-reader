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

    implementation("org.springframework.boot:spring-boot-starter-aop") // needed for @Timed on beans

    // (Optional but recommended) Spring Modulith — helps structure a modular monolith
    implementation("org.springframework.modulith:spring-modulith-starter-core:1.2.13")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // (Optional later) Testcontainers for Postgres or external deps
    // testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    // testImplementation("org.testcontainers:postgresql:1.20.4")
}

tasks.test { useJUnitPlatform() }
