# 计费单元延伸实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将最后一个被截断的计费单元延伸到最近的边界（周期边界或时间段边界），为缓存有效性和 CONTINUE 模式提供支持。

**Architecture:** 在规则计算时识别最后一个计费单元，查找下一个边界（周期边界和时间段边界取最近），延伸单元结束时间并设置 `calculationEndTime`。

**Tech Stack:** Java 17, Lombok, Maven

**Spec:** `docs/superpowers/specs/2026-03-13-billing-unit-extension-design.md`

---

## 文件结构

```
core/src/main/java/cn/shang/charging/
├── billing/pojo/
│   ├── BillingResult.java          # 新增 calculationEndTime 字段
│   └── BillingSegmentResult.java   # 已有 calculationEndTime，需修改设置逻辑
├── settlement/
│   └── ResultAssembler.java        # 汇总 calculationEndTime
└── charge/rules/
    ├── relativetime/
    │   └── RelativeTimeRule.java   # 实现延伸逻辑
    └── daynight/
        └── DayNightRule.java       # 实现延伸逻辑

bill-test/src/main/java/cn/shang/charging/
└── BillingUnitExtensionTest.java   # 新增测试类
```

---

## Chunk 1: 数据结构变更

### Task 1: BillingResult 新增字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java`

- [ ] **Step 1: 新增 calculationEndTime 字段**

```java
// 在 BillingResult 类中添加

/**
 * 实际计算到的时间点（延伸后，用于缓存有效性判断和 CONTINUE 起点）
 * 最后一个计费单元延伸后的结束时间
 */
private LocalDateTime calculationEndTime;
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
git commit -m "feat(BillingResult): 新增 calculationEndTime 字段

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2: ResultAssembler 汇总 calculationEndTime

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`

- [ ] **Step 1: 添加汇总方法**

在 `ResultAssembler` 类中添加：

```java
/**
 * 汇总 calculationEndTime
 * 取最后一个分段的 calculationEndTime
 */
private LocalDateTime calculateCalculationEndTime(List<BillingSegmentResult> segmentResultList) {
    if (segmentResultList == null || segmentResultList.isEmpty()) {
        return null;
    }
    // 取最后一个分段的 calculationEndTime
    for (int i = segmentResultList.size() - 1; i >= 0; i--) {
        LocalDateTime time = segmentResultList.get(i).getCalculationEndTime();
        if (time != null) {
            return time;
        }
    }
    return null;
}
```

- [ ] **Step 2: 在 assemble 方法中使用**

修改 `assemble` 方法：

```java
// 在 BillingResult.builder() 中添加
.calculationEndTime(calculateCalculationEndTime(segmentResultList))
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
git commit -m "feat(ResultAssembler): 汇总 calculationEndTime

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: RelativeTimeRule 延伸逻辑

### Task 3: 实现延伸逻辑（UNIT_BASED 模式）

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 添加查找下一个时间段边界的方法**

在 `RelativeTimeRule` 类中添加：

```java
/**
 * 查找下一个时间段边界
 * @param current 当前时间点
 * @param calcBegin 计费起点
 * @param config 规则配置
 * @return 下一个时间段边界时间，如果没有则返回 null
 */
private LocalDateTime findNextPeriodBoundary(LocalDateTime current, LocalDateTime calcBegin, RelativeTimeConfig config) {
    if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
        return null;
    }

    // 计算当前时间相对于计费起点的分钟数
    long minutesFromStart = Duration.between(calcBegin, current).toMinutes();

    // 遍历所有时间段，找到第一个大于当前位置的边界
    for (RelativeTimePeriod period : config.getPeriods()) {
        // 时间段结束边界
        long periodEndMinute = period.getEndMinute();
        LocalDateTime periodBoundary = calcBegin.plusMinutes(periodEndMinute);

        if (periodBoundary.isAfter(current)) {
            return periodBoundary;
        }
    }

    // 如果当前周期内没有，检查下一个周期
    // 下一个周期的第一个时间段边界
    RelativeTimePeriod firstPeriod = config.getPeriods().get(0);
    return calcBegin.plusMinutes(MINUTES_PER_CYCLE).plusMinutes(firstPeriod.getBeginMinute());
}
```

- [ ] **Step 2: 添加查找下一个周期边界的方法**

```java
/**
 * 查找下一个周期边界
 * @param current 当前时间点
 * @param calcBegin 计费起点
 * @return 下一个周期边界时间（24小时后）
 */
