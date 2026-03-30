# Architecture

**Analysis Date:** 2026-03-30

## Pattern Overview

**Overall:** Pipeline Architecture with Strategy Pattern

**Key Characteristics:**
- Modular Maven multi-module structure with clear layer separation
- Strategy pattern for rule implementations (extensible without modifying core)
- Immutable context objects for deterministic calculation
- Pure calculation logic with no side effects in core module
- Carry-over state pattern for supporting CONTINUE mode (incremental calculation)

## Layers

**Core Layer (`core` module):**
- Purpose: Pure billing calculation logic with no external dependencies
- Location: `core/src/main/java/cn/shang/charging/`
- Contains: Billing engine, rule implementations, promotion engine, POJOs
- Depends on: Only Lombok (for POJO generation), no database/external APIs
- Used by: `billing-api` module, Spring Boot starters

**API Layer (`billing-api` module):**
- Purpose: Convenient API wrapper, view-layer logic, higher-level abstractions
- Location: `billing-api/src/main/java/cn/shang/charging/wrapper/`
- Contains: `BillingTemplate`, `BillingResultViewer`, `PromotionEquivalentCalculator`
- Depends on: `core` module
- Used by: Spring Boot starters, application code

**Integration Layer (`billing-v*-spring-boot-starter` modules):**
- Purpose: Spring Boot auto-configuration and bean registration
- Location: `billing-v3-spring-boot-starter/src/main/java/`, `billing-v4-spring-boot-starter/src/main/java/`
- Contains: `BillingAutoConfiguration`, `BillingProperties`
- Depends on: `billing-api` module, Spring Boot framework
- Used by: Application code via Spring dependency injection

**Test Layer (`bill-test` module):**
- Purpose: Integration tests, functional verification, example usage
- Location: `bill-test/src/main/java/cn/shang/charging/`
- Contains: Test classes (`PromotionTest`, `DayNightTest`, `CompositeTimeTest`, etc.)
- Depends on: `core` module, `billing-api` module
- Used by: Development verification

## Data Flow

**Billing Calculation Pipeline:**

1. `BillingService.calculate()` receives `BillingRequest`
2. `SegmentBuilder.buildSegments()` splits time range into segments based on `SchemeChange` events
3. For each segment:
   - `CalculationWindowFactory.create()` builds calculation window (handles SEGMENT_LOCAL/GLOBAL_ORIGIN modes)
   - `BillingConfigResolver.resolveChargingRule()` and `resolvePromotionRules()` fetch rule configurations
   - `PromotionEngine.evaluate()` aggregates free time ranges and free minutes from rules and external promotions
   - `BillingCalculator.calculate()` delegates to registered `BillingRule` implementation
4. `ResultAssembler.assemble()` combines segment results into final `BillingResult`

**State Management:**
- Immutable `BillingContext` holds all inputs for a single segment calculation
- `BillingCarryOver` captures state for CONTINUE mode (next calculation starts from `calculatedUpTo`)
- Each segment produces `BillingSegmentResult` with `ruleOutputState` and `promotionCarryOver`

**Promotion Flow:**

1. `PromotionEngine.evaluate()` receives `BillingContext`
2. Collect promotions from two sources:
   - Rule-based: `PromotionRule.grant()` for each `PromotionRuleConfig`
   - External: `BillingRequest.externalPromotions` (coupons)
3. `FreeTimeRangeMerger.merge()` merges overlapping free time ranges
4. `FreeMinuteAllocator.allocate()` converts free minutes into optimal time ranges
5. Output `PromotionAggregate` with final free time ranges and usage tracking

## Key Abstractions

**BillingRule Interface:**
- Purpose: Strategy pattern for charging rule implementations
- Examples: `DayNightRule`, `RelativeTimeRule`, `CompositeTimeRule` in `core/src/main/java/cn/shang/charging/charge/rules/`
- Pattern: Generic interface `BillingRule<C extends RuleConfig>` with `calculate()`, `configClass()`, `supportedModes()`
- Registry: `BillingRuleRegistry` holds rule implementations, new rules register via `register()` method

