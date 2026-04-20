# Unit Value Spec Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a unified `valueSpec`-based unit valuation mechanism, switch query-time amount calculation to use it, and ship the first working integration for `DayNightRule`, `StartFreePromotionRule`, and simplified-unit exact query fallback.

**Architecture:** Add a Core-level unit valuation protocol (`UnitValueSpec` + evaluator) that computes a unit's current value and next change time from `queryTime`. Keep query orchestration in `billing-api`, let rules emit `valueSpec`, and treat simplified units as an API-level exact-recalculation fallback instead of forcing them through partial valuation.

**Tech Stack:** Java 21, Maven multi-module project, Lombok, JUnit 5

---

## Scope

This plan intentionally covers the first shippable slice of the approved design:

- Core `valueSpec` protocol and evaluator
- `BillingUnit` / `QuerySummary` / `BillingResultViewer` semantic changes
- Exact-query fallback for simplified units
- `DayNightRule` integration (`UNIT_BASED` + `CONTINUOUS`, including cap-aware valuation)
- `StartFreePromotionRule` migration from `conditionalFree` fields to `valueSpec`

Out of scope for this plan:

- `RelativeTimeRule`
- `CompositeTimeRule`
- Minute-by-minute `valueSpec` implementation

These should be handled in a follow-up plan after the first slice is stable.

## File Structure

### New files

- `core/src/main/java/cn/shang/charging/billing/value/UnitValueSpec.java`
  - Common valuation protocol for all `BillingUnit` instances
- `core/src/main/java/cn/shang/charging/billing/value/UnitValueProjection.java`
  - Evaluator return object carrying current amount and next change time
- `core/src/main/java/cn/shang/charging/billing/value/FixedValueSpec.java`
  - Stable unit implementation
- `core/src/main/java/cn/shang/charging/billing/value/StepValueSpec.java`
  - Step-change implementation for start-free-like behavior
- `core/src/main/java/cn/shang/charging/billing/value/PiecewiseTimeValueSpec.java`
  - Piecewise cumulative implementation for `DayNightRule`
- `core/src/main/java/cn/shang/charging/billing/value/UnitValueEvaluator.java`
  - Shared evaluator
- `bill-test/src/test/java/cn/shang/charging/UnitValueEvaluatorTest.java`
  - Direct tests for fixed/step/piecewise valuation
- `bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java`
  - End-to-end day/night query-time valuation tests

### Modified files

- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
  - Remove `conditionalFree*`, add `valueSpec`, redefine `chargedAmount`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java`
  - Add request-level switch to disable simplification for exact query recalculation
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`
  - Carry the simplification-disable flag to rules
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
  - Respect the per-request simplification-disable switch
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
  - Emit `valueSpec` for stable, mixed, capped, and free units
- `core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionRule.java`
  - Keep emitting `conditional/conditionalUntil` at promotion level for now
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
  - Replace conditional-free patching with value-spec evaluation
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
  - Trigger exact recalculation when query hits a simplified unit
- `billing-api/src/main/java/cn/shang/charging/wrapper/QuerySummary.java`
  - Redefine `amount` and `effectiveTo`
- `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`
  - Rewrite around `valueSpec`
- `bill-test/src/main/java/cn/shang/charging/ConditionalFreePromotionTest.java`
  - Convert from print-only exploratory file to aligned sample / update semantics
- `docs/USER_GUIDE.md`
  - Document new query semantics

### Existing files to inspect while implementing

- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `docs/superpowers/specs/2026-04-20-unit-value-spec-design.md`

---

### Task 1: Add Core Valuation Protocol

**Files:**
- Create: `core/src/main/java/cn/shang/charging/billing/value/UnitValueSpec.java`
- Create: `core/src/main/java/cn/shang/charging/billing/value/UnitValueProjection.java`
- Create: `core/src/main/java/cn/shang/charging/billing/value/FixedValueSpec.java`
- Create: `core/src/main/java/cn/shang/charging/billing/value/StepValueSpec.java`
- Create: `core/src/main/java/cn/shang/charging/billing/value/PiecewiseTimeValueSpec.java`
- Create: `core/src/main/java/cn/shang/charging/billing/value/UnitValueEvaluator.java`
- Test: `bill-test/src/test/java/cn/shang/charging/UnitValueEvaluatorTest.java`