private LocalDateTime findNextCycleBoundary(LocalDateTime current, LocalDateTime calcBegin) {
    // 找到包含 current 的周期起点
    LocalDateTime cycleStart = calcBegin;
    while (cycleStart.plusMinutes(MINUTES_PER_CYCLE).isBefore(current) ||
           cycleStart.plusMinutes(MINUTES_PER_CYCLE).equals(current)) {
        cycleStart = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
    }
    // 下一个周期边界
    return cycleStart.plusMinutes(MINUTES_PER_CYCLE);
}
```

- [ ] **Step 3: 添加延伸最后一个单元的方法**

```java
/**
 * 延伸最后一个计费单元
 * @param allUnits 所有计费单元
 * @param calcBegin 计费起点
 * @param calcEnd 计算结束时间（原截断点）
 * @param freeTimeRanges 免费时段列表
 * @param config 规则配置
 * @return 延伸后的 calculationEndTime
 */
private LocalDateTime extendLastUnit(List<BillingUnit> allUnits,
                                     LocalDateTime calcBegin,
                                     LocalDateTime calcEnd,
                                     List<FreeTimeRange> freeTimeRanges,
                                     RelativeTimeConfig config) {
    if (allUnits == null || allUnits.isEmpty()) {
        return calcEnd;
    }

    BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);

    // 如果最后一个单元没有被截断（结束时间 == 计算结束时间），需要延伸
    // 只有当结束时间等于 calcEnd 时才需要延伸
    if (!lastUnit.getEndTime().equals(calcEnd)) {
        // 单元已经被其他边界截断，不需要延伸
        return lastUnit.getEndTime();
    }

    // 查找下一个边界
    LocalDateTime nextPeriodBoundary = findNextPeriodBoundary(calcEnd, calcBegin, config);
    LocalDateTime nextCycleBoundary = findNextCycleBoundary(calcEnd, calcBegin);

    // 取最近的边界
    LocalDateTime nextBoundary = null;
    if (nextPeriodBoundary != null && nextCycleBoundary != null) {
        nextBoundary = nextPeriodBoundary.isBefore(nextCycleBoundary) ? nextPeriodBoundary : nextCycleBoundary;
    } else if (nextPeriodBoundary != null) {
        nextBoundary = nextPeriodBoundary;
    } else if (nextCycleBoundary != null) {
        nextBoundary = nextCycleBoundary;
    }

    // 如果没有找到边界，不延伸
    if (nextBoundary == null || !nextBoundary.isAfter(calcEnd)) {
        return calcEnd;
    }

    // 检查是否被免费时段覆盖到延伸终点
    String freePromotionId = findFreePromotionId(calcEnd, nextBoundary, freeTimeRanges);
    boolean extendedIsFree = freePromotionId != null;

    // 更新最后一个单元的结束时间
    int extendedDuration = (int) Duration.between(lastUnit.getBeginTime(), nextBoundary).toMinutes();

    // 创建新的延伸后单元（替换原单元）
    BillingUnit extendedUnit = BillingUnit.builder()
            .beginTime(lastUnit.getBeginTime())
            .endTime(nextBoundary)
            .durationMinutes(extendedDuration)
            .unitPrice(lastUnit.getUnitPrice())
            .originalAmount(lastUnit.getOriginalAmount()) // 金额不变
            .chargedAmount(lastUnit.getChargedAmount())   // 金额不变
            .free(lastUnit.isFree())
            .freePromotionId(lastUnit.getFreePromotionId())
            .build();

    // 替换最后一个单元
    allUnits.set(allUnits.size() - 1, extendedUnit);

    return nextBoundary;
}
```

- [ ] **Step 4: 修改 calculateUnitBased 方法**

在 `calculateUnitBased` 方法中，修改最后部分：

```java
// 原代码：
// return BillingSegmentResult.builder()
//         ...
//         .calculationEndTime(calcEnd)
//         ...

