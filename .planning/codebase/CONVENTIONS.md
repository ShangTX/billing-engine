# Coding Conventions

**Analysis Date:** 2026-03-30

## Naming Patterns

**Files:**
- Java files use PascalCase matching class names
- Package structure follows `cn.shang.charging.{module}` pattern
- Test files match source file names with `Test` suffix (e.g., `BillingResultViewerTest.java`)

**Classes:**
- POJOs use PascalCase: `BillingRequest`, `BillingResult`, `BillingUnit`
- Services use PascalCase with descriptive names: `BillingService`, `PromotionEngine`
- Rules use PascalCase with rule type suffix: `DayNightRule`, `RelativeTimeRule`
- Config classes use PascalCase with `Config` suffix: `DayNightConfig`, `RelativeTimeConfig`
- Enums use PascalCase: `BillingMode`, `ContinueMode`, `PromotionType`
- Constants classes use PascalCase with nested static classes: `BConstants.ChargeRuleType`, `BConstants.PromotionRuleType`

**Methods:**
- camelCase for all methods: `calculate()`, `buildBillingUnits()`, `applyDailyCap()`
- Private methods use camelCase with descriptive prefixes: `validateConfig()`, `findFreePromotionId()`
- Boolean getters use `is` prefix where appropriate: `isEmpty()`, `hasMultiplePromotionTypes()`
- Default interface methods use `default` keyword with clear fallback behavior

**Variables:**
- Local variables use camelCase: `calcBegin`, `calcEnd`, `billingUnits`
- Constants use UPPER_SNAKE_CASE: `RULE_TYPE`, `MINUTES_PER_CYCLE`
- Private static finals: `private static final String RULE_TYPE = "dayNight"`

**Types:**
- Generics use single uppercase letter: `BillingRule<C extends RuleConfig>`
- Type parameters documented in class/interface definitions

## Code Style

**Formatting:**
- No explicit formatter config detected (no `.editorconfig`, `.prettierrc`, or `spotless` plugin)
- Indentation: 4 spaces (observed in source files)
- Max line length: approximately 120-150 characters (no strict enforcement)

**Linting:**
- No explicit linting configuration detected
- Code quality enforced through Maven build process

**Annotations:**
- Lombok annotations placed before class definition
- Order typically: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Accessors(chain=true)`

## Import Organization

**Order:**
1. `java.*` packages (e.g., `java.math.BigDecimal`, `java.time.LocalDateTime`)
2. `cn.shang.charging.*` internal packages
3. Third-party packages (`lombok.*`)

**Path Aliases:**
- No path aliases configured
- Full package imports used throughout

## Error Handling

**Patterns:**
- Validation throws `IllegalArgumentException` with descriptive messages:
```java
// From DayNightRule.java line 73-106
private void validateConfig(DayNightConfig config) {
    if (config.getMaxChargeOneDay() == null) {
        throw new IllegalArgumentException("maxChargeOneDay is required");
    }
    if (config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("maxChargeOneDay must be positive");
    }
}
```

- Null checks use explicit `if` statements rather than `Objects.requireNonNull()`
- Graceful null handling with fallback defaults:
```java
// From DayNightRule.java line 170-173
List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
if (freeTimeRanges == null) {
    freeTimeRanges = List.of();
}
```

- Return empty collections instead of null:
```java
// From BillingRule.java line 34-36
default Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
    return Collections.emptyMap();
}
```

## Logging

**Framework:** No logging framework used in core module (SLF4J/Logback not detected)

**Patterns:**
- Test module uses `System.out.println()` for output:
```java
// From DayNightTest.java
System.out.println("结果: finalAmount = " + result.getFinalAmount());
```

- Core module follows "no side effects" principle - no logging in calculation logic

## Comments

**When to Comment:**
- Class-level Javadoc comments explaining purpose and core logic:
```java
// From DayNightRule.java lines 23-32
/**
 * 日夜分时段计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按24小时划分周期
 * 2. 每个周期内独立计算封顶
 * 3. 按unitMinutes划分计费单元，跨周期边界截断
 * ...
 */
