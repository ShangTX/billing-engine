# 计费引擎现有逻辑详细梳理（中文）

## 一、整体架构

```
BillingService.calculate()
  → SegmentBuilder.buildSegments()        # 按计费方案变更切分时间窗口
  → BillingConfigResolver.resolveXxx()    # 获取计费规则和优惠规则配置
  → PromotionEngine.evaluate()            # 聚合免费时段和免费分钟数
  → BillingCalculator.calculate()         # 委托给 BillingRule 实现
  → ResultAssembler.assemble()            # 汇总结果
```

本文件聚焦 `BillingRule` 内部的免费覆盖逻辑。

---

## 二、两种计费模式

| 模式 | 含义 |
|------|------|
| UNIT_BASED | 固定单元边界，每个单元独立计算 |
| CONTINUOUS | 时间轴被免费时段切分，单元可变长 |

---

## 三、UNIT_BASED 模式（以 DayNightRule 为例）

### 流程

```
calculateUnitBased()
  → buildUnitsWithContextWithState()  # 按固定单元长度切分时间轴，生成 UnitWithContext 列表
  → for each UnitWithContext:
       calculateUnit(unitCtx, config, freeTimeRanges)  ← 关键方法
       → findFreePromotionId(begin, end, freeTimeRanges)  ← 免费覆盖检查
       → 如果找到覆盖：free=true, chargedAmount=0, freePromotionId=xxx
       → 否则：free=false, chargedAmount=originalAmount
  → applyDailyCapWithCarryOver()  # 应用周期封顶
```

### 关键方法：`findFreePromotionId(begin, end, freeTimeRanges)`

```java
// DayNightRule.java 第 642 行
private String findFreePromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
    for (FreeTimeRange range : freeTimeRanges) {
        // 完全覆盖检查：range.begin <= begin && range.end >= end
        if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
            return range.getId();
        }
    }
    return null;
}
```

**注意**：此方法检查所有免费时段，包括条件免费的（`conditional=true`）。

**覆盖行为**：
- 单元被免费时段**完全覆盖** → `free=true`, `freePromotionId=xxx`, `chargedAmount=0`
- 单元被免费时段**部分覆盖** → `free=false`, 正常收费（不标记）
- 单元不在任何免费时段内 → `free=false`, 正常收费

### CompositeTimeRule 的 UNIT_BASED 流程

类似，但检查内联在 `buildBillingUnits()` 的单元循环中（第 1261 行）：
```java
String freePromotionId = findFreePromotionId(unitStart, unitEnd, freeTimeRanges);
boolean isFree = freePromotionId != null;
```

---

## 四、CONTINUOUS 模式（以 DayNightRule 为例）

### 流程

```
calculateContinuous()
  → splitTimeAxis(calcBegin, calcEnd, freeTimeRanges)  ← 关键：切分时间轴
  → organizeByCycle(...)              # 按 24 小时周期组织片段
  → for each CycleFragments:
       generateUnitsForCycle(cycle, config)  ← 关键：生成单元
       → 免费 fragment：生成一个 free=true 的单元
       → 非免费 fragment：按 unitMinutes 切分成多个收费单元
  → applyContinuousCapWithCarryOver() # 应用周期封顶
```

### 关键方法：`splitTimeAxis(begin, end, freeTimeRanges)`

**作用**：以免费时段的边界为 cut point，将时间轴切成多个 TimeFragment。

```java
// DayNightRule.java 第 1142 行
private List<TimeFragment> splitTimeAxis(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
    // 1. 收集 cut points（免费时段的 beginTime 和 endTime）
    List<LocalDateTime> cutPoints = new ArrayList<>();
    cutPoints.add(begin);
    cutPoints.add(end);

    for (FreeTimeRange range : freeTimeRanges) {
        // 跳过条件免费时段（已修改）
        if (range.isConditional()) { continue; }
        cutPoints.add(range.getBeginTime());
        cutPoints.add(range.getEndTime());
    }

    // 2. 去重排序
    // 3. 生成 fragment
    for (i = 0; i < cutPoints.size() - 1; i++) {
        TimeFragment fragment = new TimeFragment(fragBegin, fragEnd);
        // 检查是否匹配某个免费时段
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(fragBegin) && !range.getEndTime().isBefore(fragEnd)) {
                fragment.isFree = true;
                fragment.freePromotionId = range.getId();
                break;
            }
        }
    }
    return fragments;
}
```

**重要行为**：
- 免费时段的边界是 cut point → 免费时段本身成为一个独立 fragment
- 两个免费时段之间的空隙也是一个 fragment
- 片段匹配免费时段：如果 fragment 被某个 range **完全覆盖**，标记为免费

### `generateUnitsForCycle(cycle, config)`

