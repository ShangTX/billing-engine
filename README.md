# Billing Engine

[中文](README_CN.md) | [User Guide](docs/USER_GUIDE.md) | [Capabilities](docs/billing-engine-capabilities.md)

An extensible and traceable time-based billing engine for parking fees, venue rental, equipment rental, and other time charging scenarios.

Repository mirrors:

- Gitee: https://gitee.com/shtx/charge
- GitHub: https://github.com/ShangTX/billing-engine

For custom billing-rule extensions, see the implementation examples in the Gitee repository:
https://gitee.com/shtx/charge/tree/master/bill-test/src/main/java/cn/shang/charging/examples

## What It Provides

- Extensible billing rules without changing the core engine
- 5 built-in rule types: `dayNight`, `relativeTime`, `naturalTime`, `compositeTime`, `flatFree`
- 4 calculation modes: `CONTINUOUS`, `UNIT_BASED`, `DURATION_PERIOD`, `DURATION_GLOBAL`
- Incomplete-unit charging via unified `IncompleteUnitChargeSpec`
- Promotions: free time ranges, free minutes, smart free minutes, and conditional activation for duration modes
- Detailed billing units for audit and debugging
- Scheme switching over time (multi-segment billing)
- Query-time equivalent amount calculation via elimination method
- Pure computation core — no database, no cache, no side effects

## Requirements

- JDK 21+
- Maven 3.6+

## Install

### Recommended: billing-api

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Spring Boot Starters

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Quick Start

### Step 1: Implement BillingConfigResolver

This is the only interface you must implement. It tells the engine which billing rule, promotions, and calculation mode to use for each scheme.

```java
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class MyConfigResolver implements BillingConfigResolver {

    @Override
    public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
        return BConstants.CalculationMode.CONTINUOUS;
    }

    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd,
                                          Map<String, Object> context) {
        return new DayNightConfig()
                .setId("rule-1")
                .setDayBeginMinute(8 * 60)       // 08:00
                .setDayEndMinute(20 * 60)         // 20:00
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setUnitMinutes(60);
    }

    @Override
    public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                           LocalDateTime segmentStart,
                                                           LocalDateTime segmentEnd,
                                                           Map<String, Object> context) {
        return List.of();
    }
}
```

### Step 2a: Pure Java setup

```java
import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.promotion.*;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;

BillingConfigResolver configResolver = new MyConfigResolver();
BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

PromotionEngine promotionEngine = new PromotionEngine(
        configResolver, new FreeTimeRangeMerger(), promotionRuleRegistry);

BillingService billingService = new BillingService(
        new SegmentBuilder(), configResolver, promotionEngine,
        new BillingCalculator(billingRuleRegistry), new ResultAssembler());

BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);
```

### Step 2b: Spring Boot setup

Add the starter dependency and provide your `BillingConfigResolver` as a `@Component`. All engine beans are auto-wired.

```java
@Component
public class MyConfigResolver implements BillingConfigResolver { /* ... */ }

@Service
public class MyService {
    private final BillingTemplate billingTemplate;

    public MyService(BillingTemplate billingTemplate) {
        this.billingTemplate = billingTemplate;
    }

    public BillingResult charge(BillingRequest request) {
        return billingTemplate.calculate(request);
    }
}
```

### Step 3: Call calculate

```java
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BConstants;

BillingRequest request = new BillingRequest();
request.setBeginTime(LocalDateTime.of(2026, 5, 8, 9, 0));
request.setEndTime(LocalDateTime.of(2026, 5, 8, 12, 30));
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);

// result.getFinalAmount()      → final amount
// result.getUnits()            → billing unit details (CONTINUOUS/UNIT_BASED)
// result.getDurationSegments() → duration segments (DURATION_PERIOD/DURATION_GLOBAL)
// result.getPromotionUsages()  → promotion usage records
```

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](docs/USER_GUIDE.md) | Complete API reference, field definitions, code examples |
| [Capabilities](docs/billing-engine-capabilities.md) | Capability matrix and design notes |
| [中文能力文档](docs/billing-engine-capabilities-zh.md) | Capability matrix in Chinese |
| [计算流程](docs/billing-engine-calculation-flow-zh.md) | Core billing pipeline and calculation flow |

## Module Structure

| Module | Description |
|--------|-------------|
| `billing-core` | Core billing engine — pure computation, zero dependencies |
| `billing-api` | Convenience API wrapping core (`BillingTemplate`) |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0.x – 3.4.x auto-configuration |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5.x – 4.x auto-configuration |
| `bill-test` | Integration tests and examples |
