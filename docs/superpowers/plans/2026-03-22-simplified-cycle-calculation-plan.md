# 长期计费简化计算实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对连续无优惠的周期进行简化计算，减少计算量和计费单元数量

**Architecture:** 在 AbstractTimeBasedRule 中提供简化框架方法，各规则在周期生成阶段判断并执行简化。简化单元通过 ruleData 字段携带简化信息，支持 CONTINUE 模式状态恢复。

**Tech Stack:** Java 17, Lombok, Maven

**Design Doc:** `docs/superpowers/specs/2026-03-22-simplified-cycle-calculation-design.md`

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `BillingConfigResolver.java` | 新增 `getSimplifiedCycleThreshold()` 方法 |
| `DayNightConfig.java` | 新增 `simplifiedSupported` 字段 |
| `RelativeTimeConfig.java` | 新增 `simplifiedSupported` 字段 |
| `CompositeTimeConfig.java` | 新增 `simplifiedSupported` 字段 |
| `AbstractTimeBasedRule.java` | 新增简化框架方法 |
| `DayNightRule.java` | 实现 UNIT_BASED 和 CONTINUOUS 模式简化计算 |
| `RelativeTimeRule.java` | 实现 UNIT_BASED 和 CONTINUOUS 模式简化计算 |
| `CompositeTimeRule.java` | 实现 UNIT_BASED 和 CONTINUOUS 模式简化计算 |
| `SimplifiedCalculationTest.java` | 测试验证 |

---

## Task 1: BillingConfigResolver 新增方法

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/BillingConfigResolver.java`

- [ ] **Step 1: 新增 getSimplifiedCycleThreshold 方法**

```java
/**
 * 获取简化计算的周期阈值
 * @return 连续无优惠周期数超过此值时启用简化，0 表示禁用简化
 */
