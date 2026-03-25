# 取消延伸功能重构设计

## 日期
2026-03-21

---

## 一、背景

### 问题陈述

当前系统的"计费单元延伸"功能让计算变得复杂和难以理解：
- 延伸逻辑分散在各规则中（RelativeTimeRule, DayNightRule, CompositeTimeRule），每个规则约 50-100 行
- 延伸需要考虑免费时段边界、周期边界、时间段边界等复杂因素
- 延伸与优惠交互需要额外的 boundaryReferences 机制
- 计算逻辑"越界"到窗口外，违反单一职责

### 核心思想

**计算逻辑严格限制在计算窗口内**，避免过高的复杂度和难以理解的计算逻辑。

---

## 二、目标

1. **删除延伸逻辑**：简化规则实现
2. **删除 boundaryReferences**：移除窗口外优惠处理
3. **新增截断标记**：支持 CONTINUE 模式恢复
4. **区分计算/查询时间点**：由调用方控制费用稳定窗口

---

## 三、关键设计

### 3.1 数据结构变更

#### BillingRequest 新增字段

```java
/**
 * 查询时间点（可选）
 * 用于返回该时刻的费用状态
 * 不提供时，默认使用 calcEndTime
 */
LocalDateTime queryTime;
```

#### BillingUnit 新增字段

```java
/**
 * 是否被 calcEndTime 截断
 * 用于 CONTINUE 模式恢复截断单元
 */
Boolean isTruncated;
```

**备选方案**：未来可考虑 `unitStatus: enum`（FULL, TRUNCATED, BOUNDARY_CUT）

#### PromotionAggregate 删除字段

```java
// 删除
List<FreeTimeRange> boundaryReferences;
```

#### TimeRangeMergeResult 删除字段

```java
// 删除
List<FreeTimeRange> boundaryReferences;
```

### 3.2 计算流程变更

#### FROM_SCRATCH 模式

```
输入：beginTime, calcEndTime, queryTime
计算窗口：[beginTime, calcEndTime]

1. 生成计费单元
2. 最后一个单元：
   - 若被 calcEndTime 截断，标记 isTruncated=true
   - 不再延伸
3. calculationEndTime = calcEndTime
4. 返回结果：
   - 所有计算单元
   - 金额、effectiveFrom、effectiveTo 根据 queryTime 计算
```

#### CONTINUE 模式

```
输入：previousCarryOver, newCalcEndTime, queryTime
计算窗口：[previousCarryOver.calculatedUpTo, newCalcEndTime]

1. 检测上一个结果最后一个单元是否 isTruncated=true
2. 如果截断：
   - 恢复该单元到完整长度（受周期边界、时间段边界限制）
   - 恢复在当前窗口内进行
3. 继续生成新单元
4. calculationEndTime = newCalcEndTime
```

**关键点**：
- 恢复截断单元在**当前窗口内**进行
- 不需要考虑窗口外的优惠
- 恢复时需要检查周期边界、时间段边界，不能跨边界恢复

### 3.3 规则层变更

#### 删除的方法

| 规则 | 删除方法 |
|------|---------|
| DayNightRule | `extendLastUnit()`, `findNextFreeRangeBoundary()` |
| RelativeTimeRule | `extendLastUnit()`, `findNextFreeRangeBoundary()` |
| CompositeTimeRule | `extendLastUnit()`, `findNextFreeRangeBoundary()` |

#### 修改的方法

**calculateUnitBased() / calculateContinuous()**

```java
// 生成计费单元
List<BillingUnit> units = generateUnits(...);

// 处理最后一个单元（不再延伸）
BillingUnit lastUnit = units.get(units.size() - 1);
int fullUnitMinutes = config.getUnitMinutes();
int actualMinutes = lastUnit.getDurationMinutes();

if (actualMinutes < fullUnitMinutes && lastUnit.getEndTime().equals(calcEndTime)) {
    // 被计算窗口截断，标记
    lastUnit.setIsTruncated(true);
}

// calculationEndTime 不再延伸
LocalDateTime calculationEndTime = calcEndTime;
```

