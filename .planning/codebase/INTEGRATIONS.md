# External Integrations

**Analysis Date:** 2026-03-30

## APIs & External Services

**None:**
- The billing engine is a **pure calculation library** with zero external API dependencies
- No HTTP clients, no remote service calls, no third-party SDKs
- All calculation is deterministic and self-contained

## Data Storage

**Databases:**
- **None required by core engine**
- The core module (`billing-core`) has NO database dependencies
- Configuration is provided via `BillingConfigResolver` interface - users implement this to fetch configs from their preferred source
- Sample configuration in `core/src/main/resources/application.yaml` shows MySQL example, but this is for development reference only (contains credentials that should be externalized)

**File Storage:**
- Local filesystem only (for Maven build outputs)
- No cloud storage integration

**Caching:**
- None - Not applicable to pure calculation library

## Authentication & Identity

**Auth Provider:**
- Not applicable - Library has no authentication requirements
- Users integrate the library into their own secured applications

## Monitoring & Observability

**Error Tracking:**
- None - Library throws exceptions for caller to handle

**Logs:**
- None - Library follows "no side effects" principle
- Callers should wrap with their own logging as needed

## CI/CD & Deployment

**Hosting:**
- Maven Central Repository (Sonatype Central Portal)
- Published as library artifacts, not deployed as a service

**CI Pipeline:**
- None detected (no `.github/workflows/` directory)
- Manual release process via Maven Central Publishing Plugin

**Publication Targets:**
- `billing-core` - Core engine jar
- `billing-api` - API wrapper jar (also fat jar with dependencies)
- `billing-v3-spring-boot-starter` - Spring Boot 3.x starter
- `billing-v4-spring-boot-starter` - Spring Boot 4.x starter
- `bill-test` - NOT published (skip flags set in pom.xml)

## Environment Configuration

**Required env vars:**
- None for library usage
- For Maven Central publication: GPG signing credentials and Central Portal credentials (user-provided)

**Secrets location:**
- Not applicable for library
- Maven settings.xml for publication credentials (user's local environment)

## Webhooks & Callbacks

**Incoming:**
- None - Library does not accept webhooks

**Outgoing:**
- None - Library does not make outbound calls

## Integration Pattern

**Design Philosophy:**
The billing engine follows a **dependency-free core** design:

1. **Core engine (`billing-core`)**: Zero external dependencies, pure calculation
2. **Configuration abstraction**: `BillingConfigResolver` interface lets users fetch configs from any source (database, file, API, etc.)
3. **Spring Boot starters**: Provide auto-configuration but do not add external dependencies

**User Integration Responsibility:**
- Users implement `BillingConfigResolver` to connect to their data source
- Users handle persistence, caching, logging at their application layer
- Users manage authentication in their wrapping application

**Sample Configuration Reference:**
`core/src/main/resources/application.yaml` - Contains MySQL connection example for reference
- Note: This file is NOT used by the core engine; it's a sample showing how users might configure their own data source

---

*Integration audit: 2026-03-30*