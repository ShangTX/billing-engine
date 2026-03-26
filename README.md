# Billing Engine

[中文文档](README_CN.md)

An extensible, traceable, composable time-based billing engine for scenarios like venue usage fees, equipment rentals, and service billing.

## Features

- **Extensible Rules**: Add new billing rules without modifying the core engine
- **Traceable Process**: Complete billing details for auditing and debugging
- **Continue Calculation**: Support incremental billing from previous results
- **Flexible Promotions**: Free time ranges, free minutes, and more

## Requirements

- JDK 21+
- Maven 3.6+

## Quick Start

### Add Dependency

#### Option 1: billing-api (Recommended)

Provides `BillingTemplate` with advanced features like query-time calculation and promotion equivalents.

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>1.0.2</version>
</dependency>
```

#### Option 2: Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>
```

### Basic Usage

```java
// 1. Implement BillingConfigResolver
public class MyBillingConfigResolver implements BillingConfigResolver {
    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd) {
        return new DayNightConfig()
            .setId("daynight-1")
            .setDayBeginMinute(740)              // Day starts: 12:20
            .setDayEndMinute(1140)               // Day ends: 19:00
            .setDayUnitPrice(new BigDecimal("2")) // Day price: 2/hour
            .setNightUnitPrice(new BigDecimal("1")) // Night price: 1/hour
            .setMaxChargeOneDay(new BigDecimal("50")) // Daily cap: 50
            .setUnitMinutes(60)
            .setBlockWeight(new BigDecimal("0.5"));
    }
    // ... other methods
}

// 2. Create BillingTemplate
BillingConfigResolver configResolver = new MyBillingConfigResolver();
BillingService billingService = BillingServiceFactory.create(configResolver);
BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);

// 3. Calculate fees
BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

## API Reference

### Core Service Classes

#### BillingService (cn.shang.charging.billing)

Core billing service that executes the complete billing calculation pipeline.

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `calculate` | `BillingRequest request` | `BillingResult` | Execute billing calculation |
| `prepareContexts` | `BillingRequest request` | `List<SegmentContext>` | Prepare segment contexts (FROM_SCRATCH mode only) |
| `calculateWithContexts` | `List<SegmentContext> contexts, BillingRequest request` | `BillingResult` | Calculate with segment contexts |

#### BillingTemplate (cn.shang.charging.wrapper)

Convenient API wrapper with advanced features.

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `calculate` | `BillingRequest request` | `BillingResult` | Basic billing calculation |
| `calculateWithQuery` | `BillingRequest request, LocalDateTime queryTime` | `CalculationWithQueryResult` | Calculate and return fee status at specified time |
| `calculatePromotionEquivalents` | `BillingRequest request` | `Map<String, BigDecimal>` | Calculate equivalent amount for each promotion |
| `calculatePromotionSavings` | `BillingResult result` | `Map<String, BigDecimal>` | Analyze promotion savings |
| `getConfigResolver` | - | `BillingConfigResolver` | Get config resolver |

#### BillingConfigResolver (cn.shang.charging.billing)

Billing config resolver interface that users must implement.

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `resolveBillingMode` | `String schemeId` | `BConstants.BillingMode` | Get billing mode |
| `resolveChargingRule` | `String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd` | `RuleConfig` | Get charging rule config |
| `resolvePromotionRules` | `String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd` | `List<PromotionRuleConfig>` | Get promotion rule configs |
| `getSimplifiedCycleThreshold` | - | `int` | Simplified calculation threshold, default 0 disabled |

---

### Request and Result Classes

#### BillingRequest (cn.shang.charging.billing.pojo)

Billing request input.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | No | Request unique identifier |
| `beginTime` | LocalDateTime | **Yes** | Billing start time |
| `endTime` | LocalDateTime | **Yes** | Billing end time |
| `queryTime` | LocalDateTime | No | Query time point (for result clipping) |
| `calcEndTime` | LocalDateTime | No | Calculation end time (for controlling progress) |
| `schemeId` | String | Conditional | Billing scheme ID (alternative to `schemeChanges`) |
| `schemeChanges` | List\<SchemeChange\> | Conditional | Scheme change timeline |
| `segmentCalculationMode` | SegmentCalculationMode | **Yes** | Segment calculation mode |
| `externalPromotions` | List\<PromotionGrant\> | No | External promotion list |
| `previousCarryOver` | BillingCarryOver | No | Previous carry-over state (CONTINUE mode) |

#### BillingResult (cn.shang.charging.billing.pojo)

Billing result output.

| Field | Type | Description |
|-------|------|-------------|
| `units` | List\<BillingUnit\> | Billing unit details |
| `promotionUsages` | List\<PromotionUsage\> | Promotion usage records |
| `settlementAdjustments` | List\<SettlementAdjustment\> | Settlement adjustment records |
| `finalAmount` | BigDecimal | Final chargeable amount |
| `effectiveFrom` | LocalDateTime | Price effective start time |
| `effectiveTo` | LocalDateTime | Price effective end time |
| `calculationEndTime` | LocalDateTime | Actual calculation end time (after extension) |
| `carryOver` | BillingCarryOver | Carry-over state for next calculation |

#### BillingUnit (cn.shang.charging.billing.pojo)

Billing unit details.

| Field | Type | Description |
|-------|------|-------------|
| `beginTime` | LocalDateTime | Unit start time |
| `endTime` | LocalDateTime | Unit end time |
| `durationMinutes` | int | Unit duration (minutes) |
| `unitPrice` | BigDecimal | Unit price |
| `originalAmount` | BigDecimal | Original amount (before promotion) |
| `chargedAmount` | BigDecimal | Actual amount (after promotion) |
| `free` | boolean | Whether free (fully covered by promotion) |
| `freePromotionId` | String | Free reason (promotion ID) |
| `isTruncated` | Boolean | Whether truncated by calcEndTime |
| `ruleData` | Object | Rule extension data |

#### BillingSegmentResult (cn.shang.charging.billing.pojo)

Segment billing result.

| Field | Type | Description |
|-------|------|-------------|
| `segmentId` | String | Segment ID |
| `segmentStartTime` | LocalDateTime | Segment logical start time |
| `segmentEndTime` | LocalDateTime | Segment logical end time |
| `calculationStartTime` | LocalDateTime | Actual calculation start time |
| `calculationEndTime` | LocalDateTime | Actual calculation end time |
| `chargedAmount` | BigDecimal | Segment chargeable amount |
| `chargedDuration` | Integer | Segment actual billing duration (minutes) |
| `feeEffectiveStart` | LocalDateTime | Fee certainty start time |
| `feeEffectiveEnd` | LocalDateTime | Fee stability end time |
| `promotionAggregate` | PromotionAggregate | Promotion aggregate result |
| `billingUnits` | List\<BillingUnit\> | Billing unit details |
| `carryOverAfter` | BillingCarryOver | Carry-over state |
| `ruleOutputState` | Map\<String, Object\> | Rule output state |
| `promotionUsages` | List\<PromotionUsage\> | Promotion usage records |

---

### Carry-over State Classes

#### BillingCarryOver (cn.shang.charging.billing.pojo)

Top-level carry-over object for CONTINUE mode.

| Field | Type | Description |
|-------|------|-------------|
| `calculatedUpTo` | LocalDateTime | Calculated up to this time point |
| `segments` | Map\<String, SegmentCarryOver\> | Carry-over state by segment ID |

#### SegmentCarryOver (cn.shang.charging.billing.pojo)

Segment-level carry-over state.

| Field | Type | Description |
|-------|------|-------------|
| `ruleState` | Map\<String, Object\> | Rule state (key: rule type) |
| `promotionState` | PromotionCarryOver | Promotion carry-over state |

#### PromotionCarryOver (cn.shang.charging.billing.pojo)

Promotion carry-over state.

| Field | Type | Description |
|-------|------|-------------|
| `remainingMinutes` | Map\<String, Integer\> | Remaining free minutes (key: promotionId) |
| `usedFreeRanges` | List\<FreeTimeRange\> | Used free time ranges |

---

### Promotion Related Classes

#### PromotionGrant (cn.shang.charging.promotion.pojo)

Calculable promotion input.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Promotion ID |
| `type` | PromotionType | Promotion type |
| `source` | PromotionSource | Promotion source |
| `beginTime` | LocalDateTime | Time range start (FREE_RANGE type) |
| `endTime` | LocalDateTime | Time range end (FREE_RANGE type) |
| `freeMinutes` | Integer | Free minutes (FREE_MINUTES type) |
| `priority` | Integer | Priority |
| `rangeType` | FreeTimeRangeType | Free time range type |

#### FreeTimeRange (cn.shang.charging.promotion.pojo)

Free time range.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique identifier |
| `beginTime` | LocalDateTime | Start time |
| `endTime` | LocalDateTime | End time |
| `priority` | int | Priority |
| `promotionType` | PromotionType | Promotion type |
| `rangeType` | FreeTimeRangeType | Range type (NORMAL/BUBBLE) |
| `data` | Object | Extension data |

| Method | Return | Description |
|--------|--------|-------------|
| `isValid()` | boolean | Check if time range is valid |
| `overlaps(FreeTimeRange other)` | boolean | Check if overlaps |
| `getOverlap(FreeTimeRange other)` | FreeTimeRange | Get overlap portion |
| `copy()` | FreeTimeRange | Copy |
| `copyWithNewId()` | FreeTimeRange | Copy with new ID |

#### PromotionUsage (cn.shang.charging.promotion.pojo)

Promotion usage record.

| Field | Type | Description |
|-------|------|-------------|
| `promotionId` | String | Promotion source ID |
| `type` | PromotionType | Promotion type |
| `grantedMinutes` | long | Granted minutes |
| `usedMinutes` | long | Used minutes |
| `usedFrom` | LocalDateTime | Usage start time |
| `usedTo` | LocalDateTime | Usage end time |
| `equivalentAmount` | BigDecimal | Equivalent promotion amount |

#### PromotionAggregate (cn.shang.charging.promotion.pojo)

Promotion aggregate result.

| Field | Type | Description |
|-------|------|-------------|
| `freeTimeRanges` | List\<FreeTimeRange\> | Free time range list |
| `freeMinutes` | long | Total free minutes |
| `usages` | List\<PromotionUsage\> | Usage statistics |
| `equivalentAmount` | BigDecimal | Equivalent amount |
| `promotionCarryOver` | PromotionCarryOver | Promotion carry-over output |

| Method | Return | Description |
|--------|--------|-------------|
| `isEmpty()` | boolean | Whether no promotion |
| `hasMultiplePromotionTypes()` | boolean | Whether has multiple promotion types |
| `hasSinglePromotionType()` | boolean | Whether has single promotion type |

#### TimeRangeMergeResult (cn.shang.charging.promotion.pojo)

Time range merge result.

| Field | Type | Description |
|-------|------|-------------|
| `mergedRanges` | List\<FreeTimeRange\> | Merged time ranges |
| `discardedRanges` | List\<FreeTimeRange\> | Discarded time ranges |
| `originalToDiscarded` | Map\<String, List\<FreeTimeRange\>\> | Original to discarded mapping |

| Method | Parameters | Return | Description |
|--------|------------|--------|-------------|
| `addMergedRange` | `FreeTimeRange range` | void | Add merged time range |
| `addDiscardedRange` | `FreeTimeRange range` | void | Add discarded time range |
| `addDiscardedRanges` | `List<FreeTimeRange> ranges` | void | Batch add discarded time ranges |
| `getDiscardedParts` | `String originalId` | List\<FreeTimeRange\> | Get discarded parts |
| `getRemainingParts` | `String originalId` | List\<FreeTimeRange\> | Get remaining parts |

---

### Configuration Interfaces

#### RuleConfig (cn.shang.charging.billing.pojo)

Billing rule configuration interface.

| Method | Return | Description |
|--------|--------|-------------|
| `getId()` | String | Get rule ID |
| `getType()` | String | Get rule type |
| `getSimplifiedSupported()` | Boolean | Whether supports simplified calculation (default null means supported) |

#### PromotionRuleConfig (cn.shang.charging.billing.pojo)

Promotion rule configuration interface.

| Method | Return | Description |
|--------|--------|-------------|
| `getId()` | String | Get rule ID |
| `getType()` | String | Get rule type |
| `getPriority()` | Integer | Get priority |

---

### Rule Configuration Implementation Classes

#### DayNightConfig (cn.shang.charging.charge.rules.daynight)

Day-night time-based billing rule configuration.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Rule ID |
| `type` | String | Rule type (default "dayNight") |
| `dayBeginMinute` | Integer | Day start minute (0 for midnight) |
| `dayEndMinute` | Integer | Day end minute |
| `unitMinutes` | Integer | Billing unit length (minutes) |
| `blockWeight` | BigDecimal | Day-night judgment weight |
| `dayUnitPrice` | BigDecimal | Day unit price |
| `nightUnitPrice` | BigDecimal | Night unit price |
| `maxChargeOneDay` | BigDecimal | Daily cap amount |
| `simplifiedSupported` | Boolean | Whether supports simplified calculation |

#### RelativeTimeConfig (cn.shang.charging.charge.rules.relativetime)

Relative time period billing rule configuration.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Rule ID |
| `type` | String | Rule type (default "relativeTime") |
| `periods` | List\<RelativeTimePeriod\> | Time period list |
| `maxChargeOneCycle` | BigDecimal | Cycle cap amount |
| `simplifiedSupported` | Boolean | Whether supports simplified calculation |

#### RelativeTimePeriod (cn.shang.charging.charge.rules.relativetime)

Relative time period definition.

| Field | Type | Description |
|-------|------|-------------|
| `beginMinute` | int | Relative start minute (0-1439) |
| `endMinute` | int | Relative end minute (1-1440) |
| `unitMinutes` | int | Billing unit length (minutes) |
| `unitPrice` | BigDecimal | Unit price |

#### CompositeTimeConfig (cn.shang.charging.charge.rules.compositetime)

Composite time billing rule configuration.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Rule ID |
| `type` | String | Rule type (default "compositeTime") |
| `periods` | List\<CompositePeriod\> | Relative time period list |
| `maxChargeOneCycle` | BigDecimal | Cycle cap amount |
| `insufficientUnitMode` | InsufficientUnitMode | Insufficient unit billing mode |
| `simplifiedSupported` | Boolean | Whether supports simplified calculation |

#### CompositePeriod (cn.shang.charging.charge.rules.compositetime)

Relative time period configuration (composite time rule).

| Field | Type | Description |
|-------|------|-------------|
| `beginMinute` | int | Relative start minute (0-1440) |
| `endMinute` | int | Relative end minute |
| `unitMinutes` | int | Billing unit length (minutes) |
| `maxCharge` | BigDecimal | Period independent cap (optional) |
| `crossPeriodMode` | CrossPeriodMode | Cross natural period handling mode |
| `naturalPeriods` | List\<NaturalPeriod\> | Natural period price list |

#### NaturalPeriod (cn.shang.charging.charge.rules.compositetime)

Natural period configuration.

| Field | Type | Description |
|-------|------|-------------|
| `beginMinute` | int | Natural period start minute |
| `endMinute` | int | Natural period end minute |
| `unitPrice` | BigDecimal | Unit price |

#### FreeMinutesPromotionConfig (cn.shang.charging.promotion.rules.minutes)

Free minutes promotion rule configuration.

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Rule ID |
| `type` | String | Rule type (default "freeMinutes") |
| `priority` | Integer | Priority |
| `minutes` | int | Free minutes |

#### InsufficientUnitMode (cn.shang.charging.charge.rules.compositetime)

Insufficient unit billing mode enum.

| Value | Description |
|-------|-------------|
| `FULL` | Full charge |
| `PROPORTIONAL` | Proportional charge |

#### CrossPeriodMode (cn.shang.charging.charge.rules.compositetime)

Cross natural period handling mode enum.

| Value | Description |
|-------|-------------|
| `BLOCK_WEIGHT` | Determine price by time ratio |
| `HIGHER_PRICE` | Use higher price |
| `LOWER_PRICE` | Use lower price |
| `PROPORTIONAL` | Calculate proportionally |
| `BEGIN_TIME_PRICE` | Use begin time period price |
| `END_TIME_PRICE` | Use end time period price |
| `BEGIN_TIME_TRUNCATE` | Use begin time price, truncate by natural period boundary |

---

### Constants and Enums

#### BConstants (cn.shang.charging.billing.pojo)

##### ContinueMode Enum

| Value | Description |
|-------|-------------|
| `FROM_SCRATCH` | Calculate from start time |
| `CONTINUE` | Continue from previous result |

##### BillingMode Enum

| Value | Description |
|-------|-------------|
| `CONTINUOUS` | Continuous time billing mode |
| `UNIT_BASED` | Billing unit mode |

##### PromotionType Enum

| Value | Description |
|-------|-------------|
| `AMOUNT` | Amount deduction (pending) |
| `DISCOUNT` | Discount promotion (pending) |
| `FREE_RANGE` | Free time range |
| `FREE_MINUTES` | Free minutes |

##### PromotionSource Enum

| Value | Description |
|-------|-------------|
| `RULE` | Rule promotion |
| `COUPON` | Coupon |

##### SegmentCalculationMode Enum

| Value | Description |
|-------|-------------|
| `SINGLE` | Single segment only |
| `SEGMENT_LOCAL` | Segment independent calculation |
| `GLOBAL_ORIGIN` | Global origin + segment clipping |

##### ChargeRuleType Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `DAY_NIGHT` | "dayNight" | Day-night time billing |
| `TIMES` | "times" | By times |
| `NATURAL_TIME` | "naturalTime" | By natural time period |
| `RELATIVE_TIME` | "relativeTime" | By relative time period |
| `NR_TIME_MIX` | "nrTimeMix" | Natural-relative time mix |
| `COMPOSITE_TIME` | "compositeTime" | Composite time billing |

##### PromotionRuleType Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `FREE_MINUTES` | "freeMinutes" | Free minutes rule |

#### FreeTimeRangeType (cn.shang.charging.promotion.pojo)

| Value | Description |
|-------|-------------|
| `NORMAL` | Normal free time range, does not affect cycle boundary |
| `BUBBLE` | Bubble free time range, extends billing cycle boundary |

---

### Other Classes

#### SchemeChange (cn.shang.charging.billing.pojo)

Scheme switch record.

| Field | Type | Description |
|-------|------|-------------|
| `lastSchemeId` | String | Previous scheme ID |
| `nextSchemeId` | String | Next scheme ID |
| `changeTime` | LocalDateTime | Change time |

#### CalculationWithQueryResult (cn.shang.charging.wrapper)

Calculation result and query result.

| Field | Type | Description |
|-------|------|-------------|
| `calculationResult` | BillingResult | Complete calculation result (for CONTINUE progress storage) |
| `queryResult` | BillingResult | Query time point result (for display) |

## Billing Modes

| Mode | Description |
|------|-------------|
| `CONTINUOUS` | Continuous time billing, time can be interrupted by promotions |
| `UNIT_BASED` | Independent calculation by billing unit |

## Promotion Types

| Type | Description |
|------|-------------|
| `FREE_MINUTES` | Free minutes, intelligently allocated to optimal time slots |
| `FREE_RANGE` | Free time range |
| `AMOUNT` | Amount deduction (pending) |
| `DISCOUNT` | Discount promotion (pending) |

## Continue Calculation Mode

Supports incremental calculation for long-term billing scenarios:

```java
// First calculation
BillingResult result1 = billingTemplate.calculate(request1);

