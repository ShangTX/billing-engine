# Technology Stack

**Analysis Date:** 2026-03-30

## Languages

**Primary:**
- Java 21 - Core implementation language, all modules use Java
- Verified compatibility with JDK 25 (OpenJDK 25 tested)

**Secondary:**
- None - Pure Java implementation

## Runtime

**Environment:**
- JDK 21 (minimum required)
- Maven 3.9.11 via Maven Wrapper

**Package Manager:**
- Maven
- Lockfile: Not present (no lockfile mechanism in Maven)
- Wrapper: `.mvn/wrapper/maven-wrapper.properties` present

## Frameworks

**Core:**
- Spring Boot 3.2.0 (billing-v3-spring-boot-starter) - Spring Boot 3.0.x - 3.4.x support
- Spring Boot 4.0.0 (billing-v4-spring-boot-starter) - Spring Boot 3.5.x - 4.x support
- Spring Boot Autoconfigure - For bean registration and configuration properties

**Testing:**
- JUnit Jupiter 5.10.0 - Unit testing framework
- Mockito 5.10.0 - Mocking framework
- Mockito JUnit Jupiter 5.10.0 - Mockito integration with JUnit 5

**Build/Dev:**
- Maven Compiler Plugin 3.13.0 - Java compilation
- Maven Source Plugin 3.3.1 - Source jar packaging
- Maven Javadoc Plugin 3.11.2 - Documentation generation
- Maven GPG Plugin 3.2.7 - Artifact signing for Maven Central
- Maven Shade Plugin 3.6.0 - Fat JAR creation (billing-api module)
- Maven Toolchains Plugin 3.2.0 - JDK version enforcement
- Flatten Maven Plugin 1.6.0 - POM flattening for publication
- Central Publishing Maven Plugin 0.7.0 - Maven Central deployment

## Key Dependencies

**Critical:**
- Lombok 1.18.34 - Code generation for POJOs (`@Data`, `@Builder`, `@AllArgsConstructor`, `@Accessors(chain=true)`)
  - Used extensively in: `core/src/main/java/cn/shang/charging/billing/pojo/*.java`, `core/src/main/java/cn/shang/charging/promotion/pojo/*.java`
  - Annotation processor configured in Maven Compiler Plugin

**Infrastructure:**
- billing-core 2.0.1 - Core calculation engine (internal module)
- billing-api 2.0.1 - Convenient API wrapper (internal module)

**Test Utilities:**
- Jackson datatype JSR310 3.0.0-rc2 - JSON serialization for Java 8 time types (test utility only)

## Configuration

**Environment:**
- Spring Boot properties via `BillingProperties` class
- Configuration prefix: `billing`
- Key property: `billing.schemes` - Map of schemeId to SchemeMeta

**Build:**
- `pom.xml` - Parent POM with shared configuration
- Module POMs: `core/pom.xml`, `billing-api/pom.xml`, `billing-v3-spring-boot-starter/pom.xml`, `billing-v4-spring-boot-starter/pom.xml`, `bill-test/pom.xml`
- Toolchains configuration requires JDK 21

**Project Metadata:**
- Group ID: `io.github.shangtx`
- Artifact ID: `billing-engine` (parent)
- Version: 2.0.1
- License: MIT License
- SCM: GitHub (https://github.com/ShangTX/billing-engine)

## Platform Requirements

**Development:**
- JDK 21+ (toolchains enforce JDK 21)
- Maven 3.6+
- Lombok annotation processor

**Production:**
- JDK 21+ runtime
- For Spring Boot integration: Spring Boot 3.0.x+ or 4.0.x+
- No external server required - library can be embedded in any Java application

---

*Stack analysis: 2026-03-30*