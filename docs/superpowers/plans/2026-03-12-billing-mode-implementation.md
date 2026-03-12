# 计费模式差异化实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 DayNightRule 和 RelativeTimeRule 中实现 UNIT_BASED 和 CONTINUOUS 两种计费模式的差异化计算。

**Architecture:** 在每个规则的 calculate() 方法中根据 billingMode 分发到 calculateUnitBased() 或 calculateContinuous()。CONTINUOUS 模式先按免费时段边界切分时间轴，再对每个片段从片段起点按单元长度划分计费单元。

**Tech Stack:** Java 17+, Lombok, Maven

---

## Chunk 1: DayNightRule 重构与 CONTINUOUS 实现

### Task 1: 重构 DayNightRule 支持模式分发

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 修改 calculate() 方法，添加模式分发逻辑**

将现有 calculate() 方法改为 calculateUnitBased()，并在 calculate() 中根据 billingMode 分发：

```java
@Override
public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
        return calculateUnitBased(context, config, promotionAggregate);
    } else {
        return calculateContinuous(context, config, promotionAggregate);
    }
}
```

- [ ] **Step 2: 将现有逻辑重命名为 calculateUnitBased()**

将原 calculate() 方法的主体逻辑移入新的私有方法 calculateUnitBased()：

```java
/**
 * UNIT_BASED 模式计算
 */
private BillingSegmentResult calculateUnitBased(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    // 获取计算窗口
    LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
    LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

    // 按周期和单元划分，生成计费单元
    List<UnitWithContext> unitsWithContext = buildUnitsWithContext(calcBegin, calcEnd, config);

    // 获取免费时段
    List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
    if (freeTimeRanges == null) {
        freeTimeRanges = List.of();
    }

    // 计算每个单元
    List<BillingUnit> billingUnits = new ArrayList<>();
    List<PromotionUsage> promotionUsages = new ArrayList<>();

    for (UnitWithContext unitCtx : unitsWithContext) {
        BillingUnit unit = calculateUnit(unitCtx, config, freeTimeRanges);
        billingUnits.add(unit);
    }

    // 按周期应用封顶
    applyDailyCap(billingUnits, config);

    // 汇总结果
    BigDecimal totalAmount = billingUnits.stream()
            .map(BillingUnit::getChargedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    // 计算费用稳定时间窗口
    LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
    LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

    return BillingSegmentResult.builder()
            .segmentId(context.getSegment().getSchemeId())
            .segmentStartTime(context.getSegment().getBeginTime())
            .segmentEndTime(context.getSegment().getEndTime())
            .calculationStartTime(calcBegin)
            .calculationEndTime(calcEnd)
            .chargedAmount(totalAmount)
            .billingUnits(billingUnits)
            .promotionUsages(promotionUsages)
            .promotionAggregate(promotionAggregate)
            .feeEffectiveStart(feeEffectiveStart)
            .feeEffectiveEnd(feeEffectiveEnd)
            .build();
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "refactor: DayNightRule 添加计费模式分发逻辑 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

### Task 2: 实现 DayNightRule 的 CONTINUOUS 模式

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 添加 calculateContinuous() 方法框架**

```java
/**
 * CONTINUOUS 模式计算
 * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
 */
private BillingSegmentResult calculateContinuous(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    // 获取计算窗口
    LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
    LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

    // 获取免费时段
    List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
    if (freeTimeRanges == null) {
        freeTimeRanges = List.of();
    }

    // 按免费时段边界切分时间轴
    List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

    // 按周期组织片段
    List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, config);

    // 对每个周期的片段生成计费单元并应用封顶
    List<BillingUnit> allUnits = new ArrayList<>();
    for (CycleFragments cycle : cycles) {
        List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
        applyContinuousCap(cycleUnits, config.getMaxChargeOneDay());
        allUnits.addAll(cycleUnits);
    }

    // 汇总结果
    BigDecimal totalAmount = allUnits.stream()
            .map(BillingUnit::getChargedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
    LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

    return BillingSegmentResult.builder()
            .segmentId(context.getSegment().getSchemeId())
            .segmentStartTime(context.getSegment().getBeginTime())
            .segmentEndTime(context.getSegment().getEndTime())
            .calculationStartTime(calcBegin)
            .calculationEndTime(calcEnd)
            .chargedAmount(totalAmount)
            .billingUnits(allUnits)
            .promotionUsages(new ArrayList<>())
            .promotionAggregate(promotionAggregate)
            .feeEffectiveStart(feeEffectiveStart)
            .feeEffectiveEnd(feeEffectiveEnd)
            .build();
}
```

- [ ] **Step 2: 添加时间片段内部类**

```java
/**
 * 时间片段（切分后的时间范围）
 */