// 修改为：
// 延伸最后一个计费单元
LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, freeTimeRanges, config);

return BillingSegmentResult.builder()
        .segmentId(context.getSegment().getSchemeId())
        .segmentStartTime(context.getSegment().getBeginTime())
        .segmentEndTime(context.getSegment().getEndTime())
        .calculationStartTime(calcBegin)
        .calculationEndTime(extendedCalculationEndTime)  // 使用延伸后的时间
        .chargedAmount(totalAmount)
        .billingUnits(allUnits)
        .promotionUsages(new ArrayList<>())
        .promotionAggregate(promotionAggregate)
        .feeEffectiveStart(feeEffectiveStart)
        .feeEffectiveEnd(feeEffectiveEnd)
        .build();
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "feat(RelativeTimeRule): 实现计费单元延伸逻辑（UNIT_BASED模式）

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 4: 实现延伸逻辑（CONTINUOUS 模式）

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 找到 calculateContinuous 方法**

阅读 `calculateContinuous` 方法，理解其结构。

- [ ] **Step 2: 在 calculateContinuous 方法末尾添加延伸逻辑**

首先阅读 `calculateContinuous` 方法，找到返回 `BillingSegmentResult` 的位置（大约在第 410-422 行）。

修改代码如下：

```java
// 原代码（大约在返回前）：
// return BillingSegmentResult.builder()
//         ...
//         .calculationEndTime(calcEnd)
//         ...

// 在返回之前，添加延伸逻辑：
LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, freeTimeRanges, config);

// 如果 effectiveTo 早于延伸后的时间，更新 effectiveTo
if (extendedCalculationEndTime.isAfter(feeEffectiveEnd)) {
    feeEffectiveEnd = extendedCalculationEndTime;
}

// 然后返回，使用延伸后的时间
return BillingSegmentResult.builder()
        .segmentId(context.getSegment().getSchemeId())
        .segmentStartTime(context.getSegment().getBeginTime())
        .segmentEndTime(context.getSegment().getEndTime())
        .calculationStartTime(calcBegin)
        .calculationEndTime(extendedCalculationEndTime)  // 使用延伸后的时间
        .chargedAmount(totalAmount)
        .billingUnits(allUnits)
        .promotionUsages(new ArrayList<>())
        .promotionAggregate(promotionAggregate)
        .feeEffectiveStart(feeEffectiveStart)
        .feeEffectiveEnd(feeEffectiveEnd)
        .build();
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "feat(RelativeTimeRule): 实现计费单元延伸逻辑（CONTINUOUS模式）

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: DayNightRule 延伸逻辑

### Task 5: DayNightRule 实现延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 阅读现有代码**

阅读 `DayNightRule` 类（`core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`），重点关注：
- `calculateUnitBased` 方法：找到返回 `BillingSegmentResult` 的位置（大约在第 100-102 行）
- `calculateContinuous` 方法：找到返回 `BillingSegmentResult` 的位置
- 理解 `allUnits`、`calcBegin`、`calcEnd`、`freeTimeRanges` 变量的来源

- [ ] **Step 2: 添加辅助方法**

添加与 `RelativeTimeRule` 类似的辅助方法：

```java
/**
 * 查找下一个周期边界
 * DayNightRule 的周期边界是计费起点 + 24小时
 */
private LocalDateTime findNextCycleBoundary(LocalDateTime current, LocalDateTime calcBegin) {
    LocalDateTime cycleStart = calcBegin;
    while (cycleStart.plusHours(24).isBefore(current) ||
           cycleStart.plusHours(24).equals(current)) {
        cycleStart = cycleStart.plusHours(24);
    }
    return cycleStart.plusHours(24);
}

/**
 * 延伸最后一个计费单元
 */