- [ ] **Step 1: Write the failing evaluator tests**

```java
package cn.shang.charging;

import cn.shang.charging.billing.value.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitValueEvaluatorTest {

    private final UnitValueEvaluator evaluator = new UnitValueEvaluator();

    @Test
    void fixedValueSpec_returnsStableAmountAndUnitEndAsNextChange() {
        LocalDateTime t0 = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 20, 11, 0);

        UnitValueProjection projection = evaluator.evaluate(
                FixedValueSpec.builder()
                        .finalAmount(new BigDecimal("5.00"))
                        .unitEndTime(t1)
                        .build(),
                t0.plusMinutes(15),
                t0,
                t1
        );

        assertEquals(new BigDecimal("5.00"), projection.getCurrentAmount());
        assertEquals(t1, projection.getNextChangeTime());
    }

    @Test
    void stepValueSpec_switchesAtConfiguredBoundary() {
        LocalDateTime t0 = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 20, 10, 30);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 20, 11, 0);

        StepValueSpec spec = StepValueSpec.builder()
                .beforeAmount(BigDecimal.ZERO)
                .afterAmount(new BigDecimal("8.00"))
                .switchTime(t1)
                .unitEndTime(t2)
                .build();

        assertEquals(BigDecimal.ZERO, evaluator.evaluate(spec, t0.plusMinutes(10), t0, t2).getCurrentAmount());
        assertEquals(new BigDecimal("8.00"), evaluator.evaluate(spec, t1.plusMinutes(1), t0, t2).getCurrentAmount());
    }

    @Test
    void piecewiseTimeValueSpec_accumulatesBySegment() {
        LocalDateTime t0 = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime t1 = LocalDateTime.of(2026, 4, 20, 10, 20);
        LocalDateTime t2 = LocalDateTime.of(2026, 4, 20, 11, 0);

        PiecewiseTimeValueSpec spec = PiecewiseTimeValueSpec.builder()
                .finalAmount(new BigDecimal("10.00"))
                .segments(List.of(
                        PiecewiseTimeValueSpec.Segment.fixed(t0, t1, new BigDecimal("2.00")),
                        PiecewiseTimeValueSpec.Segment.linear(t1, t2, new BigDecimal("2.00"), new BigDecimal("10.00"))
                ))
                .unitEndTime(t2)
                .build();

        assertEquals(new BigDecimal("2.00"), evaluator.evaluate(spec, t1, t0, t2).getCurrentAmount());
        assertEquals(new BigDecimal("6.00"), evaluator.evaluate(spec, LocalDateTime.of(2026, 4, 20, 10, 40), t0, t2).getCurrentAmount());
        assertEquals(new BigDecimal("10.00"), evaluator.evaluate(spec, t2, t0, t2).getCurrentAmount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=UnitValueEvaluatorTest test`

Expected: compilation failure because `billing.value` types do not exist yet

- [ ] **Step 3: Write minimal valuation protocol**

```java
package cn.shang.charging.billing.value;

import java.time.LocalDateTime;

public interface UnitValueSpec {
    UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime);
}
```

```java
package cn.shang.charging.billing.value;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitValueProjection {
    private BigDecimal currentAmount;
    private LocalDateTime nextChangeTime;
}
```

```java
package cn.shang.charging.billing.value;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedValueSpec implements UnitValueSpec {
    private BigDecimal finalAmount;
    private LocalDateTime unitEndTime;

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        return UnitValueProjection.builder()
                .currentAmount(finalAmount)
                .nextChangeTime(this.unitEndTime)
                .build();
    }
}
```

```java
package cn.shang.charging.billing.value;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepValueSpec implements UnitValueSpec {
    private BigDecimal beforeAmount;
    private BigDecimal afterAmount;
    private LocalDateTime switchTime;
    private LocalDateTime unitEndTime;

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        boolean after = queryTime.isAfter(switchTime);
        return UnitValueProjection.builder()
                .currentAmount(after ? afterAmount : beforeAmount)
                .nextChangeTime(after ? this.unitEndTime : this.switchTime)
                .build();
    }
}
```

