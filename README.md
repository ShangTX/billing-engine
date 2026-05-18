# Billing Engine

[中文](README_CN.md) | [User Guide](docs/USER_GUIDE.md) | [Capabilities](docs/billing-engine-capabilities.md)

An extensible and traceable time-based billing engine for parking fees, venue rental, equipment rental, and other time charging scenarios.

## What It Provides

- Extensible billing rules without changing the core engine.
- Detailed billing units for audit and debugging.
- Continue calculation from a previous result.
- Scheme switching over time.
- Free time ranges and free minutes promotions.
- Query-time amount calculation through `billing-api`.

## Requirements

- JDK 21+
- Maven 3.6+

JDK 25 is also compatible and has been verified with OpenJDK 25.

## Install

### Recommended: `billing-api`

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>2.1.1</version>
</dependency>
```

### Spring Boot Starters

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>2.1.1</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>2.1.1</version>
</dependency>
```

## Minimal Usage

Implement `BillingConfigResolver`, build a `BillingTemplate`, then call `calculate()`.

```java
BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

For a full compilable setup, rule registration, Spring Boot integration, query-time amounts, continue calculation, and custom rule development, see the [User Guide](docs/USER_GUIDE.md).

## Current Capability Notes

- Implemented billing rules include `dayNight`, `relativeTime`, `compositeTime`, and `flatFree`.
- Implemented promotion capabilities focus on `FREE_RANGE`, `FREE_MINUTES`, `freeMinutes`, and `startFree`.
- `naturalTime` and `nrTimeMix` are now covered by `compositeTime` and will not be implemented separately.
- `AMOUNT` and `DISCOUNT` are reserved promotion types; `times` is a reserved billing type (for non-time-based scenarios).
- Query-time amount is calculated from the hit unit's `valueSpec`; it is not read directly from `accumulatedAmount`.

See [Billing Engine Capabilities](docs/billing-engine-capabilities.md) for the full implemented capability matrix and known gaps.

## Documentation

| Document | Purpose |
|----------|---------|
| [User Guide](docs/USER_GUIDE.md) | Main caller-facing usage guide |
| [Capabilities](docs/billing-engine-capabilities.md) | Implemented capabilities and current limitations |
| [Calculation Flow](docs/billing-engine-calculation-flow-zh.md) | Chinese calculation flow reference |
| [TODO](docs/TODO.md) | Active backlog and known issues |

## License

MIT License