private LocalDateTime extendLastUnit(List<BillingUnit> allUnits,
                                     LocalDateTime calcBegin,
                                     LocalDateTime calcEnd,
                                     List<FreeTimeRange> freeTimeRanges) {
    if (allUnits == null || allUnits.isEmpty()) {
        return calcEnd;
    }

    BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);

    // 只有当结束时间等于 calcEnd 时才需要延伸
    if (!lastUnit.getEndTime().equals(calcEnd)) {
        return lastUnit.getEndTime();
    }

    // DayNightRule 只有周期边界，没有时间段边界
    LocalDateTime nextBoundary = findNextCycleBoundary(calcEnd, calcBegin);

    if (nextBoundary == null || !nextBoundary.isAfter(calcEnd)) {
        return calcEnd;
    }

    // 更新最后一个单元
    int extendedDuration = (int) Duration.between(lastUnit.getBeginTime(), nextBoundary).toMinutes();

    BillingUnit extendedUnit = BillingUnit.builder()
            .beginTime(lastUnit.getBeginTime())
            .endTime(nextBoundary)
            .durationMinutes(extendedDuration)
            .unitPrice(lastUnit.getUnitPrice())
            .originalAmount(lastUnit.getOriginalAmount())
            .chargedAmount(lastUnit.getChargedAmount())
            .free(lastUnit.isFree())
            .freePromotionId(lastUnit.getFreePromotionId())
            .build();

    allUnits.set(allUnits.size() - 1, extendedUnit);

    return nextBoundary;
}
```

- [ ] **Step 3: 修改 calculateUnitBased 方法**

DayNightRule 有两个计算方法：`calculateUnitBased`（UNIT_BASED 模式）和 `calculateContinuous`（CONTINUOUS 模式）。

对于 `calculateUnitBased` 方法，找到返回 `BillingSegmentResult` 的位置，修改如下：

```java
// 在返回之前，添加延伸逻辑：
LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, freeTimeRanges);

return BillingSegmentResult.builder()
        .segmentId(context.getSegment().getSchemeId())
        .segmentStartTime(context.getSegment().getBeginTime())
        .segmentEndTime(context.getSegment().getEndTime())
        .calculationStartTime(calcBegin)
        .calculationEndTime(extendedCalculationEndTime)  // 使用延伸后的时间
        .chargedAmount(totalAmount)
        .billingUnits(allUnits)
        .promotionUsages(new ArrayList<>())
        .promotionAggregate(promotionAggregate)
        .feeEffectiveStart(feeEffectiveStart)
        .feeEffectiveEnd(feeEffectiveEnd)
        .build();
```

- [ ] **Step 4: 修改 calculateContinuous 方法**

类似地，在 `calculateContinuous` 方法中也添加延伸逻辑：

```java
LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, freeTimeRanges);

// 如果 effectiveTo 早于延伸后的时间，更新 effectiveTo
if (extendedCalculationEndTime.isAfter(feeEffectiveEnd)) {
    feeEffectiveEnd = extendedCalculationEndTime;
}

return BillingSegmentResult.builder()
        .segmentId(context.getSegment().getSchemeId())
        .segmentStartTime(context.getSegment().getBeginTime())
        .segmentEndTime(context.getSegment().getEndTime())
        .calculationStartTime(calcBegin)
        .calculationEndTime(extendedCalculationEndTime)  // 使用延伸后的时间
        .chargedAmount(totalAmount)
        .billingUnits(allUnits)
        .promotionUsages(new ArrayList<>())
        .promotionAggregate(promotionAggregate)
        .feeEffectiveStart(feeEffectiveStart)
        .feeEffectiveEnd(feeEffectiveEnd)
        .build();
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "feat(DayNightRule): 实现计费单元延伸逻辑

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 4: 测试验证

### Task 6: 创建测试类

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/BillingUnitExtensionTest.java`

- [ ] **Step 1: 创建测试类**

```java
package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费单元延伸测试
 */
public class BillingUnitExtensionTest {

    public static void main(String[] args) {
        System.out.println("========== 计费单元延伸测试 ==========\n");

        test1_ExtendToPeriodBoundary();
        test2_ExtendToCycleBoundary();
        test3_NoExtensionNeeded_BoundaryEqualsEnd();
        test4_FreeTimeRangeExtension();
        test5_UnitBasedMode();
    }