```java
package cn.shang.charging.billing.value;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PiecewiseTimeValueSpec implements UnitValueSpec {
    private BigDecimal finalAmount;
    private List<Segment> segments;
    private LocalDateTime unitEndTime;

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        BigDecimal current = BigDecimal.ZERO;
        LocalDateTime nextChange = this.unitEndTime;

        for (Segment segment : segments) {
            if (!queryTime.isAfter(segment.getBegin()) && !queryTime.equals(segment.getBegin())) {
                nextChange = segment.getBegin();
                break;
            }
            if (!queryTime.isAfter(segment.getEnd())) {
                current = current.add(segment.valueAt(queryTime));
                nextChange = segment.getEnd();
                break;
            }
            current = current.add(segment.valueAt(segment.getEnd()));
        }

        return UnitValueProjection.builder()
                .currentAmount(current.min(finalAmount))
                .nextChangeTime(nextChange)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Segment {
        private LocalDateTime begin;
        private LocalDateTime end;
        private BigDecimal fromAmount;
        private BigDecimal toAmount;

        public static Segment fixed(LocalDateTime begin, LocalDateTime end, BigDecimal amount) {
            return Segment.builder().begin(begin).end(end).fromAmount(amount).toAmount(amount).build();
        }

        public static Segment linear(LocalDateTime begin, LocalDateTime end, BigDecimal fromAmount, BigDecimal toAmount) {
            return Segment.builder().begin(begin).end(end).fromAmount(fromAmount).toAmount(toAmount).build();
        }

        public BigDecimal valueAt(LocalDateTime queryTime) {
            if (!queryTime.isAfter(begin)) {
                return fromAmount;
            }
            if (!queryTime.isBefore(end)) {
                return toAmount;
            }
            long total = Duration.between(begin, end).toMinutes();
            long elapsed = Duration.between(begin, queryTime).toMinutes();
            BigDecimal ratio = BigDecimal.valueOf(elapsed)
                    .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
            return fromAmount.add(toAmount.subtract(fromAmount).multiply(ratio));
        }
    }
}
```

```java
package cn.shang.charging.billing.value;

import java.time.LocalDateTime;

public class UnitValueEvaluator {
    public UnitValueProjection evaluate(UnitValueSpec spec, LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        return spec.project(queryTime, unitBeginTime, unitEndTime);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl bill-test -Dtest=UnitValueEvaluatorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/value/*.java bill-test/src/test/java/cn/shang/charging/UnitValueEvaluatorTest.java
git commit -m "feat: add unit value spec core protocol"
```

### Task 2: Redefine BillingUnit Semantics

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/QuerySummary.java`
- Test: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`

- [ ] **Step 1: Write failing tests for query summary semantics**

```java
@Test
void createQuerySummary_usesCurrentUnitProjectionInsteadOfAccumulatedAmount() {
    LocalDateTime t8 = LocalDateTime.of(2026, 4, 20, 8, 0);
    LocalDateTime t9 = LocalDateTime.of(2026, 4, 20, 9, 0);

    BillingUnit unit = BillingUnit.builder()
            .beginTime(t8)
            .endTime(t9)
            .chargedAmount(new BigDecimal("10.00"))
            .accumulatedAmount(new BigDecimal("20.00"))
            .valueSpec(StepValueSpec.builder()
                    .beforeAmount(new BigDecimal("4.00"))
                    .afterAmount(new BigDecimal("10.00"))
                    .switchTime(LocalDateTime.of(2026, 4, 20, 8, 30))
                    .unitEndTime(t9)
                    .build())
            .build();

    BillingResult result = BillingResult.builder()
            .units(List.of(unit))
            .calculationEndTime(t9)
            .build();

    QuerySummary summary = new BillingResultViewer().createQuerySummary(result, LocalDateTime.of(2026, 4, 20, 8, 15));

    assertEquals(new BigDecimal("4.00"), summary.getAmount());
    assertEquals(LocalDateTime.of(2026, 4, 20, 8, 30), summary.getEffectiveTo());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest#createQuerySummary_usesCurrentUnitProjectionInsteadOfAccumulatedAmount test`

Expected: compilation failure because `BillingUnit` has no `valueSpec`

- [ ] **Step 3: Extend BillingUnit and QuerySummary**