private static class TimeFragment {
    LocalDateTime beginTime;
    LocalDateTime endTime;
    boolean isFree;
    String freePromotionId;  // 如果是免费片段，记录优惠ID

    TimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.isFree = false;
        this.freePromotionId = null;
    }
}

/**
 * 周期片段容器
 */
private static class CycleFragments {
    final LocalDateTime cycleStart;
    final LocalDateTime cycleEnd;
    final List<TimeFragment> fragments = new ArrayList<>();

    CycleFragments(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
        this.cycleStart = cycleStart;
        this.cycleEnd = cycleEnd;
    }
}
```

- [ ] **Step 3: 实现 splitTimeAxis() 方法**

```java
/**
 * 按免费时段边界切分时间轴
 */
private List<TimeFragment> splitTimeAxis(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
    List<TimeFragment> fragments = new ArrayList<>();

    // 收集所有切分点（免费时段边界）
    List<LocalDateTime> cutPoints = new ArrayList<>();
    cutPoints.add(begin);

    for (FreeTimeRange range : freeTimeRanges) {
        // 只处理在计费范围内的免费时段
        if (range.getBeginTime().isAfter(end) || range.getEndTime().isBefore(begin)) {
            continue;
        }
        if (range.getBeginTime().isAfter(begin) && range.getBeginTime().isBefore(end)) {
            cutPoints.add(range.getBeginTime());
        }
        if (range.getEndTime().isAfter(begin) && range.getEndTime().isBefore(end)) {
            cutPoints.add(range.getEndTime());
        }
    }

    cutPoints.add(end);

    // 去重并排序
    cutPoints = cutPoints.stream().distinct().sorted().toList();

    // 生成片段
    for (int i = 0; i < cutPoints.size() - 1; i++) {
        LocalDateTime fragBegin = cutPoints.get(i);
        LocalDateTime fragEnd = cutPoints.get(i + 1);

        TimeFragment fragment = new TimeFragment(fragBegin, fragEnd);

        // 检查是否匹配某个免费时段
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(fragBegin) && !range.getEndTime().isBefore(fragEnd)) {
                fragment.isFree = true;
                fragment.freePromotionId = range.getId();
                break;
            }
        }

        fragments.add(fragment);
    }

    return fragments;
}
```

- [ ] **Step 4: 实现 organizeByCycle() 方法**

```java
/**
 * 按周期组织片段
 */
private List<CycleFragments> organizeByCycle(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                              List<TimeFragment> fragments, DayNightConfig config) {
    List<CycleFragments> cycles = new ArrayList<>();

    LocalDateTime cycleStart = calcBegin;
    LocalDateTime cycleEnd = calcBegin.plusHours(24);

    CycleFragments currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

    for (TimeFragment fragment : fragments) {
        // 检查片段是否跨越周期边界
        while (fragment.endTime.isAfter(currentCycle.cycleEnd)) {
            // 切分片段
            TimeFragment beforeBoundary = new TimeFragment(fragment.beginTime, currentCycle.cycleEnd);
            beforeBoundary.isFree = fragment.isFree;
            beforeBoundary.freePromotionId = fragment.freePromotionId;

            currentCycle.fragments.add(beforeBoundary);
            cycles.add(currentCycle);

            // 开始新周期
            cycleStart = currentCycle.cycleEnd;
            cycleEnd = cycleStart.plusHours(24);
            currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

            // 更新原片段
            fragment.beginTime = currentCycle.cycleStart;
        }

        currentCycle.fragments.add(fragment);
    }

    if (!currentCycle.fragments.isEmpty()) {
        cycles.add(currentCycle);
    }

    return cycles;
}
```

- [ ] **Step 5: 实现 generateUnitsForCycle() 方法**

```java
/**
 * 为一个周期生成计费单元
 */
