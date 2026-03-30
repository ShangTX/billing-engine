# Codebase Structure

**Analysis Date:** 2026-03-30

## Directory Layout

```
charge/
├── core/                            # Core billing engine (no external dependencies)
│   └── src/main/java/cn/shang/charging/
│       ├── billing/                 # Billing orchestration layer
│       │   └── pojo/                # Billing POJOs (Request, Result, Context, etc.)
│       ├── charge/                  # Charging rule implementations
│       │   ├── pojo/                # Charge POJOs
│       │   └── rules/               # BillingRule interface and implementations
│       │       ├── daynight/        # DayNightRule (day/night pricing)
│       │       ├── relativetime/    # RelativeTimeRule (relative time periods)
│       │       └── compositetime/   # CompositeTimeRule (mixed time rules)
│       ├── promotion/               # Promotion engine and logic
│       │   ├── pojo/                # Promotion POJOs (FreeTimeRange, FreeMinutes, etc.)
│       │   └── rules/               # PromotionRule interface and implementations
│       │       └── minutes/         # FreeMinutesPromotionRule
│       ├── settlement/              # Result assembly and settlement
│       │   └── pojo/                # Settlement POJOs
│       └── util/                    # Utility classes
├── billing-api/                     # Convenient API wrapper
│   └── src/main/java/cn/shang/charging/wrapper/
│       ├── BillingTemplate.java     # Main entry point for convenient API
│       ├── BillingResultViewer.java # Query-time result filtering
│       ├── PromotionEquivalentCalculator.java # Promotion value calculation
│       └── PromotionSavingsAnalyzer.java      # Savings analysis
├── billing-v3-spring-boot-starter/  # Spring Boot 3.0-3.4 starter
│   └── src/main/java/cn/shang/charging/spring/boot/autoconfigure/
│   └── src/main/resources/META-INF/spring/
├── billing-v4-spring-boot-starter/  # Spring Boot 3.5-4.x starter
│   └── src/main/java/cn/shang/charging/spring/boot/autoconfigure/
│   └── src/main/resources/META-INF/spring/
├── bill-test/                       # Test module
│   └── src/main/java/cn/shang/charging/  # Test classes (PromotionTest, etc.)
│   └── src/test/java/cn/shang/charging/  # Unit tests
├── pom.xml                          # Parent POM with shared dependencies
└── CLAUDE.md                        # Project documentation for Claude
```

## Directory Purposes

**core/src/main/java/cn/shang/charging/billing:**
- Purpose: Billing orchestration and pipeline control
- Contains: `BillingService`, `BillingCalculator`, `SegmentBuilder`, `CalculationWindowFactory`, `BillingConfigResolver`
- Key files: `BillingService.java` (pipeline orchestrator), `BillingCalculator.java` (rule delegation)

**core/src/main/java/cn/shang/charging/billing/pojo:**
- Purpose: Immutable data objects for billing pipeline
- Contains: `BillingRequest`, `BillingResult`, `BillingContext`, `BillingSegment`, `BillingUnit`, `BillingCarryOver`, `BConstants`
- Key files: `BillingRequest.java` (input), `BillingResult.java` (output), `BConstants.java` (enums and constants)

**core/src/main/java/cn/shang/charging/charge/rules:**
- Purpose: BillingRule interface and registry (strategy pattern)
- Contains: `BillingRule.java` (interface), `BillingRuleRegistry.java` (registry), `AbstractTimeBasedRule.java` (base class)
- Key files: `BillingRule.java` (strategy interface), `BillingRuleRegistry.java` (extensible registry)

**core/src/main/java/cn/shang/charging/charge/rules/daynight:**
- Purpose: Day/night time-based billing rule implementation
- Contains: `DayNightRule.java`, `DayNightConfig.java`
- Key files: `DayNightRule.java` (complex rule with daily cap, block weight logic)

**core/src/main/java/cn/shang/charging/promotion:**
- Purpose: Promotion engine and aggregation logic
- Contains: `PromotionEngine.java`, `FreeTimeRangeMerger.java`, `FreeMinuteAllocator.java`
- Key files: `PromotionEngine.java` (aggregation orchestrator)

**core/src/main/java/cn/shang/charging/promotion/pojo:**
- Purpose: Promotion-related data objects
- Contains: `FreeTimeRange`, `FreeMinutes`, `PromotionAggregate`, `PromotionUsage`, `PromotionGrant`
- Key files: `PromotionAggregate.java` (final promotion result)

**billing-api/src/main/java/cn/shang/charging/wrapper:**
- Purpose: Convenient API layer with view logic
- Contains: `BillingTemplate`, `BillingResultViewer`, `PromotionEquivalentCalculator`, `PromotionSavingsAnalyzer`
- Key files: `BillingTemplate.java` (main entry), `BillingResultViewer.java` (query-time filtering)