**PromotionRule Interface:**
- Purpose: Strategy pattern for promotion rule implementations
- Examples: `FreeMinutesPromotionRule` in `core/src/main/java/cn/shang/charging/promotion/rules/minutes/`
- Pattern: Generic interface `PromotionRule<T extends PromotionRuleConfig>` with `grant()`, `getConfigClass()`
- Registry: `PromotionRuleRegistry` holds promotion rule implementations

**AbstractTimeBasedRule:**
- Purpose: Base class for time-based billing rules with shared logic
- Examples: `DayNightRule` extends `AbstractTimeBasedRule<DayNightConfig>`
- Pattern: Template method pattern with `getRuleType()`, `hasComplexFeatures()`, `isSimplifiedSupported()`, `getCycleCapAmount()`
- Features: State management (`RuleState`), simplification framework, carry-over support

**BillingConfigResolver Interface:**
- Purpose: Abstract configuration resolution (scheme-based, time-range aware)
- Examples: Implementation provided by application layer (database/config file lookup)
- Pattern: Interface with `resolveChargingRule()`, `resolvePromotionRules()`, `resolveBillingMode()`
- Extension: Methods accept `Map<String, Object> context` for flexible parameter passing

## Entry Points

**Primary Entry Point:**
- Location: `BillingService.calculate(BillingRequest)` in `core/src/main/java/cn/shang/charging/billing/BillingService.java`
- Triggers: Application code directly or via `BillingTemplate.calculate()`
- Responsibilities: Orchestrates the full billing pipeline

**Convenient API Entry:**
- Location: `BillingTemplate.calculate(BillingRequest)` in `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
- Triggers: Application code via Spring Boot starter or direct instantiation
- Responsibilities: Time rounding, wraps `BillingService`, provides `calculateWithQuery()`, `calculatePromotionEquivalents()`

**Spring Boot Auto-configuration:**
- Location: `BillingAutoConfiguration` in `billing-v*-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingAutoConfiguration.java`
- Triggers: Spring Boot application startup
- Responsibilities: Creates `BillingService`, `BillingTemplate`, registries, and engine beans

**Rule Execution Entry:**
- Location: `BillingRule.calculate(BillingContext, RuleConfig, PromotionAggregate)` in `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`
- Triggers: `BillingCalculator.calculate()` delegates based on rule type
- Responsibilities: Rule-specific billing unit generation, cap application, free time handling

## Error Handling

**Strategy:** Runtime exceptions with descriptive messages

**Patterns:**
- Rule not found: `throw new RuntimeException("No billing rule found for type: " + ruleConfig.getType())`
- Config mismatch: `throw new IllegalStateException("RuleConfig mismatch, rule=..., config=...")`
- Billing mode unsupported: `throw new IllegalStateException("Rule ... does not support billing mode: ...")`
- Config validation: `throw new IllegalArgumentException("maxChargeOneDay is required")` in `validateConfig()`

**Design Principle:** Errors are thrown immediately; no silent failures or null returns for invalid inputs

## Cross-Cutting Concerns

**Logging:** None in core module (pure calculation). Application layer handles logging.

**Validation:**
- Input validation in rule implementations (`validateConfig()` methods)
- Type safety via generic interfaces (`configClass()` method for type checking)
- Billing mode support check via `supportedModes()` method

**Authentication:** Not applicable - core module has no external dependencies

**State Serialization:**
- `RuleState` serialized to `Map<String, Object>` for JSON compatibility
- `TypeConversionUtil` handles type conversion (String/Double to BigDecimal, String to LocalDateTime)

**Time Handling:**
- `TimeRoundingMode` enum for handling seconds in begin/end times
- `BillingTemplate.applyTimeRounding()` implements truncation and ceiling logic

---

*Architecture analysis: 2026-03-30*