private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
    List<BillingUnit> units = new ArrayList<>();
    int unitMinutes = config.getUnitMinutes();

    for (TimeFragment fragment : cycle.fragments) {
        if (fragment.isFree) {
            // 免费片段直接生成一个免费单元
            BillingUnit unit = BillingUnit.builder()
                    .beginTime(fragment.beginTime)
                    .endTime(fragment.endTime)
                    .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                    .unitPrice(BigDecimal.ZERO)
                    .originalAmount(BigDecimal.ZERO)
                    .free(true)
                    .freePromotionId(fragment.freePromotionId)
                    .chargedAmount(BigDecimal.ZERO)
                    .ruleData(cycle.fragments.indexOf(fragment))
                    .build();
            units.add(unit);
        } else {
            // 收费片段按单元长度划分
            LocalDateTime current = fragment.beginTime;
            while (current.isBefore(fragment.endTime)) {
                LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
                if (unitEnd.isAfter(fragment.endTime)) {
                    unitEnd = fragment.endTime;
                }

                int duration = (int) Duration.between(current, unitEnd).toMinutes();

                // 确定单价
                BigDecimal unitPrice = determineUnitPrice(current, unitEnd, config);

                // 不足单元也收全额
                BigDecimal originalAmount = unitPrice;

                BillingUnit unit = BillingUnit.builder()
                        .beginTime(current)
                        .endTime(unitEnd)
                        .durationMinutes(duration)
                        .unitPrice(unitPrice)
                        .originalAmount(originalAmount)
                        .free(false)
                        .chargedAmount(originalAmount)
                        .ruleData(cycle.fragments.indexOf(fragment))
                        .build();

                units.add(unit);
                current = unitEnd;
            }
        }
    }

    return units;
}

/**
 * 确定时段单价
 */
private BigDecimal determineUnitPrice(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
    PeriodType periodType = determinePeriodType(begin, end, config);

    if (periodType == PeriodType.DAY) {
        return config.getDayUnitPrice();
    } else if (periodType == PeriodType.NIGHT) {
        return config.getNightUnitPrice();
    } else {
        // MIXED: 根据时长比例判断
        int[] mins = calculateDayNightMinutes(begin, end, config);
        int duration = (int) Duration.between(begin, end).toMinutes();
        BigDecimal ratio = BigDecimal.valueOf(mins[0])
                .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(config.getBlockWeight()) >= 0) {
            return config.getDayUnitPrice();
        } else {
            return config.getNightUnitPrice();
        }
    }
}
```

- [ ] **Step 6: 实现 applyContinuousCap() 方法**

```java
/**
 * CONTINUOUS 模式封顶处理
 * 封顶后截止，剩余时间合并为免费单元
 */