// Continue calculation
BillingRequest request2 = new BillingRequest();
request2.setBeginTime(beginTime);
request2.setEndTime(secondQueryTime);
request2.setPreviousCarryOver(result1.getCarryOver());

BillingResult result2 = billingTemplate.calculate(request2);
```

## Rule Configuration

### DayNightConfig (Day-Night Time)

```java
DayNightConfig config = new DayNightConfig()
    .setId("daynight-1")
    .setDayBeginMinute(740)                  // 12:20
    .setDayEndMinute(1140)                   // 19:00
    .setDayUnitPrice(new BigDecimal("2"))
    .setNightUnitPrice(new BigDecimal("1"))
    .setMaxChargeOneDay(new BigDecimal("50"))
    .setUnitMinutes(60)
    .setBlockWeight(new BigDecimal("0.5"));
```

### RelativeTimeConfig (Relative Time)

```java
RelativeTimeConfig config = new RelativeTimeConfig()
    .setId("relative-1")
    .setUnitMinutes(60)
    .setMaxChargeOneDay(new BigDecimal("30"))
    .setPeriods(List.of(
        new RelativeTimePeriod()
            .setStartMinute(0)
            .setEndMinute(720)
            .setUnitPrice(new BigDecimal("1")),
        new RelativeTimePeriod()
            .setStartMinute(720)
            .setEndMinute(1440)
            .setUnitPrice(new BigDecimal("2"))
    ));