**CONTINUE 模式恢复逻辑**

```java
// 在规则计算开头
if (context.getContinueMode() == BConstants.ContinueMode.CONTINUE) {
    BillingUnit lastUnit = getLastUnitFromPreviousResult(context);
    if (lastUnit != null && Boolean.TRUE.equals(lastUnit.getIsTruncated())) {
        // 恢复截断单元
        // 调整计算起点为截断单元的开始时间
        // 让第一个单元从该点开始，恢复到完整长度
        // 注意：不能跨周期边界、时间段边界恢复
    }
}
```

### 3.4 PromotionEngine 变更

#### 删除的逻辑

```java
// 删除 boundaryReferences 获取和传递
List<FreeTimeRange> boundaryReferences = ...;
```

#### 简化的逻辑

只处理当前计算窗口内的免费时段，不再考虑窗口外的优惠。

### 3.5 ResultAssembler 变更

#### 新增 queryTime 处理

```java
public BillingResult assemble(BillingRequest request, List<BillingSegmentResult> segmentResults) {
    LocalDateTime queryTime = request.getQueryTime() != null
        ? request.getQueryTime()
        : request.getCalcEndTime();  // 默认使用 calcEndTime

    // 过滤单元：只统计 queryTime 之前完成的单元
    // 重新计算金额、effectiveFrom、effectiveTo

    // ...
}
```

---

## 四、删除清单

| 文件/字段 | 操作 |
|----------|------|
| `PromotionAggregate.boundaryReferences` | 删除字段 |
| `TimeRangeMergeResult.boundaryReferences` | 删除字段 |
| `DayNightRule.extendLastUnit()` | 删除方法 |
| `DayNightRule.findNextFreeRangeBoundary()` | 删除方法 |
| `RelativeTimeRule.extendLastUnit()` | 删除方法 |
| `RelativeTimeRule.findNextFreeRangeBoundary()` | 删除方法 |
| `CompositeTimeRule.extendLastUnit()` | 删除方法 |
| `CompositeTimeRule.findNextFreeRangeBoundary()` | 删除方法 |
| `FreeTimeRangeMerger.preprocessRanges()` 中边界参考处理 | 删除逻辑 |

---

## 五、预期收益

| 指标 | 当前 | 优化后 |
|------|------|--------|
| DayNightRule 行数 | ~1200 | ~1100 |
| RelativeTimeRule 行数 | ~1200 | ~1100 |
| CompositeTimeRule 行数 | ~1400 | ~1300 |
| 边界判断复杂度 | 高（窗口内外） | 低（窗口内） |
| boundaryReferences 机制 | 需要 | 删除 |

---

## 六、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|---------|
| CONTINUE 模式行为变化 | 中 | 测试覆盖各种截断恢复场景 |
| queryTime 逻辑新增 | 低 | 可选字段，向后兼容 |
| 规则修改影响现有功能 | 中 | 渐进式修改，保证测试通过 |

---

## 七、实施计划

### Phase 1: 数据结构变更
- [ ] BillingRequest 新增 queryTime 字段
- [ ] BillingUnit 新增 isTruncated 字段
- [ ] 删除 PromotionAggregate.boundaryReferences
- [ ] 删除 TimeRangeMergeResult.boundaryReferences

### Phase 2: 规则层修改
- [ ] DayNightRule 删除延伸逻辑，新增截断标记
- [ ] RelativeTimeRule 删除延伸逻辑，新增截断标记
- [ ] CompositeTimeRule 删除延伸逻辑，新增截断标记

### Phase 3: CONTINUE 模式恢复逻辑
- [ ] 各规则新增截断单元恢复逻辑

### Phase 4: PromotionEngine 简化
- [ ] 删除 boundaryReferences 处理

### Phase 5: ResultAssembler 新增 queryTime 处理
- [ ] 根据 queryTime 过滤单元和计算金额

### Phase 6: 测试验证
- [ ] 更新现有测试
- [ ] 新增截断恢复测试
- [ ] 新增 queryTime 测试