default int getSimplifiedCycleThreshold() {
    return 0; // 默认禁用
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/BillingConfigResolver.java
git commit -m "feat: BillingConfigResolver 新增 getSimplifiedCycleThreshold 方法"
```

---

## Task 2: 配置类新增 simplifiedSupported 字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeConfig.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeConfig.java`

- [ ] **Step 1: DayNightConfig 新增字段**

在 `maxChargeOneDay` 字段后添加：

```java
/**
 * 是否支持简化计算，null 表示默认支持
 */
private Boolean simplifiedSupported;
```

- [ ] **Step 2: RelativeTimeConfig 新增字段**

在 `maxChargeOneCycle` 字段后添加：

```java
/**
 * 是否支持简化计算，null 表示默认支持
 */
private Boolean simplifiedSupported;
```

- [ ] **Step 3: CompositeTimeConfig 新增字段**

在封顶金额字段后添加：

```java
/**
 * 是否支持简化计算，null 表示默认支持
 */
private Boolean simplifiedSupported;
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java \
        core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeConfig.java \
        core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeConfig.java
git commit -m "feat: 配置类新增 simplifiedSupported 字段"
```

---

## Task 3: AbstractTimeBasedRule 新增简化框架方法

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`

- [ ] **Step 1: 新增抽象方法声明**

在 `hasComplexFeatures` 方法后添加：

```java
// ==================== 简化计算框架 ====================

/**
 * 子类实现：是否支持简化计算
 */
protected abstract boolean isSimplifiedSupported(C config);

/**
 * 子类实现：获取周期封顶金额
 * 用于简化计算时确定单周期金额
 */
protected abstract BigDecimal getCycleCapAmount(C config);
```

- [ ] **Step 2: 新增辅助方法：检查简化是否启用**

```java
/**
 * 检查简化计算是否启用
 */
protected boolean isSimplificationEnabled(C config, BillingConfigResolver configResolver) {
    // 配置明确禁用
    if (config.getSimplifiedSupported() != null && !config.getSimplifiedSupported()) {
        return false;
    }
    // 阈值为 0 表示禁用
    int threshold = configResolver.getSimplifiedCycleThreshold();
    if (threshold <= 0) {
        return false;
    }
    // 封顶金额必须有效
    BigDecimal capAmount = getCycleCapAmount(config);
    return capAmount != null && capAmount.compareTo(BigDecimal.ZERO) > 0;
}
```

- [ ] **Step 3: 新增方法：计算周期边界时间**

```java
/**
 * 计算周期边界时间
 * @param cycleIndex 周期索引（0-based）
 * @param calcBegin 计算起点
 * @return 该周期的起始时间
 */
protected LocalDateTime getCycleBoundary(int cycleIndex, LocalDateTime calcBegin) {
    return calcBegin.plusMinutes((long) cycleIndex * getCycleMinutes());
}
```

- [ ] **Step 4: 新增方法：计算优惠覆盖的周期索引集合**

```java
/**
 * 计算优惠时段覆盖的周期索引集合
 */
protected Set<Integer> findCyclesWithPromotion(
        LocalDateTime calcBegin,
        LocalDateTime calcEnd,
        PromotionAggregate promotionAggregate) {

    Set<Integer> cycles = new HashSet<>();

    // 如果有免费分钟数，保守地将所有周期视为有优惠
    if (promotionAggregate != null && promotionAggregate.getFreeMinutes() > 0) {
        // 返回 -1 表示所有周期都有优惠（无法用集合表示无限）
        // 调用方需特殊处理
        return null;
    }

    if (promotionAggregate == null || promotionAggregate.getFreeTimeRanges() == null) {
        return cycles;
    }

    List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
    int cycleMinutes = getCycleMinutes();

    for (FreeTimeRange range : freeTimeRanges) {
        // 忽略窗口外的时段
        if (range.getEndTime().isBefore(calcBegin) || range.getBeginTime().isAfter(calcEnd)) {
            continue;
        }

        // 计算优惠时段覆盖的周期范围
        LocalDateTime effectiveBegin = range.getBeginTime().isBefore(calcBegin) ? calcBegin : range.getBeginTime();
        LocalDateTime effectiveEnd = range.getEndTime().isAfter(calcEnd) ? calcEnd : range.getEndTime();

        int startCycle = (int) Duration.between(calcBegin, effectiveBegin).toMinutes() / cycleMinutes;
        int endCycle = (int) Duration.between(calcBegin, effectiveEnd).toMinutes() / cycleMinutes;

        // 如果结束时间正好在周期边界，不包含下一个周期
        long endMinutes = Duration.between(calcBegin, effectiveEnd).toMinutes();
        if (endMinutes % cycleMinutes == 0) {
            endCycle--;
        }

        // 添加所有覆盖的周期索引
        for (int i = startCycle; i <= endCycle; i++) {
            if (i >= 0) {
                cycles.add(i);
            }
        }
    }

    return cycles;
}
```

- [ ] **Step 5: 新增方法：构建简化单元**

```java
/**
 * 构建简化单元
 */
protected BillingUnit buildSimplifiedUnit(
        int beginCycleIndex,
        int cycleCount,
        BigDecimal cycleCapAmount,
        LocalDateTime calcBegin) {

    LocalDateTime beginTime = getCycleBoundary(beginCycleIndex, calcBegin);
    LocalDateTime endTime = getCycleBoundary(beginCycleIndex + cycleCount, calcBegin);
    BigDecimal totalAmount = cycleCapAmount.multiply(BigDecimal.valueOf(cycleCount));

    // 构建 ruleData
    Map<String, Object> ruleData = new HashMap<>();
    ruleData.put("cycleIndex", beginCycleIndex);
    ruleData.put("simplifiedCycleCount", cycleCount);
    ruleData.put("simplifiedCycleAmount", cycleCapAmount);
    ruleData.put("isSimplified", true);

    return BillingUnit.builder()
            .beginTime(beginTime)
            .endTime(endTime)
            .durationMinutes((int) Duration.between(beginTime, endTime).toMinutes())
            .unitPrice(cycleCapAmount)
            .originalAmount(totalAmount)
            .chargedAmount(totalAmount)
            .ruleData(ruleData)
            .build();
}
```

- [ ] **Step 6: 新增方法：检查单元是否为简化单元**

```java
/**
 * 检查 BillingUnit 是否为简化单元
 */
@SuppressWarnings("unchecked")
protected boolean isSimplifiedUnit(BillingUnit unit) {
    if (unit.getRuleData() instanceof Map) {
        Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
        return Boolean.TRUE.equals(data.get("isSimplified"));
    }
    return false;
}
```

- [ ] **Step 7: 新增方法：从简化单元恢复状态**

```java
/**
 * 从简化单元恢复 RuleState
 */
@SuppressWarnings("unchecked")
protected RuleState restoreStateFromSimplifiedUnit(RuleState state, BillingUnit simplifiedUnit, LocalDateTime calcBegin) {
    if (state == null || simplifiedUnit == null || !isSimplifiedUnit(simplifiedUnit)) {
        return state;
    }

    Map<String, Object> data = (Map<String, Object>) simplifiedUnit.getRuleData();
    int simplifiedCount = (Integer) data.get("simplifiedCycleCount");
    BigDecimal cycleAmount = (BigDecimal) data.get("simplifiedCycleAmount");

    state.setCycleIndex(state.getCycleIndex() + simplifiedCount);
    state.setCycleAccumulated(cycleAmount);
    state.setCycleBoundary(getCycleBoundary(state.getCycleIndex() + 1, calcBegin));

    return state;
}
```

- [ ] **Step 8: 修改 restoreState 方法，支持简化单元恢复**

修改现有 `restoreState` 方法，在 CONTINUE 模式下检查上一个结果是否有简化单元：

```java
/**
 * 从 Map 恢复 RuleState（支持简化单元）
 * @param stateMap 状态 Map
 * @param previousResult 上一次计算结果（用于检测简化单元）
 * @param calcBegin 当前计算起点
 */
@SuppressWarnings("unchecked")
protected RuleState restoreStateWithSimplification(
        Map<String, Object> stateMap,
        BillingSegmentResult previousResult,
        LocalDateTime calcBegin) {

    RuleState state = restoreState(stateMap);
    if (state == null) {
        return null;
    }

    // 检查上一个结果的最后一个单元是否为简化单元
    if (previousResult != null && previousResult.getBillingUnits() != null
            && !previousResult.getBillingUnits().isEmpty()) {
        BillingUnit lastUnit = previousResult.getBillingUnits().get(
            previousResult.getBillingUnits().size() - 1);
        if (isSimplifiedUnit(lastUnit)) {
            restoreStateFromSimplifiedUnit(state, lastUnit, calcBegin);
        }
    }

    return state;
}
```

- [ ] **Step 9: 新增 import 声明**

在文件顶部添加：

```java
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
```

- [ ] **Step 10: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 11: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java
git commit -m "feat: AbstractTimeBasedRule 新增简化计算框架方法"
```

---

## Task 4: DayNightRule 实现简化计算

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 实现 isSimplifiedSupported 方法**

```java
@Override
protected boolean isSimplifiedSupported(DayNightConfig config) {
    return config.getSimplifiedSupported() == null || config.getSimplifiedSupported();
}
```

- [ ] **Step 2: 实现 getCycleCapAmount 方法**

```java
@Override
protected BigDecimal getCycleCapAmount(DayNightConfig config) {
    return config.getMaxChargeOneDay();
}
```

- [ ] **Step 3: 修改 calculateUnitBased 方法（简化逻辑）**

在 `validateConfig` 之后、状态恢复之前，添加简化判断逻辑：

```java
// 检查是否启用简化
int threshold = context.getBillingConfigResolver().getSimplifiedCycleThreshold();
boolean simplificationEnabled = isSimplificationEnabled(config, context.getBillingConfigResolver());

// 计算总周期数
long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
int totalCycles = (int) (totalMinutes / getCycleMinutes());

if (simplificationEnabled && totalCycles > threshold) {
    // 预计算有优惠的周期
    Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);

    // 如果所有周期都有优惠（freeMinutes > 0），不简化
    if (cyclesWithPromotion == null) {
        simplificationEnabled = false;
    } else {
        // 执行简化计算
        return calculateWithSimplification(context, config, promotionAggregate,
            calcBegin, calcEnd, state, threshold, cyclesWithPromotion, totalCycles);
    }
}
```

- [ ] **Step 4: 新增 calculateWithSimplification 方法**

```java
/**
 * 带简化计算的方法
 */
private BillingSegmentResult calculateWithSimplification(
        BillingContext context,
        DayNightConfig config,
        PromotionAggregate promotionAggregate,
        LocalDateTime calcBegin,
        LocalDateTime calcEnd,
        RuleState state,
        int threshold,
        Set<Integer> cyclesWithPromotion,
        int totalCycles) {

    List<BillingUnit> billingUnits = new ArrayList<>();
    BigDecimal cycleCapAmount = getCycleCapAmount(config);

    // 从 state 恢复周期索引
    int startCycleIndex = state.getCycleIndex();

    int consecutiveSimplified = 0;
    int simplifiedStartIndex = -1;

    for (int cycleIndex = startCycleIndex; cycleIndex <= totalCycles; cycleIndex++) {
        boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

        if (!hasPromotion) {
            // 无优惠周期，累计
            if (consecutiveSimplified == 0) {
                simplifiedStartIndex = cycleIndex;
            }
            consecutiveSimplified++;
        } else {
            // 遇到有优惠周期，先处理之前的简化段
            if (consecutiveSimplified > threshold) {
                // 生成简化单元
                BillingUnit simplifiedUnit = buildSimplifiedUnit(
                    simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                billingUnits.add(simplifiedUnit);
            } else if (consecutiveSimplified > 0) {
                // 不足阈值，逐周期生成
                for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                    List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, calcEnd, config);
                    billingUnits.addAll(cycleUnits);
                }
            }
            consecutiveSimplified = 0;

            // 生成当前有优惠周期的详细单元
            List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(cycleIndex, calcBegin, calcEnd, config);
            billingUnits.addAll(cycleUnits);
        }
    }

    // 处理最后的简化段
    if (consecutiveSimplified > threshold) {
        BillingUnit simplifiedUnit = buildSimplifiedUnit(
            simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
        billingUnits.add(simplifiedUnit);
    } else if (consecutiveSimplified > 0) {
        for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
            List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, calcEnd, config);
            billingUnits.addAll(cycleUnits);
        }
    }

    // 应用封顶
    applyDailyCapWithCarryOver(billingUnits, config, state.getCycleAccumulated());

    // 更新状态
    state.setCycleIndex(totalCycles);
    state.setCycleAccumulated(cycleCapAmount);
    state.setCycleBoundary(getCycleBoundary(totalCycles + 1, calcBegin));

    // 汇总结果
    BigDecimal totalAmount = billingUnits.stream()
            .map(BillingUnit::getChargedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    // 标记最后单元截断
    if (!billingUnits.isEmpty()) {
        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        if (!isSimplifiedUnit(lastUnit)) {
            int unitMinutes = config.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }
    }

    return BillingSegmentResult.builder()
            .segmentId(context.getSegment().getId())
            .segmentStartTime(context.getSegment().getBeginTime())
            .segmentEndTime(context.getSegment().getEndTime())
            .calculationStartTime(calcBegin)
            .calculationEndTime(calcEnd)
            .chargedAmount(totalAmount)
            .billingUnits(billingUnits)
            .promotionUsages(new ArrayList<>())
            .promotionAggregate(promotionAggregate)
            .feeEffectiveStart(calculateEffectiveFrom(billingUnits))
            .feeEffectiveEnd(calculateEffectiveTo(billingUnits, promotionAggregate.getFreeTimeRanges(), calcBegin, calcEnd))
            .ruleOutputState(buildRuleOutputState(state))
            .build();
}
```

- [ ] **Step 5: 新增 generateUnitsForSingleCycle 方法**

```java
/**
 * 生成单个周期的计费单元
 */
private List<BillingUnit> generateUnitsForSingleCycle(
        int cycleIndex,
        LocalDateTime calcBegin,
        LocalDateTime calcEnd,
        DayNightConfig config) {

    LocalDateTime cycleStart = getCycleBoundary(cycleIndex, calcBegin);
    LocalDateTime cycleEnd = getCycleBoundary(cycleIndex + 1, calcBegin);

    // 限制在计算窗口内
    if (cycleStart.isBefore(calcBegin)) {
        cycleStart = calcBegin;
    }
    if (cycleEnd.isAfter(calcEnd)) {
        cycleEnd = calcEnd;
    }

    if (!cycleStart.isBefore(cycleEnd)) {
        return List.of();
    }

    // 使用现有的 buildUnitsWithContextWithState 逻辑
    RuleState tempState = RuleState.builder()
            .cycleIndex(cycleIndex)
            .cycleAccumulated(BigDecimal.ZERO)
            .cycleBoundary(cycleEnd)
            .build();

    List<UnitWithContext> unitsWithContext = buildUnitsWithContextWithState(cycleStart, cycleEnd, config, tempState);

    List<BillingUnit> units = new ArrayList<>();
    for (UnitWithContext unitCtx : unitsWithContext) {
        BillingUnit unit = calculateUnit(unitCtx, config, List.of());
        units.add(unit);
    }

    return units;
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功，可能需要修复细节

- [ ] **Step 7: 实现 CONTINUOUS 模式简化计算**

在 `calculateContinuous` 方法中，在 `organizeByCycle()` 之后添加简化逻辑：

```java
// 在按周期组织片段后，检查是否可以简化
int threshold = context.getBillingConfigResolver().getSimplifiedCycleThreshold();
boolean simplificationEnabled = isSimplificationEnabled(config, context.getBillingConfigResolver());

if (simplificationEnabled) {
    Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);

    if (cyclesWithPromotion != null) {
        // 在生成计费单元前进行简化判断
        allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
            threshold, config, calcBegin);
    }
}