```java
// DayNightRule.java 第 1244 行
private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
    for (TimeFragment fragment : cycle.fragments) {
        if (fragment.isFree) {
            // 免费片段：整个生成一个免费单元
            BillingUnit unit = BillingUnit.builder()
                .free(true)
                .freePromotionId(fragment.freePromotionId)
                .chargedAmount(BigDecimal.ZERO)
                .build();
        } else {
            // 非免费片段：按 unitMinutes 切分
            while (current.isBefore(fragment.endTime)) {
                unitEnd = current.plusMinutes(unitMinutes);
                // 生成收费单元
                BillingUnit unit = BillingUnit.builder()
                    .free(false)
                    .chargedAmount(originalAmount)
                    .build();
                // ⚠️ 这里没有检查单元是否被条件免费时段覆盖
                current = unitEnd;
            }
        }
    }
    return units;
}
```

---

## 五、条件免费（conditional=true）的当前行为

### FreeTimeRange 的字段

```java
public class FreeTimeRange {
    LocalDateTime beginTime;
    LocalDateTime endTime;
    String id;
    boolean conditional;                    // 是否条件免费
    LocalDateTime conditionalUntil;         // 条件免费的窗口截止时间
    // ... 其他字段
}
```

### 当前流程

| 步骤 | UNIT_BASED | CONTINUOUS |
|------|-----------|------------|
| 切分时间轴 | 不涉及 | `splitTimeAxis` 跳过条件免费时段 |
| 免费覆盖检查 | `findFreePromotionId` 检查所有 range（含条件免费） | fragment 匹配免费时段时包含条件免费 |
| 单元标记 | 覆盖检查在 `calculateUnit()` 中进行 | 免费 fragment 标记 `free=true` |
| 条件免费标记 | ❌ 不设置 `conditionalFree` | ❌ 不设置 `conditionalFree` |

### 关键问题

当条件免费时段不参与切分时，整个时间窗口成为一个非免费 fragment（因为条件免费范围不能完全覆盖该 fragment）。

**例如**：
- 计费窗口：00:00-02:00
- 条件免费：00:00-01:00（conditional=true）
- 切分后只有一个 fragment：00:00-02:00（非免费）
- `generateUnitsForCycle` 切出两个单元：00:00-01:00 和 01:00-02:00
- **问题**：00:00-01:00 被条件免费完全覆盖，但没有被标记为 `free=true`

**对比 UNIT_BASED 模式**：
- `calculateUnit()` 中调用 `findFreePromotionId(begin, end, freeTimeRanges)`
- 00:00-01:00 会被 `findFreePromotionId` 找到条件免费 range
- 所以 UNIT_BASED 模式下能正确标记 `free=true`
- **但** `conditionalFree` 和 `conditionalFreeUntil` 字段仍未设置

---

## 六、RelativeTimeRule 和 CompositeTimeRule

### RelativeTimeRule CONTINUOUS 模式

- 与 DayNightRule 相同模式：`splitTimeAxis` → `organizeByCycle` → `generateUnitsForCycle` → `generateUnitsForFragment`
- 非免费 fragment 切分单元时同样不检查条件免费覆盖
- 有 `findFreePromotionId` 但只在非 CONTINUOUS 路径使用

### CompositeTimeRule CONTINUOUS 模式

- `buildBillingUnits()` 中有 `findFreePromotionId` 检查
- 每个单元生成时都检查免费覆盖
- 但同样不设置 `conditionalFree` / `conditionalFreeUntil`

---

## 七、已有的条件免费相关代码

### BillingUnit.java

```java
public class BillingUnit {
    boolean conditionalFree;           // 是否条件免费
    LocalDateTime conditionalFreeUntil; // 条件免费窗口截止时间
    // 以上两个字段已存在，但当前没有任何代码设置它们
}
```

### BillingResultViewer.java（查询层）

```java
private BillingResult applyQueryTimeValidation(BillingResult result, LocalDateTime queryTime) {
    // 检查是否有 conditionalFree=true 的单元
    boolean hasConditionalFree = originalUnits.stream().anyMatch(BillingUnit::isConditionalFree);
    // 如果 queryTime 超出 conditionalFreeUntil，恢复原价
    // ...
}

private BigDecimal resolveUnitCharge(BillingUnit unit, LocalDateTime queryTime) {
    // 处理 ruleData 中的 CONDITIONAL_PARTIAL 标记
    // ...
}
```

**问题**：查询层依赖 `conditionalFree` 字段，但计费引擎从未设置这个值。

---

## 八、问题总结

| 场景 | UNIT_BASED | CONTINUOUS (DayNight) | CONTINUOUS (CompositeTime) |
|------|-----------|----------------------|---------------------------|
| 单元被普通免费完全覆盖 | ✅ free=true | ✅ free=true | ✅ free=true |
| 单元被条件免费完全覆盖 | ✅ free=true, 但 conditionalFree 未设置 | ❌ 单元未标记为免费 | ✅ free=true, 但 conditionalFree 未设置 |
| 单元被条件免费部分覆盖 | ❌ 正常收费 | ❌ 正常收费, 无 ruleData 标记 | ❌ 正常收费, 无 ruleData 标记 |
| 查询层条件免费校验 | ❌ 依赖 conditionalFree 字段（未设置） | ❌ 依赖 conditionalFree 字段（未设置） | ❌ 依赖 conditionalFree 字段（未设置） |