```java
package cn.shang.charging.billing.pojo;

import cn.shang.charging.billing.value.UnitValueSpec;
// existing imports omitted

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class BillingUnit {
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private int durationMinutes;
    private BigDecimal unitPrice;
    private BigDecimal originalAmount;
    private boolean free;
    private Boolean isTruncated;
    private String freePromotionId;
    private BigDecimal chargedAmount;      // final amount after full unit completion
    private BigDecimal accumulatedAmount;  // cumulative amount after full unit completion
    private UnitValueSpec valueSpec;
    private Object ruleData;

    @Deprecated
    private Boolean mergedFromPrevious;
}
```

```java
package cn.shang.charging.wrapper;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuerySummary {
    private int unitIndex;
    private BigDecimal amount;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo; // next change time from valueSpec
    private LocalDateTime queryTime;
    private List<PromotionUsage> promotionUsages;
}
```

- [ ] **Step 4: Run focused tests to verify compile and baseline pass**

Run: `mvn -pl bill-test -Dtest=UnitValueEvaluatorTest,BillingResultViewerTest test`

Expected: FAIL in viewer assertions only, compile succeeds

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java billing-api/src/main/java/cn/shang/charging/wrapper/QuerySummary.java bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java
git commit -m "refactor: add value spec to billing unit"
```

### Task 3: Rewrite BillingResultViewer Around UnitValueEvaluator

**Files:**
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
- Modify: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`

- [ ] **Step 1: Write the failing viewer tests for new formula and coverage check**

```java
@Test
void createQuerySummary_rejectsQueryTimeAfterCalculationEndTime() {
    BillingResult result = BillingResult.builder()
            .units(List.of())
            .calculationEndTime(LocalDateTime.of(2026, 4, 20, 9, 0))
            .build();

    IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> new BillingResultViewer().createQuerySummary(result, LocalDateTime.of(2026, 4, 20, 9, 1))
    );

    assertTrue(ex.getMessage().contains("calculationEndTime"));
}

@Test
void createQuerySummary_usesAccumulatedMinusFinalPlusCurrentFormula() {
    LocalDateTime t8 = LocalDateTime.of(2026, 4, 20, 8, 0);
    LocalDateTime t9 = LocalDateTime.of(2026, 4, 20, 9, 0);

    BillingUnit unit = BillingUnit.builder()
            .beginTime(t8)
            .endTime(t9)
            .chargedAmount(new BigDecimal("10.00"))
            .accumulatedAmount(new BigDecimal("20.00"))
            .valueSpec(FixedValueSpec.builder().finalAmount(new BigDecimal("6.00")).unitEndTime(t9).build())
            .build();

    BillingResult result = BillingResult.builder()
            .units(List.of(unit))
            .calculationEndTime(t9)
            .build();

    QuerySummary summary = new BillingResultViewer().createQuerySummary(result, t8.plusMinutes(10));

    assertEquals(new BigDecimal("16.00"), summary.getAmount());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest test`

Expected: FAIL because viewer still uses `accumulatedAmount` directly and lacks coverage guard

- [ ] **Step 3: Replace legacy conditional-free logic with evaluator-based query**

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.UnitValueEvaluator;
import cn.shang.charging.billing.value.UnitValueProjection;
// existing imports omitted

public class BillingResultViewer {

    private final UnitValueEvaluator evaluator = new UnitValueEvaluator();