```

- Method-level Javadoc for public interfaces:
```java
// From BillingConfigResolver.java
/**
 * 获取计费规则配置（带上下文参数）
 * @param schemeId 方案id
 * @param segmentStart 计费分段开始时间
 * @param segmentEnd 计费分段结束时间
 * @param context 上下文参数
 * @return 当前分段的计费规则配置
 */
```

- Inline comments for complex logic explanations

**JSDoc/TSDoc:**
- Javadoc used for public APIs and complex methods
- Chinese comments used for domain-specific explanations
- Parameter documentation with `@param`, return with `@return`

## Function Design

**Size:** Methods range from 10-200+ lines depending on complexity

**Parameters:**
- Use POJOs for complex parameter groups: `BillingContext`, `PromotionAggregate`
- Primitive parameters for simple operations: `schemeId`, `segmentStart`, `segmentEnd`

**Return Values:**
- Return Result POJOs for complex operations: `BillingResult`, `BillingSegmentResult`
- Return primitive/wrapper types for simple queries: `BigDecimal`, `boolean`
- Return empty collections instead of null

## Module Design

**Exports:**
- Public interfaces for extension points: `BillingRule`, `PromotionRule`, `BillingConfigResolver`
- Public POJOs for data transfer: `BillingRequest`, `BillingResult`, `BillingUnit`
- Service classes are public: `BillingService`, `BillingCalculator`

**Barrel Files:** Not used - each class in its own file

## Lombok Usage Patterns

**Core Annotations:**
- `@Data` - Generates getters, setters, `equals`, `hashCode`, `toString`
- `@Builder` - Creates builder pattern with `@Builder.Default` for defaults
- `@NoArgsConstructor` - Zero-argument constructor
- `@AllArgsConstructor` - All-arguments constructor
- `@Accessors(chain=true)` - Enables fluent setter chaining

**POJO Pattern:**
```java
// From BillingUnit.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class BillingUnit {
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    // ...
}
```

**Config Pattern:**
```java
// From DayNightConfig.java
@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class DayNightConfig implements RuleConfig {
    String id;

    @Builder.Default
    String type = BConstants.ChargeRuleType.DAY_NIGHT;
    // ...
}
```

**Inner Classes:**
- Static inner classes for state management:
```java
// From AbstractTimeBasedRule.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public static class RuleState {
    private int cycleIndex;
    private BigDecimal cycleAccumulated;
    private LocalDateTime cycleBoundary;
}
```

## Builder Pattern Usage

**Creating Objects:**
```java
// From BillingService.java line 52-58
return BillingResult.builder()
    .units(List.of())
    .promotionUsages(List.of())
    .finalAmount(BigDecimal.ZERO)
    .calculationEndTime(carryOver.getCalculatedUpTo())
    .carryOver(carryOver)
    .build();
```

**Fluent Chaining:**
```java
// From DayNightTest.java line 158-165
externalPromotions.add(PromotionGrant.builder()
    .id("external-free-range")
    .type(BConstants.PromotionType.FREE_RANGE)
    .priority(1)
    .source(BConstants.PromotionSource.COUPON)
    .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
    .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
    .build());
```

**toBuilder Pattern:**
```java
// From BillingResult.java
@Builder(toBuilder = true)
public class BillingResult { ... }
```

## Interface Design Patterns

**Default Methods:**
- Interface provides default implementations for backward compatibility:
```java
// From BillingConfigResolver.java
default BConstants.BillingMode resolveBillingMode(String schemeId) {
    return resolveBillingMode(schemeId, Collections.emptyMap());
}
```

**Generic Type Safety:**
- Rules declare config class type:
```java
// From BillingRule.java
public interface BillingRule<C extends RuleConfig> {
    Class<C> configClass();
    BillingSegmentResult calculate(BillingContext context, C ruleConfig, PromotionAggregate promotionAggregate);
}
```

**Strategy Pattern:**
- Registry pattern for rule implementations:
```java
// From BillingRuleRegistry.java (used in BillingAutoConfiguration.java)
BillingRuleRegistry registry = new BillingRuleRegistry();
registry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
```

---

*Convention analysis: 2026-03-30*