# Billing Engine

[中文文档](README_CN.md)

A extensible, traceable, composable time-based billing engine for scenarios like venue usage fees, equipment rentals, and service billing.

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
    <version>1.0.1</version>
</dependency>
```

#### Option 2: Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>1.0.1</version>
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

// 3. Calculate
BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

## API Reference

### BillingTemplate Methods

| Method | Description |
|--------|-------------|
| `calculate(request)` | Basic billing calculation |
| `calculateWithQuery(request, queryTime)` | Calculate and return state at specific time |
| `calculatePromotionEquivalents(request)` | Calculate equivalent amount for each promotion |

### Input: BillingRequest

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `beginTime` | LocalDateTime | **Yes** | Billing start time |
| `endTime` | LocalDateTime | **Yes** | Billing end time |
| `schemeId` | String | Conditional | Billing scheme ID (or use `schemeChanges`) |
| `schemeChanges` | List\<SchemeChange\> | Conditional | Scheme change timeline |
| `segmentCalculationMode` | SegmentCalculationMode | **Yes** | Segment calculation mode |
| `externalPromotions` | List\<PromotionGrant\> | No | External promotions (coupons, etc.) |
| `previousCarryOver` | BillingCarryOver | No | Previous state for CONTINUE mode |

### Output: BillingResult

| Field | Type | Description |
|-------|------|-------------|
| `finalAmount` | BigDecimal | Final amount due |
| `units` | List\<BillingUnit\> | Billing unit details |
| `promotionUsages` | List\<PromotionUsage\> | Promotion usage records |
| `carryOver` | BillingCarryOver | State for next calculation |

## Billing Modes

| Mode | Description |
|------|-------------|
| `CONTINUOUS` | Continuous time billing, can be interrupted by promotions |
| `UNIT_BASED` | Independent billing per unit |

## Promotion Types

| Type | Description |
|------|-------------|
| `FREE_MINUTES` | Free minutes allocated optimally |
| `FREE_RANGE` | Free time periods |
| `AMOUNT` | Amount discount (planned) |
| `DISCOUNT` | Percentage discount (planned) |

## Continue Mode

Support incremental billing for long-term scenarios:

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

### DayNightConfig

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

### RelativeTimeConfig

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

### CompositeTimeConfig

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

## Custom Rules

### 1. Implement RuleConfig

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

### 2. Implement BillingRule

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
| `billing-core` | Core billing engine, pure calculation logic |
| `billing-api` | Convenient API wrapper (recommended) |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0-3.4 integration |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5-4.x integration |

## Design Principles

1. **Core engine only calculates** - No caching, no database, no side effects
2. **Rules are pure functions** - Same input always produces same output
3. **Rules don't depend on each other** - All rules executed through Engine
4. **Config and implementation separated** - RuleConfig describes parameters, BillingRule calculates

## License

MIT License