    /**
     * 测试1: 延伸到时间段边界
     */
    static void test1_ExtendToPeriodBoundary() {
        System.out.println("=== 测试1: 延伸到时间段边界 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-1");
        // 计费时间: 08:00-09:00 (1小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: period1 = 0-120分钟, period2 = 120-1440分钟");
        System.out.println("下一个时间段边界: 10:00 (120分钟边界)");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00");
        System.out.println("最后一个单元: " + result.getUnits().get(result.getUnits().size() - 1).getBeginTime() +
                " - " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
        System.out.println("收费金额: " + result.getFinalAmount());
        System.out.println();
    }

    /**
     * 测试2: 单元完整不延伸（边界恰好等于结束时间）
     */
    static void test2_ExtendToCycleBoundary() {
        System.out.println("=== 测试2: 无时间段边界，延伸到周期边界 ===");

        // 使用没有时间段边界限制的配置
        var billingService = getBillingServiceNoPeriodBoundary();
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: 无时间段边界，只有周期边界");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 次日 08:00（24小时周期边界）");
        System.out.println();
    }

    /**
     * 测试3: 边界恰好等于结束时间，不延伸
     * 对应 spec 测试3: 单元长度60分钟，计费结束时间09:00，下一个边界也是09:00
     */
    static void test3_NoExtensionNeeded_BoundaryEqualsEnd() {
        System.out.println("=== 测试3: 边界恰好等于结束时间，不延伸 ===");

        // 配置: period1 = 0-60分钟 (边界在09:00), period2 = 60-1440分钟
        var billingService = getBillingServiceWithBoundaryAt9();
        var request = new BillingRequest();
        // 计费时间: 08:00-09:00 (恰好到边界)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: period1 = 0-60分钟 (边界在09:00)");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 09:00 (无延伸，边界恰好等于结束时间)");
        System.out.println("最后一个单元结束时间: " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
        System.out.println();
    }

    /**
     * 测试4: 免费时段覆盖的单元延伸
     */
    static void test4_FreeTimeRangeExtension() {
        System.out.println("=== 测试4: 免费时段覆盖的单元延伸 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 免费时段: 08:00-10:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("免费时段: 08:00-10:00");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00");
        System.out.println("收费金额: " + result.getFinalAmount() + " (免费)");
        System.out.println();
    }

    /**
     * 测试5: UNIT_BASED 模式下的延伸
     */
    static void test5_UnitBasedMode() {
        System.out.println("=== 测试5: UNIT_BASED 模式下的延伸 ===");

        var billingService = getBillingServiceUnitBased();
        var request = new BillingRequest();
        // 计费时间: 08:00-09:00 (1小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("模式: UNIT_BASED");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00 (延伸到时间段边界)");
        System.out.println("最后一个单元: " + result.getUnits().get(result.getUnits().size() - 1).getBeginTime() +
                " - " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
        System.out.println();
    }

    static BillingService getBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-1")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceNoPeriodBoundary() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // 只有一个时间段，覆盖整个周期
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-2")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceWithBoundaryAt9() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // period1 = 0-60分钟 (边界在 09:00), period2 = 60-1440分钟
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(60)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(60)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-3")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceUnitBased() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-unit-based")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn clean install -DskipTests -q && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.BillingUnitExtensionTest" -q`

Expected: 所有测试输出符合预期

- [ ] **Step 3: Commit**

```bash
git add bill-test/src/main/java/cn/shang/charging/BillingUnitExtensionTest.java
git commit -m "test: 添加计费单元延伸测试

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 7: 运行现有测试确保无回归

- [ ] **Step 1: 运行 RelativeTimeTest**

Run: `mvn clean install -DskipTests -q && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.RelativeTimeTest" -q`

Expected: 所有测试通过

- [ ] **Step 2: 运行 DayNightTest**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.DayNightTest" -q`

Expected: 所有测试通过

- [ ] **Step 3: 运行 PromotionTest**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.PromotionTest" -q`

Expected: 所有测试通过

---

## 完成检查

- [ ] BillingResult 包含 `calculationEndTime` 字段
- [ ] BillingSegmentResult 的 `calculationEndTime` 设置为延伸后的时间
- [ ] ResultAssembler 正确汇总 `calculationEndTime`
- [ ] RelativeTimeRule 实现延伸逻辑（UNIT_BASED 和 CONTINUOUS 模式）
- [ ] DayNightRule 实现延伸逻辑
- [ ] 所有测试通过
- [ ] 无回归问题