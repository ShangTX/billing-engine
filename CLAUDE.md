# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build all modules
mvn clean install

# Build single module
mvn clean install -pl core
mvn clean install -pl bill-test

# Run test (main entry point for testing)
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.PromotionTest"
```

## Architecture

This is a parking billing system with a modular Maven multi-module structure:

- **charge** (parent) - Root POM with shared dependencies (Lombok, Apache Commons, Jackson)
- **core** - Core billing engine and business logic
- **bill-test** - Test module that depends on core, contains integration tests

### Core Architecture (core module)

The billing calculation follows a pipeline architecture:

```
BillingService.calculate()
├── SegmentBuilder.buildSegments()     # Split by billing scheme changes
├── RuleResolver                       # Resolve charging & promotion rules per segment
├── PromotionEngine.evaluate()         # Aggregate free time ranges & minutes
├── BillingCalculator.calculate()      # Apply billing rules with promotions
└── ResultAssembler.assemble()         # Combine segment results
```

### Key Components

**Billing Flow:**
- `BillingService` - Orchestrates the billing calculation pipeline
- `SegmentBuilder` - Splits time range into segments based on `SchemeChange` events
- `RuleResolver` - Interface for resolving `RuleConfig` and `PromotionRuleConfig` by schemeId + time range
- `PromotionEngine` - Aggregates promotions from rules and external sources (coupons)
- `BillingCalculator` - Delegates to registered `BillingRule` implementations
- `ResultAssembler` - Combines segment results into final `BillingResult`

**Rule System:**
- `BillingRuleRegistry` / `PromotionRuleRegistry` - Strategy pattern registries
- Rules are resolved by type string constants in `BConstants.ChargeRuleType` / `PromotionRuleType`
- Built-in rules: `DayNightRule` (charges by day/night time periods), `FreeMinutesPromotionRule`, `FreeTimeRangePromotionRule`

**Promotion Types:**
- `FREE_RANGE` - Free time periods (e.g., 01:00-04:00 is free)
- `FREE_MINUTES` - Free minutes allocated optimally within the time window
- Promotions can come from rules (scheme-based) or external sources (coupons)
- Priority-based resolution when multiple promotions apply

**Segment Calculation Modes (`SegmentCalculationMode`):**
- `SINGLE` - Only single segment calculation
- `SEGMENT_LOCAL` - Each segment calculates from its own start time
- `GLOBAL_ORIGIN` - Global time window with segment截取

### Data Model

Key POJOs in `billing.pojo` and `promotion.pojo`:
- `BillingRequest` - Input with time range, schemeId/schemeChanges, external promotions
- `BillingResult` - Output with billing units, promotion usages, final amount
- `BillingContext` - Immutable context holding segment, window, rules for calculation
- `PromotionAggregate` - Aggregated free time ranges and usage tracking

### Implementation Notes

- `ChargingEngine` is a placeholder (returns null)
- Rule implementations use generic type safety with `configClass()` for type checking
- Lombok used extensively for POJOs (`@Data`, `@Builder`, `@AllArgsConstructor`, `@Accessors(chain=true)`)

### DayNightRule Implementation

The `DayNightRule` implements time-based billing with:
- **24-hour billing cycle** starting from the billing begin time
- **Day/night pricing**: Different unit prices for day period (e.g., 12:20-19:00) vs night period
- **Daily cap**: Maximum charge per 24-hour cycle
- **Block weight threshold**: When a billing unit spans both day and night, use day price if day minutes ratio >= blockWeight (0.5), otherwise use night price
- **Free time ranges**: Promotions that completely cover a billing unit make it free
- **Daily cap handling**: Units after reaching the daily cap are marked as free with `freePromotionId="DAILY_CAP"`