private void applyContinuousCap(List<BillingUnit> units, BigDecimal maxCharge) {
    if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
        return;
    }

    BigDecimal accumulated = BigDecimal.ZERO;
    int capIndex = -1;
    BigDecimal lastChargeAmount = null;

    // 找到封顶位置
    for (int i = 0; i < units.size(); i++) {
        BillingUnit unit = units.get(i);
        if (unit.isFree()) {
            continue;
        }

        BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

        if (newAccumulated.compareTo(maxCharge) >= 0) {
            // 超过封顶
            capIndex = i;
            lastChargeAmount = maxCharge.subtract(accumulated);
            break;
        }

        accumulated = newAccumulated;
    }

    if (capIndex < 0) {
        return; // 未超过封顶
    }

    // 调整封顶单元的金额
    units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));

    // 封顶后的单元合并为一个免费单元
    if (capIndex < units.size() - 1) {
        BillingUnit firstAfterCap = units.get(capIndex + 1);
        BillingUnit lastAfterCap = units.get(units.size() - 1);

        BillingUnit mergedFreeUnit = BillingUnit.builder()
                .beginTime(firstAfterCap.getBeginTime())
                .endTime(lastAfterCap.getEndTime())
                .durationMinutes((int) Duration.between(firstAfterCap.getBeginTime(), lastAfterCap.getEndTime()).toMinutes())
                .unitPrice(BigDecimal.ZERO)
                .originalAmount(BigDecimal.ZERO)
                .free(true)
                .freePromotionId("DAILY_CAP")
                .chargedAmount(BigDecimal.ZERO)
                .build();

        // 移除封顶后的单元，添加合并后的免费单元
        units.subList(capIndex + 1, units.size()).clear();
        units.add(mergedFreeUnit);
    }
}
```

- [ ] **Step 7: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 8: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "feat: DayNightRule 实现 CONTINUOUS 计费模式 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 2: RelativeTimeRule 重构与 CONTINUOUS 实现

### Task 3: 重构 RelativeTimeRule 支持模式分发

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 修改 calculate() 方法，添加模式分发逻辑**

```java
@Override
public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
    if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
        return calculateUnitBased(context, config, promotionAggregate);
    } else {
        return calculateContinuous(context, config, promotionAggregate);
    }
}
```

- [ ] **Step 2: 将现有逻辑重命名为 calculateUnitBased()**

将原 calculate() 方法的主体逻辑移入新的私有方法 calculateUnitBased()。

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "refactor: RelativeTimeRule 添加计费模式分发逻辑 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

### Task 4: 实现 RelativeTimeRule 的 CONTINUOUS 模式

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 添加 calculateContinuous() 方法**

参考 DayNightRule 的实现，但需要注意：
- RelativeTimeRule 使用 periods 配置，不同时段有不同的 unitMinutes 和 unitPrice
- 需要根据片段开始时间在周期内的偏移量查找对应的 period

```java
/**
 * CONTINUOUS 模式计算
 */
private BillingSegmentResult calculateContinuous(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
    validateConfig(config);

    CalculationWindow window = context.getWindow();
    LocalDateTime calcBegin = window.getCalculationBegin();
    LocalDateTime calcEnd = window.getCalculationEnd();

    List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
            ? promotionAggregate.getFreeTimeRanges()
            : List.of();

    // 按免费时段边界切分时间轴
    List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

    // 按周期组织片段
    List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments);

    // 对每个周期的片段生成计费单元并应用封顶
    List<BillingUnit> allUnits = new ArrayList<>();
    for (CycleFragments cycle : cycles) {
        List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
        applyContinuousCap(cycleUnits, config.getMaxChargeOneCycle());
        allUnits.addAll(cycleUnits);
    }

    BigDecimal totalAmount = allUnits.stream()
            .map(BillingUnit::getChargedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
    LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

    return BillingSegmentResult.builder()
            .segmentId(context.getSegment().getSchemeId())
            .segmentStartTime(context.getSegment().getBeginTime())
            .segmentEndTime(context.getSegment().getEndTime())
            .calculationStartTime(calcBegin)
            .calculationEndTime(calcEnd)
            .chargedAmount(totalAmount)
            .billingUnits(allUnits)
            .promotionUsages(new ArrayList<>())
            .promotionAggregate(promotionAggregate)
            .feeEffectiveStart(feeEffectiveStart)
            .feeEffectiveEnd(feeEffectiveEnd)
            .build();
}
```

- [ ] **Step 2: 添加时间片段内部类（与 DayNightRule 类似）**

```java
private static class TimeFragment {
    LocalDateTime beginTime;
    LocalDateTime endTime;
    boolean isFree;
    String freePromotionId;

    TimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.isFree = false;
        this.freePromotionId = null;
    }
}

private static class CycleFragments {
    final LocalDateTime cycleStart;
    final LocalDateTime cycleEnd;
    final List<TimeFragment> fragments = new ArrayList<>();

