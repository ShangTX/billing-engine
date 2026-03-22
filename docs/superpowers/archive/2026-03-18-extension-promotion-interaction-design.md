# 延伸与优惠交互设计规格

> **Related Plan:** `docs/superpowers/plans/2026-03-18-extension-promotion-interaction.md`

---

## 问题背景

### 典型场景

```
免费时段：09:20-09:50
计算窗口：07:30-09:00
计费单元长度：60分钟
```

**当前流程**：
1. `PromotionEngine.evaluate()` 在窗口 [07:30, 09:00] 内分配优惠
   - 免费时段 09:20-09:50 完全在窗口外，被丢弃
2. `BillingRule` 生成计费单元：07:30-08:30, 08:30-09:00
3. `BillingRule` 延伸最后单元：08:30-09:00 → 08:30-09:30
4. 延伸后的单元 [09:20, 09:30] 与免费时段重叠，但优惠引擎不知道！

### 核心矛盾

优惠分配在延伸之前，延伸"闯入"了新的优惠区域，导致：
1. 延伸区域内的优惠未被处理
2. CONTINUE 模式下次计算时可能重复或遗漏优惠

---

## 设计决策

### 延伸的语义

| 决策项 | 决策 |
|--------|------|
| 延伸目的 | 预测下次 CONTINUE 的起点 |
| 遇到优惠时行为 | 停在优惠边界，下次 CONTINUE 从边界开始 |

### 期望行为

```
免费时段：09:20-09:50
计费窗口：07:30-09:00
最后单元：08:30-09:00

期望延伸到：09:20（停在免费时段边界）
calculationEndTime = 09:20
下次 CONTINUE 从 09:20 开始
```

---

## 解决方案：boundaryReferences 机制

### 核心思想

**区分两种用途的时段**：

| 类型 | 用途 | 参与结算 | 影响延伸 |
|------|------|----------|----------|
| 参与计算的时段 | 当前窗口内使用 | ✅ 计入 usedFreeRanges | ✅ 作为边界 |
| 边界参考时段 | 只用于延伸边界判断 | ❌ 不计入结算 | ✅ 作为边界 |

### 数据流

```
FreeTimeRangeMerger.preprocessRanges()
    ↓
时段在窗口前 → discardedRanges（丢弃）
时段在窗口内 → mergedRanges（参与计算）
时段在窗口后 → boundaryReferences（边界参考）
    ↓
PromotionEngine.evaluate()
    ↓
PromotionAggregate
    ├── freeTimeRanges = mergedRanges
    └── boundaryReferences = boundaryReferences
    ↓
BillingRule.extendLastUnit()
    ↓
findNextFreeRangeBoundary(freeTimeRanges + boundaryReferences)
    ↓
延伸停在最近的优惠边界
```

---

## 数据结构设计

### TimeRangeMergeResult

```java
public class TimeRangeMergeResult {
    private List<FreeTimeRange> mergedRanges;       // 参与计算的时段
    private List<FreeTimeRange> discardedRanges;    // 被丢弃/覆盖的时段
    private List<FreeTimeRange> boundaryReferences; // 新增：边界参考时段
}
```

### PromotionAggregate

```java
public class PromotionAggregate {
    private List<FreeTimeRange> freeTimeRanges;     // 当前窗口内的免费时段
    private List<FreeTimeRange> boundaryReferences; // 新增：边界参考时段
    private List<PromotionUsage> usages;
    private PromotionCarryOver promotionCarryOver;
}
```

---

## 关键算法

### FreeTimeRangeMerger.preprocessRanges()

**修改前**：
```java
// 时间段完全在整体区间外
if (originalRange.getEndTime().isBefore(overallStart) ||
        originalRange.getBeginTime().isAfter(overallEnd)) {
    result.addDiscardedRange(originalRange.copy());
    continue;
}
```

**修改后**：
```java
// 时间段完全在整体区间之前 → 丢弃
if (originalRange.getEndTime().isBefore(overallStart)) {
    result.addDiscardedRange(originalRange.copy());
    continue;
}

// 时间段完全在整体区间之后 → 作为边界参考保留
if (originalRange.getBeginTime().isAfter(overallEnd)) {
    FreeTimeRange boundaryRef = new FreeTimeRange()
            .setId(originalRange.getId())
            .setBeginTime(originalRange.getBeginTime())
            .setEndTime(originalRange.getEndTime())
            .setPriority(originalRange.getPriority())
            .setPromotionType(originalRange.getPromotionType());
    result.addBoundaryReference(boundaryRef);
    continue;
}
```