// 如果未简化，使用原有逻辑
if (allUnits.isEmpty()) {
    // 原有的逐周期生成逻辑...
}
```

新增 `generateSimplifiedUnitsForContinuous` 方法：

```java
private List<BillingUnit> generateSimplifiedUnitsForContinuous(
        List<CycleFragments> cycles,
        Set<Integer> cyclesWithPromotion,
        int threshold,
        DayNightConfig config,
        LocalDateTime calcBegin) {

    List<BillingUnit> allUnits = new ArrayList<>();
    BigDecimal cycleCapAmount = getCycleCapAmount(config);

    int consecutiveSimplified = 0;
    int simplifiedStartIndex = -1;

    for (CycleFragments cycle : cycles) {
        int cycleIndex = calculateCycleIndex(cycle.cycleStart, calcBegin);
        boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

        if (!hasPromotion) {
            if (consecutiveSimplified == 0) {
                simplifiedStartIndex = cycleIndex;
            }
            consecutiveSimplified++;
        } else {
            // 处理之前的简化段
            if (consecutiveSimplified > threshold) {
                BillingUnit simplifiedUnit = buildSimplifiedUnit(
                    simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                allUnits.add(simplifiedUnit);
            } else if (consecutiveSimplified > 0) {
                // 不足阈值，正常生成
                for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                    List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config);
                    allUnits.addAll(cycleUnits);
                }
            }
            consecutiveSimplified = 0;

            // 生成当前有优惠周期的详细单元
            List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
            allUnits.addAll(cycleUnits);
        }
    }

    // 处理最后的简化段
    if (consecutiveSimplified > threshold) {
        BillingUnit simplifiedUnit = buildSimplifiedUnit(
            simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
        allUnits.add(simplifiedUnit);
    } else if (consecutiveSimplified > 0) {
        for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
            List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, calcEnd, config);
            allUnits.addAll(cycleUnits);
        }
    }

    return allUnits;
}
```

- [ ] **Step 8: 编译验证 CONTINUOUS 模式**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 9: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "feat: DayNightRule 实现简化计算逻辑（UNIT_BASED + CONTINUOUS）"
```