    public QuerySummary createQuerySummary(BillingResult result, LocalDateTime queryTime) {
        if (result == null || queryTime == null) {
            throw new IllegalArgumentException("result 和 queryTime 不能为 null");
        }
        if (result.getCalculationEndTime() != null && queryTime.isAfter(result.getCalculationEndTime())) {
            throw new IllegalArgumentException("queryTime 超出 calculationEndTime");
        }

        List<BillingUnit> units = result.getUnits();
        if (units == null || units.isEmpty()) {
            return QuerySummary.builder()
                    .unitIndex(-1)
                    .amount(BigDecimal.ZERO)
                    .queryTime(queryTime)
                    .promotionUsages(List.of())
                    .build();
        }

        int unitIndex = findUnitIndex(units, queryTime);
        BillingUnit unit = units.get(unitIndex);
        UnitValueProjection projection = evaluator.evaluate(unit.getValueSpec(), queryTime, unit.getBeginTime(), unit.getEndTime());

        BigDecimal amount = unit.getAccumulatedAmount()
                .subtract(unit.getChargedAmount())
                .add(projection.getCurrentAmount());

        return QuerySummary.builder()
                .unitIndex(unitIndex)
                .amount(amount)
                .effectiveFrom(units.get(0).getBeginTime())
                .effectiveTo(projection.getNextChangeTime())
                .queryTime(queryTime)
                .promotionUsages(filterUsages(result.getPromotionUsages(), queryTime))
                .build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest,UnitValueEvaluatorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java
git commit -m "refactor: evaluate query amounts from unit value specs"
```

### Task 4: Add Simplification Bypass For Exact Query

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
- Test: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`

- [ ] **Step 1: Write the failing exact-query fallback test**

```java
@Test
void calculateWithQuery_recalculatesWhenHitUnitIsSimplified() {
    BillingRequest request = new BillingRequest();
    request.setBeginTime(LocalDateTime.of(2026, 4, 20, 0, 0));
    request.setEndTime(LocalDateTime.of(2026, 4, 25, 0, 0));
    request.setDisableSimplification(false);

    BillingTemplate template = // build template with low simplified threshold test double

    CalculationWithQueryResult result = template.calculateWithQuery(request, LocalDateTime.of(2026, 4, 22, 12, 0));

    assertFalse(result.getCalculationResult().getUnits().stream().anyMatch(UnitValueTestSupport::isSimplifiedUnit));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest#calculateWithQuery_recalculatesWhenHitUnitIsSimplified test`

Expected: FAIL because request has no simplification-disable flag and template never recalculates

- [ ] **Step 3: Add request/context flag and template fallback**

```java
package cn.shang.charging.billing.pojo;

@Data
public class BillingRequest {
    // existing fields...
    private Boolean disableSimplification;
}
```

```java
package cn.shang.charging.billing.pojo;

@Builder
@Data
public class BillingContext {
    // existing fields...
    private Boolean disableSimplification;
}
```

```java
protected boolean isSimplificationEnabled(C config, BillingConfigResolver configResolver, BillingContext context) {
    if (Boolean.TRUE.equals(context.getDisableSimplification())) {
        return false;
    }
    if (config.getSimplifiedSupported() != null && !config.getSimplifiedSupported()) {
        return false;
    }
    int threshold = configResolver.getSimplifiedCycleThreshold();
    if (threshold <= 0) {
        return false;
    }
    BigDecimal capAmount = getCycleCapAmount(config);
    return capAmount != null && capAmount.compareTo(BigDecimal.ZERO) > 0;
}
```

```java
public CalculationWithQueryResult calculateWithQuery(BillingRequest request, LocalDateTime queryTime) {
    BillingResult calculationResult = billingService.calculate(request);

    QuerySummary firstPass = resultViewer.createQuerySummary(calculationResult, queryTime);
    BillingUnit hitUnit = firstPass.getUnitIndex() >= 0 ? calculationResult.getUnits().get(firstPass.getUnitIndex()) : null;

    if (hitUnit != null && UnitValueSpecs.isSimplified(hitUnit.getValueSpec())) {
        BillingRequest detailedRequest = request.toBuilder()
                .disableSimplification(true)
                .build();
        calculationResult = billingService.calculate(detailedRequest);
    }

    QuerySummary queryResult = resultViewer.createQuerySummary(calculationResult, queryTime);
    return new CalculationWithQueryResult(calculationResult, queryResult);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java
git commit -m "feat: bypass simplification for exact query"
```

### Task 5: Migrate DayNightRule UNIT_BASED Units To valueSpec

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- Create: `bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java`

- [ ] **Step 1: Write failing unit-based mixed-unit query test**

```java
@Test
void unitBased_mixedDayNightUnit_returnsProgressiveCurrentValue() {
    BillingService service = DayNightQueryFixtures.createUnitBasedService(
            LocalDateTime.of(2026, 4, 20, 18, 30),
            LocalDateTime.of(2026, 4, 20, 19, 30)
    );

    BillingRequest request = DayNightQueryFixtures.baseRequest(
            LocalDateTime.of(2026, 4, 20, 18, 30),
            LocalDateTime.of(2026, 4, 20, 19, 30),
            BConstants.BillingMode.UNIT_BASED
    );

    BillingResult result = service.calculate(request);
    QuerySummary summary = new BillingResultViewer().createQuerySummary(result, LocalDateTime.of(2026, 4, 20, 19, 0));

    assertEquals(new BigDecimal("1.00"), summary.getAmount());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=DayNightQueryValueTest#unitBased_mixedDayNightUnit_returnsProgressiveCurrentValue test`

Expected: FAIL because mixed unit is still flattened to a single fixed price

- [ ] **Step 3: Emit fixed or piecewise `valueSpec` from UNIT_BASED path**

```java
private BillingUnit calculateUnit(UnitWithContext unitCtx, DayNightConfig config, List<FreeTimeRange> freeTimeRanges) {
    int duration = (int) Duration.between(unitCtx.beginTime, unitCtx.endTime).toMinutes();
    UnitValueSpec valueSpec;
    BigDecimal finalAmount;

    if (unitCtx.periodType == PeriodType.DAY) {
        finalAmount = config.getDayUnitPrice();
        valueSpec = FixedValueSpec.builder()
                .finalAmount(finalAmount)
                .unitEndTime(unitCtx.endTime)
                .build();
    } else if (unitCtx.periodType == PeriodType.NIGHT) {
        finalAmount = config.getNightUnitPrice();
        valueSpec = FixedValueSpec.builder()
                .finalAmount(finalAmount)
                .unitEndTime(unitCtx.endTime)
                .build();
    } else {
        finalAmount = determineFinalMixedAmount(unitCtx, config);
        valueSpec = buildMixedUnitValueSpec(unitCtx, config, finalAmount);
    }

    String freePromotionId = findFreePromotionId(unitCtx.beginTime, unitCtx.endTime, freeTimeRanges);
    boolean isFree = freePromotionId != null;
    if (isFree) {
        finalAmount = BigDecimal.ZERO;
        valueSpec = FixedValueSpec.builder().finalAmount(BigDecimal.ZERO).unitEndTime(unitCtx.endTime).build();
    }

    return BillingUnit.builder()
            .beginTime(unitCtx.beginTime)
            .endTime(unitCtx.endTime)
            .durationMinutes(duration)
            .unitPrice(finalAmount)
            .originalAmount(finalAmount)
            .free(isFree)
            .freePromotionId(freePromotionId)
            .chargedAmount(finalAmount)
            .valueSpec(valueSpec)
            .ruleData(unitCtx.cycleIndex)
            .build();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl bill-test -Dtest=DayNightQueryValueTest,UnitValueEvaluatorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java
git commit -m "feat: add unit value specs to day-night unit-based units"
```

### Task 6: Migrate DayNightRule CONTINUOUS Path And Encode Cap Into valueSpec

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- Modify: `bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java`

- [ ] **Step 1: Write failing continuous-mode tests for mixed units and cap-hit unit**

```java
@Test
void continuous_mixedUnit_usesPiecewiseCurrentValue() {
    // arrange a fragment that crosses day/night boundary without free ranges
}

@Test
void continuous_capHitUnit_returnsCappedCurrentValue() {
    // arrange a day cap low enough that the hit unit gets truncated by cap
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=DayNightQueryValueTest test`

Expected: FAIL because continuous units still use flattened price and cap only mutates `chargedAmount`

- [ ] **Step 3: Build valueSpec before and after cap application**

```java
private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
    List<BillingUnit> units = new ArrayList<>();
    for (TimeFragment fragment : cycle.fragments) {
        if (fragment.isFree) {
            units.add(BillingUnit.builder()
                    .beginTime(fragment.beginTime)
                    .endTime(fragment.endTime)
                    .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                    .unitPrice(BigDecimal.ZERO)
                    .originalAmount(BigDecimal.ZERO)
                    .free(true)
                    .freePromotionId(fragment.freePromotionId)
                    .chargedAmount(BigDecimal.ZERO)
                    .valueSpec(FixedValueSpec.builder().finalAmount(BigDecimal.ZERO).unitEndTime(fragment.endTime).build())
                    .build());
            continue;
        }
        units.addAll(generateChargeableUnitsForFragment(fragment, config));
    }
    return units;
}
```

```java
private void applyCapToHitUnitValueSpec(BillingUnit unit, BigDecimal cappedFinalAmount) {
    unit.setChargedAmount(cappedFinalAmount);
    unit.setValueSpec(UnitValueSpecs.cap(unit.getValueSpec(), cappedFinalAmount, unit.getEndTime()));
    if (cappedFinalAmount.compareTo(BigDecimal.ZERO) == 0) {
        unit.setFree(true);
        unit.setFreePromotionId("DAILY_CAP");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl bill-test -Dtest=DayNightQueryValueTest,UnitValueEvaluatorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java
git commit -m "feat: encode day-night continuous valuation and cap into value specs"
```

### Task 7: Replace ConditionalFree Query Logic With StepValueSpec

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionRule.java`
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- Modify: `bill-test/src/main/java/cn/shang/charging/ConditionalFreePromotionTest.java`
- Modify: `bill-test/src/main/java/cn/shang/charging/StartFreePromotionTest.java`

- [ ] **Step 1: Write failing test proving viewer no longer depends on `conditionalFree` fields**

```java
@Test
void createQuerySummary_handlesStartFreeThroughStepValueSpecOnly() {
    // create a start-free unit with StepValueSpec and no conditionalFree fields
    // assert viewer returns 0 before switch and full amount after switch
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest test`

Expected: FAIL because legacy conditional-free code path still expects `conditionalFree` fields

- [ ] **Step 3: Remove conditional-free fields from BillingUnit and delete legacy viewer patching**

```java
// BillingUnit: delete conditionalFree / conditionalFreeUntil
```

```java
// BillingResultViewer: delete applyQueryTimeValidation(), cloneUnit(), resolveUnitCharge(), calculatePartialCoverageMinutes()
// keep only evaluator-driven logic
```

```java
// DayNight / start-free integration: emit StepValueSpec where start-free should behave as "free until boundary, normal after boundary"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl bill-test -Dtest=BillingResultViewerTest,DayNightQueryValueTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionRule.java bill-test/src/main/java/cn/shang/charging/ConditionalFreePromotionTest.java bill-test/src/main/java/cn/shang/charging/StartFreePromotionTest.java
git commit -m "refactor: replace conditional free query path with value specs"
```

### Task 8: Update Documentation And Final Regression Checks

**Files:**
- Modify: `docs/USER_GUIDE.md`
- Modify: `README.md`
- Modify: `README_CN.md`
- Test: `bill-test/src/test/java/cn/shang/charging/UnitValueEvaluatorTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java`

- [ ] **Step 1: Update docs to match shipped query semantics**

```md
## Query-Time Valuation

- Query is valid only when `queryTime <= calculationEndTime`
- Query amount for the hit unit is computed from `valueSpec`
- `chargedAmount` is the final full-unit amount
- `accumulatedAmount` is the full-unit cumulative amount
- If the hit unit is simplified, `billing-api` performs an exact recalculation before answering
```

- [ ] **Step 2: Run focused regression suite**

Run: `mvn -pl bill-test -Dtest=UnitValueEvaluatorTest,BillingResultViewerTest,DayNightQueryValueTest test`

Expected: PASS

- [ ] **Step 3: Run broader bill-test module tests**

Run: `mvn -pl bill-test test`

Expected: PASS or a short, reviewed failure list limited to unrelated pre-existing tests

- [ ] **Step 4: Commit**

```bash
git add docs/USER_GUIDE.md README.md README_CN.md bill-test/src/test/java/cn/shang/charging/UnitValueEvaluatorTest.java bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java
git commit -m "docs: describe value spec query semantics"
```

## Self-Review

### Spec Coverage

- Query-time exact amount from existing units: covered by Tasks 1, 2, 3
- `chargedAmount` / `accumulatedAmount` semantic rewrite: covered by Task 2
- `simplified unit` exact-query fallback: covered by Task 4
- `DayNightRule` mixed-unit valuation: covered by Tasks 5 and 6
- Cap included in `valueSpec`: covered by Task 6
- Start-free / conditional-free absorbed into unified model: covered by Task 7
- `effectiveTo` from next change time: covered by Task 3
- Docs/test updates: covered by Task 8

No uncovered requirement remains in the first-slice scope.

### Placeholder Scan

- No `TODO` / `TBD`
- Every task includes exact files
- Every code-changing step includes actual code snippets
- Every verification step includes an exact command and expected result

### Type Consistency

- Public protocol names are consistent across tasks:
  - `UnitValueSpec`
  - `UnitValueProjection`
  - `UnitValueEvaluator`
  - `valueSpec`
- Query formula consistently uses:
  - `chargedAmount` = final full-unit amount
  - `accumulatedAmount` = full-unit cumulative amount

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-20-unit-value-spec-implementation.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