    CycleFragments(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
        this.cycleStart = cycleStart;
        this.cycleEnd = cycleEnd;
    }
}
```

- [ ] **Step 3: 实现 splitTimeAxis() 方法（与 DayNightRule 相同）**

```java
private List<TimeFragment> splitTimeAxis(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
    List<TimeFragment> fragments = new ArrayList<>();

    List<LocalDateTime> cutPoints = new ArrayList<>();
    cutPoints.add(begin);

    for (FreeTimeRange range : freeTimeRanges) {
        if (range.getBeginTime().isAfter(end) || range.getEndTime().isBefore(begin)) {
            continue;
        }
        if (range.getBeginTime().isAfter(begin) && range.getBeginTime().isBefore(end)) {
            cutPoints.add(range.getBeginTime());
        }
        if (range.getEndTime().isAfter(begin) && range.getEndTime().isBefore(end)) {
            cutPoints.add(range.getEndTime());
        }
    }

    cutPoints.add(end);
    cutPoints = cutPoints.stream().distinct().sorted().toList();

    for (int i = 0; i < cutPoints.size() - 1; i++) {
        LocalDateTime fragBegin = cutPoints.get(i);
        LocalDateTime fragEnd = cutPoints.get(i + 1);

        TimeFragment fragment = new TimeFragment(fragBegin, fragEnd);

        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(fragBegin) && !range.getEndTime().isBefore(fragEnd)) {
                fragment.isFree = true;
                fragment.freePromotionId = range.getId();
                break;
            }
        }

        fragments.add(fragment);
    }

    return fragments;
}
```

- [ ] **Step 4: 实现 organizeByCycle() 方法**

```java
private List<CycleFragments> organizeByCycle(LocalDateTime calcBegin, LocalDateTime calcEnd, List<TimeFragment> fragments) {
    List<CycleFragments> cycles = new ArrayList<>();

    LocalDateTime cycleStart = calcBegin;
    LocalDateTime cycleEnd = calcBegin.plusMinutes(MINUTES_PER_CYCLE);

    CycleFragments currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

    for (TimeFragment fragment : fragments) {
        while (fragment.endTime.isAfter(currentCycle.cycleEnd)) {
            TimeFragment beforeBoundary = new TimeFragment(fragment.beginTime, currentCycle.cycleEnd);
            beforeBoundary.isFree = fragment.isFree;
            beforeBoundary.freePromotionId = fragment.freePromotionId;

            currentCycle.fragments.add(beforeBoundary);
            cycles.add(currentCycle);

            cycleStart = currentCycle.cycleEnd;
            cycleEnd = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
            currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

            fragment.beginTime = currentCycle.cycleStart;
        }

        currentCycle.fragments.add(fragment);
    }

    if (!currentCycle.fragments.isEmpty()) {
        cycles.add(currentCycle);
    }

    return cycles;
}
```

- [ ] **Step 5: 实现 generateUnitsForCycle() 方法**

```java
private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, RelativeTimeConfig config) {
    List<BillingUnit> units = new ArrayList<>();

    for (TimeFragment fragment : cycle.fragments) {
        if (fragment.isFree) {
            BillingUnit unit = BillingUnit.builder()
                    .beginTime(fragment.beginTime)
                    .endTime(fragment.endTime)
                    .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                    .unitPrice(BigDecimal.ZERO)
                    .originalAmount(BigDecimal.ZERO)
                    .free(true)
                    .freePromotionId(fragment.freePromotionId)
                    .chargedAmount(BigDecimal.ZERO)
                    .build();
            units.add(unit);
        } else {
            // 根据片段开始时间查找对应的 period
            units.addAll(generateUnitsForFragment(fragment, cycle.cycleStart, config));
        }
    }

    return units;
}

/**
 * 为一个片段生成计费单元
 */