### findNextFreeRangeBoundary()

**修改**：合并 freeTimeRanges 和 boundaryReferences 进行查找

```java
private LocalDateTime findNextFreeRangeBoundary(
        LocalDateTime calcEnd,
        LocalDateTime fullUnitEnd,
        List<FreeTimeRange> freeTimeRanges,
        List<FreeTimeRange> boundaryReferences) {

    // 合并两个列表进行查找
    List<FreeTimeRange> allRanges = new ArrayList<>();
    if (freeTimeRanges != null) allRanges.addAll(freeTimeRanges);
    if (boundaryReferences != null) allRanges.addAll(boundaryReferences);

    // 原有查找逻辑...
}
```

---

## 结算影响分析

### buildPromotionCarryOver 不受影响

```java
// 只检查 mergedRanges（即 finalFreeRanges），不检查 boundaryReferences
for (FreeTimeRange range : finalFreeRanges) {
    if (range.getPromotionType() == FREE_RANGE) {
        if (!range.getEndTime().isAfter(calculationEndTime)) {
            usedFreeRanges.add(...);
        }
    }
}
```

**结论**：
- `boundaryReferences` 不在 `mergedRanges` 中
- 不会被计入 `usedFreeRanges`
- 不影响下次 CONTINUE 的优惠使用

---

## 完整流程示例

```
免费时段：09:20-09:50
计算窗口：07:30-09:00

1. FreeTimeRangeMerger.preprocessRanges():
   - 检测到 beginTime(09:20) > overallEnd(09:00)
   - 记录到 boundaryReferences

2. TimeRangeMergeResult:
   - mergedRanges: []（窗口内无免费时段）
   - boundaryReferences: [09:20-09:50]

3. PromotionEngine.evaluate():
   - freeTimeRanges = mergedRanges = []
   - boundaryReferences = [09:20-09:50]

4. 规则层延伸:
   - findNextFreeRangeBoundary() 检查 freeTimeRanges + boundaryReferences
   - 找到 09:20，延伸停在 09:20
   - calculationEndTime = 09:20

5. buildPromotionCarryOver():
   - finalFreeRanges = []（不包含 09:20-09:50）
   - usedFreeRanges = []（正确，没有使用）

6. 下次 CONTINUE:
   - 从 09:00 开始计算
   - 免费时段 09:20-09:50 仍然完整可用 ✅
```

---

## 测试场景

### 场景1：延伸停在优惠边界

| 参数 | 值 |
|------|-----|
| 免费时段 | 09:20-09:50 |
| 计算窗口 | 07:30-09:00 |
| 单元长度 | 60分钟 |
| 最后单元 | 08:30-09:00 |

**预期**：
- 延伸到 09:20
- calculationEndTime = 09:20
- usedFreeRanges = []

### 场景2：最后单元被免费时段覆盖

| 参数 | 值 |
|------|-----|
| 免费时段 | 09:00-11:00 |
| 计算窗口 | 08:00-10:00 |
| 单元长度 | 60分钟 |

**预期**：
- 最后单元 09:00-10:00 免费
- 延伸到 11:00
- calculationEndTime = 11:00
- usedFreeRanges = [09:00-10:00]

### 场景3：多个边界参考

| 参数 | 值 |
|------|-----|
| 免费时段 | 09:20-09:50, 10:00-11:00 |
| 计算窗口 | 07:30-09:00 |
| 单元长度 | 60分钟 |

**预期**：
- 延伸到 09:20（最近的边界）
- calculationEndTime = 09:20

---

## 设计原则检查

| 原则 | 是否违背 | 说明 |
|------|----------|------|
| 核心引擎只负责计算 | ❌ 不违背 | 优惠引擎仍然只做计算 |
| 规则必须是纯计算 | ❌ 不违背 | 规则逻辑不变，只是输入更完整 |
| 时间计算必须可重复 | ❌ 不违背 | 相同输入仍然得到相同结果 |
| 规则不应相互依赖 | ❌ 不违背 | 无新增依赖 |
| 规则配置与实现分离 | ❌ 不违背 | 无配置变更 |

---

## 文档版本

- 创建时间：2026-03-18
- 状态：已确认
- 作者：Claude Code