---

## Task 5: RelativeTimeRule 实现简化计算

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 实现 isSimplifiedSupported 方法**

```java
@Override
protected boolean isSimplifiedSupported(RelativeTimeConfig config) {
    return config.getSimplifiedSupported() == null || config.getSimplifiedSupported();
}
```

- [ ] **Step 2: 实现 getCycleCapAmount 方法**

```java
@Override
protected BigDecimal getCycleCapAmount(RelativeTimeConfig config) {
    return config.getMaxChargeOneCycle();
}
```

- [ ] **Step 3: 实现 UNIT_BASED 模式简化逻辑**

在 `calculateUnitBased` 方法中添加与 DayNightRule 类似的简化逻辑：
1. 检查简化是否启用
2. 预计算有优惠的周期索引集合
3. 遍历周期时判断是否可以简化
4. 生成简化单元或详细单元

关键差异：
- 使用 `buildBillingUnitsWithState` 方法生成单个周期的详细单元
- 封顶金额使用 `config.getMaxChargeOneCycle()`

- [ ] **Step 4: 实现 CONTINUOUS 模式简化逻辑**

在 `calculateContinuous` 方法中：
1. 在 `organizeByCycle()` 之后添加简化判断
2. 实现 `generateSimplifiedUnitsForContinuous` 方法（参考 DayNightRule）

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 6: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "feat: RelativeTimeRule 实现简化计算逻辑（UNIT_BASED + CONTINUOUS）"
```

---

## Task 6: CompositeTimeRule 实现简化计算

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 实现 isSimplifiedSupported 方法**

```java
@Override
protected boolean isSimplifiedSupported(CompositeTimeConfig config) {
    return config.getSimplifiedSupported() == null || config.getSimplifiedSupported();
}
```

- [ ] **Step 2: 实现 getCycleCapAmount 方法**

```java
@Override
protected BigDecimal getCycleCapAmount(CompositeTimeConfig config) {
    return config.getMaxChargeOneCycle();
}
```

- [ ] **Step 3: 实现 UNIT_BASED 模式简化逻辑**

在 `calculateUnitBased` 方法中添加与 DayNightRule 类似的简化逻辑：
1. 检查简化是否启用
2. 预计算有优惠的周期索引集合
3. 遍历周期时判断是否可以简化
4. 生成简化单元或详细单元

关键差异：
- 使用 `buildBillingUnits` 方法生成单个周期的详细单元
- 封顶金额使用 `config.getMaxChargeOneCycle()`
- 注意：CompositeTimeRule 有双层结构，简化时只考虑周期封顶

- [ ] **Step 4: 实现 CONTINUOUS 模式简化逻辑**

在 `calculateContinuous` 方法中：
1. 在 `organizeByCycle()` 之后添加简化判断
2. 实现 `generateSimplifiedUnitsForContinuous` 方法（参考 DayNightRule）

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

- [ ] **Step 6: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "feat: CompositeTimeRule 实现简化计算逻辑（UNIT_BASED + CONTINUOUS）"
```