private List<BillingUnit> generateUnitsForFragment(TimeFragment fragment, LocalDateTime cycleStart, RelativeTimeConfig config) {
    List<BillingUnit> units = new ArrayList<>();

    // 计算片段开始时间在周期内的偏移量
    long offsetMinutes = Duration.between(cycleStart, fragment.beginTime).toMinutes();
    LocalDateTime current = fragment.beginTime;

    while (current.isBefore(fragment.endTime)) {
        // 找到当前时间点对应的 period
        int minutesFromCycleStart = (int) Duration.between(cycleStart, current).toMinutes();
        RelativeTimePeriod period = findPeriodForMinute(minutesFromCycleStart, config.getPeriods());

        int unitMinutes = period.getUnitMinutes();
        BigDecimal unitPrice = period.getUnitPrice();

        LocalDateTime unitEnd = current.plusMinutes(unitMinutes);

        // 截断到片段边界和 period 边界
        if (unitEnd.isAfter(fragment.endTime)) {
            unitEnd = fragment.endTime;
        }

        // 截断到 period 边界
        int periodEndMinute = period.getEndMinute();
        LocalDateTime periodEnd = cycleStart.plusMinutes(periodEndMinute);
        if (unitEnd.isAfter(periodEnd)) {
            unitEnd = periodEnd;
        }

        int duration = (int) Duration.between(current, unitEnd).toMinutes();

        // 不足单元也收全额
        BigDecimal originalAmount = unitPrice;

        BillingUnit unit = BillingUnit.builder()
                .beginTime(current)
                .endTime(unitEnd)
                .durationMinutes(duration)
                .unitPrice(unitPrice)
                .originalAmount(originalAmount)
                .free(false)
                .chargedAmount(originalAmount)
                .build();

        units.add(unit);
        current = unitEnd;
    }

    return units;
}

/**
 * 找到对应的 period
 */
private RelativeTimePeriod findPeriodForMinute(int minute, List<RelativeTimePeriod> periods) {
    for (RelativeTimePeriod period : periods) {
        if (minute >= period.getBeginMinute() && minute < period.getEndMinute()) {
            return period;
        }
    }
    // 如果超出最后一个 period，返回最后一个
    return periods.get(periods.size() - 1);
}
```

- [ ] **Step 6: 实现 applyContinuousCap() 方法**

```java
private void applyContinuousCap(List<BillingUnit> units, BigDecimal maxCharge) {
    if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
        return;
    }

    BigDecimal accumulated = BigDecimal.ZERO;
    int capIndex = -1;
    BigDecimal lastChargeAmount = null;

    for (int i = 0; i < units.size(); i++) {
        BillingUnit unit = units.get(i);
        if (unit.isFree()) {
            continue;
        }

        BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

        if (newAccumulated.compareTo(maxCharge) >= 0) {
            capIndex = i;
            lastChargeAmount = maxCharge.subtract(accumulated);
            break;
        }

        accumulated = newAccumulated;
    }

    if (capIndex < 0) {
        return;
    }

    units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));

    if (capIndex < units.size() - 1) {
        BillingUnit firstAfterCap = units.get(capIndex + 1);
        BillingUnit lastAfterCap = units.get(units.size() - 1);

        BillingUnit mergedFreeUnit = BillingUnit.builder()
                .beginTime(firstAfterCap.getBeginTime())
                .endTime(lastAfterCap.getEndTime())
                .durationMinutes((int) Duration.between(firstAfterCap.getBeginTime(), lastAfterCap.getEndTime()).toMinutes())
                .unitPrice(BigDecimal.ZERO)
                .originalAmount(BigDecimal.ZERO)
                .free(true)
                .freePromotionId("CYCLE_CAP")
                .chargedAmount(BigDecimal.ZERO)
                .build();

        units.subList(capIndex + 1, units.size()).clear();
        units.add(mergedFreeUnit);
    }
}
```

- [ ] **Step 7: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 8: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "feat: RelativeTimeRule 实现 CONTINUOUS 计费模式 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 3: 测试验证

### Task 5: 编译和运行测试

- [ ] **Step 1: 完整编译**

Run: `mvn clean compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行现有测试**

Run: `mvn clean install -DskipTests -q && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.RelativeTimeTest" -q`

Expected: 测试正常运行

- [ ] **Step 3: 最终提交**

```bash
git status
# 如有未提交的变更则提交
```

---

## 后续提醒

实现完成后需要：
1. 添加 CONTINUOUS 模式的测试用例
2. 验证封顶截止逻辑的正确性
3. 考虑将公共方法（如 splitTimeAxis、applyContinuousCap）抽离到工具类