**bill-test/src/main/java/cn/shang/charging:**
- Purpose: Integration tests and functional verification
- Contains: `PromotionTest.java`, `DayNightTest.java`, `CompositeTimeTest.java`, `ContinueModeTest.java`, etc.
- Key files: Test classes serve as usage examples and verification

## Key File Locations

**Entry Points:**
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`: Core pipeline orchestrator
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`: Convenient API entry
- `billing-v*-spring-boot-starter/src/main/java/.../BillingAutoConfiguration.java`: Spring Boot bean config

**Configuration:**
- `pom.xml`: Maven dependencies (Lombok 1.18.34, JDK 21)
- `billing-v*-spring-boot-starter/src/main/java/.../BillingProperties.java`: Spring Boot properties

**Core Logic:**
- `core/src/main/java/cn/shang/charging/billing/BillingCalculator.java`: Rule execution
- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`: Promotion aggregation
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`: Result combination

**Rule Implementations:**
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`: Day/night pricing
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`: Relative time periods
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`: Mixed time rules

**Testing:**
- `bill-test/src/main/java/cn/shang/charging/PromotionTest.java`: Promotion scenarios
- `bill-test/src/main/java/cn/shang/charging/DayNightTest.java`: Day/night rule tests
- `bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java`: CONTINUE mode tests

## Naming Conventions

**Files:**
- POJO classes: `BillingRequest.java`, `BillingResult.java`, `BillingUnit.java` (descriptive nouns)
- Service classes: `BillingService.java`, `BillingCalculator.java` (verb+noun or noun+noun)
- Rule classes: `DayNightRule.java`, `RelativeTimeRule.java` (feature+Rule suffix)
- Config classes: `DayNightConfig.java`, `RelativeTimeConfig.java` (feature+Config suffix)
- Registry classes: `BillingRuleRegistry.java`, `PromotionRuleRegistry.java` (type+Registry suffix)

**Directories:**
- Package by feature: `daynight/`, `relativetime/`, `compositetime/`, `minutes/`
- Package by layer: `billing/`, `charge/`, `promotion/`, `settlement/`, `wrapper/`
- POJO separation: `pojo/` subdirectory within each layer

**Classes:**
- Interfaces: `BillingRule`, `PromotionRule`, `BillingConfigResolver` (noun or noun+Verb)
- Abstract classes: `AbstractTimeBasedRule` (Abstract prefix)
- Registries: `BillingRuleRegistry`, `PromotionRuleRegistry` (type+Registry)
- Builders: `SegmentBuilder` (noun+Builder)

## Where to Add New Code

**New Billing Rule:**
- Implementation: Create class in `core/src/main/java/cn/shang/charging/charge/rules/<feature>/`
- Config POJO: Create config class in same package implementing `RuleConfig`
- Registration: Add to `BillingRuleRegistry.init()` or register via Spring Boot starter
- Test: Create test class in `bill-test/src/main/java/cn/shang/charging/`

**New Promotion Rule:**
- Implementation: Create class in `core/src/main/java/cn/shang/charging/promotion/rules/<type>/`
- Config POJO: Extend `PromotionRuleConfig`
- Registration: Add to `PromotionRuleRegistry` via Spring Boot starter

**New POJO:**
- Billing-related: `core/src/main/java/cn/shang/charging/billing/pojo/`
- Promotion-related: `core/src/main/java/cn/shang/charging/promotion/pojo/`
- Charge-related: `core/src/main/java/cn/shang/charging/charge/pojo/`

**New API Wrapper:**
- Location: `billing-api/src/main/java/cn/shang/charging/wrapper/`
- Inject `BillingService` or use existing wrappers

**New Test:**
- Integration test: `bill-test/src/main/java/cn/shang/charging/` (main sources used as test entry)
- Unit test: `bill-test/src/test/java/cn/shang/charging/`

**New Spring Boot Configuration:**
- Properties: `billing-v*-spring-boot-starter/src/main/java/.../BillingProperties.java`
- Auto-config: `billing-v*-spring-boot-starter/src/main/java/.../BillingAutoConfiguration.java`

## Special Directories

**.planning/codebase:**
- Purpose: Codebase analysis documents for GSD workflow
- Generated: Yes (by `/gsd:map-codebase` command)
- Committed: Yes (part of project documentation)

**docs/superpowers:**
- Purpose: GSD workflow documents (plans, specs, archive)
- Generated: Yes (by GSD commands)
- Committed: Yes

**target (each module):**
- Purpose: Maven build output
- Generated: Yes (by `mvn clean install`)
- Committed: No (in `.gitignore`)

---

*Structure analysis: 2026-03-30*