---

## Task 7: 测试验证

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/SimplifiedCalculationTest.java`

- [ ] **Step 1: 创建测试类框架**

```java
package cn.shang.charging;

/**
 * 长期计费简化计算测试
 */
public class SimplifiedCalculationTest {

    public static void main(String[] args) {
        testBasicSimplification();
        testPartialSimplification();
        testThresholdBoundary();
        testDisabledSimplification();
        testContinueMode();
        testFreeMinutes();

        System.out.println("\n✅ 所有测试通过");
    }

    // 测试方法...
}
```

- [ ] **Step 2: 实现测试方法 testBasicSimplification**

30 天无优惠，验证生成简化单元

- [ ] **Step 3: 实现测试方法 testPartialSimplification**

30 天，中间某天有优惠，验证分段简化

- [ ] **Step 4: 实现测试方法 testThresholdBoundary**

8 天（阈值 7），验证刚好超过阈值

- [ ] **Step 5: 实现测试方法 testDisabledSimplification**

配置禁用或阈值 0，验证正常计算

- [ ] **Step 6: 实现测试方法 testContinueMode**

简化后继续计算，验证状态恢复

- [ ] **Step 7: 实现测试方法 testFreeMinutes**

存在免费分钟时，验证不启用简化

- [ ] **Step 8: 运行测试**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.SimplifiedCalculationTest" -q`
Expected: 所有测试通过

- [ ] **Step 9: 提交**

```bash
git add bill-test/src/main/java/cn/shang/charging/SimplifiedCalculationTest.java
git commit -m "test: 新增长期计费简化计算测试"
```

---

## Task 8: 清理和文档更新

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 移除 FreeTimeRangePromotionRule 引用**

在 `CLAUDE.md` 中搜索并移除对 `FreeTimeRangePromotionRule` 的引用（该类实际不存在）

- [ ] **Step 2: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 移除 FreeTimeRangePromotionRule 引用"
```

---

## 验收标准

- [ ] 所有测试通过
- [ ] 编译无警告
- [ ] 代码符合项目规范
- [ ] 简化单元正确生成
- [ ] CONTINUE 模式状态正确恢复