```

### CompositeTimeConfig (Composite Time)

```java
CompositeTimeConfig config = new CompositeTimeConfig()
    .setId("composite-1")
    .setBillingMode(BConstants.BillingMode.CONTINUOUS)
    .setPeriods(List.of(
        new CompositePeriod()
            .setId("daytime")
            .setType("natural")
            .setNaturalPeriod(NaturalPeriod.DAY)
            .setUnitMinutes(60)
            .setUnitPrice(new BigDecimal("2")),
        new CompositePeriod()
            .setId("nighttime")
            .setType("natural")
            .setNaturalPeriod(NaturalPeriod.NIGHT)
            .setUnitMinutes(60)
            .setUnitPrice(new BigDecimal("1"))
    ));
```

### FreeMinutesPromotionConfig (Free Minutes Promotion)

```java
FreeMinutesPromotionConfig promoConfig = new FreeMinutesPromotionConfig()
    .setId("free-minutes-1")
    .setMinutes(30)        // 30 minutes free
    .setPriority(100);     // Higher priority applied first
```

## Custom Rules

### 1. Implement Rule Configuration

```java
@Data
public class MyRuleConfig implements RuleConfig {
    private String id;
    private BigDecimal unitPrice;
    private int unitMinutes;

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "myRule"; }
}
```

### 2. Implement Rule Logic

```java
public class MyBillingRule implements BillingRule<MyRuleConfig> {
    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          MyRuleConfig config,
                                          PromotionAggregate promotionAggregate) {
        // Implement billing logic
    }

    @Override
    public Class<MyRuleConfig> configClass() {
        return MyRuleConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.CONTINUOUS);
    }
}
```

### 3. Register Rule

```java
ruleRegistry.register("myRule", new MyBillingRule());
```

## Module Structure

| Module | Description |
|--------|-------------|
| `billing-core` | Core billing engine with pure calculation logic |
| `billing-api` | Convenient API wrapper (recommended) |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0-3.4 integration |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5-4.x integration |

## Design Principles

1. **Core engine only calculates** — No caching, no database, no side effects
2. **Rules are pure functions** — Same input always produces same output
3. **Rules don't depend on each other** — All rules executed through Engine
4. **Configuration separated from implementation** — RuleConfig describes parameters, BillingRule handles calculation

## License

MIT License