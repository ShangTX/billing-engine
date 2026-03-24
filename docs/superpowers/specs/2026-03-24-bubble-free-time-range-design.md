# 气泡型免费时间段设计

## 日期
2026-03-24

---

## 一、背景

### 问题陈述

某些场景下，免费时间段不应只是"扣除"计费时间，还应"延长"计费周期。

**典型场景**：
- 停车场会员权益：每天1小时免费停车，但这1小时不压缩计费周期
- 充电桩优惠：充电过程中赠送的免费时段，不应让后续费率时段提前到来

### 目标

实现"气泡型"免费时间段，该类型免费时段会延长计费周期，后续时间段边界整体后移。

---

## 二、概念定义

### 2.1 与普通免费时间段的区别

| 类型 | 模型 | 效果 |
|------|------|------|
| 免费时间段（普通） | 气泡抽出 | 周期时长不变，相对位置连续 |
| 免费时间段（气泡型） | 气泡弹开 | 周期时长延长，后续边界后移 |

### 2.2 气泡弹开模型

```
水管（时间轴）：
|===水===|气泡|===水===|    → 气泡将水向两端"弹开"

时间轴：
|--计费--|--免费--|--计费--|    → 免费时间段将后续边界"弹开"
```

**核心规则**：
1. 气泡前的段：边界不变
2. 气泡后的段：边界整体后移 = 气泡长度

### 2.3 典型案例

```
气泡型免费时间段：11:00-12:00（60分钟）
计费起点：08:00
周期长度：24小时

无气泡时：
├── 周期终点：次日 08:00

有气泡时：
├── 周期延长 60 分钟
└── 周期终点：次日 09:00
```

---

## 三、设计方案

### 3.1 设计原则

**避免特殊对待**：气泡型免费时间段除了延长周期边界的特性外，其他处理逻辑与普通免费时间段保持一致：
- 复用 `usedFreeRanges` 记录使用情况
- 复用 `filterUsedFreeRanges` 过滤已使用部分
- 复用优惠结转机制

### 3.2 数据结构

**FreeTimeRange 增加类型字段**：

```java
public class FreeTimeRange {
    private String id;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private int priority;
    private BConstants.PromotionType promotionType;

    // 新增：区分普通型/气泡型
    private FreeTimeRangeType rangeType; // 默认 NORMAL
}
```

**新增枚举**：

```java
public enum FreeTimeRangeType {
    NORMAL,  // 普通免费时间段
    BUBBLE   // 气泡型免费时间段（延长周期）
}
```

**无需改动**：
- `PromotionCarryOver.java` — 复用现有 `usedFreeRanges`
- `RuleState` — 无需新增字段
- `BConstants.PromotionType` — 无需新增枚举

### 3.3 核心流程

```
calculateContinuous 流程：
├── 1. 恢复状态（包括 usedFreeRanges）
├── 2. 计算周期边界延长 = usedFreeRanges 中气泡型时段总长度
├── 3. cycleBoundary = 原始边界 + 周期延长
├── 4. 过滤已使用免费时段（复用 filterUsedFreeRanges）
├── 5. 计算并生成计费单元
├── 6. 更新 usedFreeRanges（包含本次使用的气泡部分）
└── 7. 输出 cycleBoundary（已延长）
```

### 3.4 跨计算段场景处理

气泡型免费时段可能跨越 CONTINUE 计算段：

```
第一次计算 (08:00-12:00)：
├── 气泡型免费时段：11:00-13:00
├── 本次使用气泡部分：11:00-12:00（60分钟）
├── 周期延长：60分钟 → cycleBoundary = 次日 09:00
├── usedFreeRanges 记录：11:00-12:00
└── 结转未用气泡：12:00-13:00

第二次计算 (CONTINUE 12:00-18:00)：
├── 恢复 cycleBoundary = 次日 09:00
├── 恢复 usedFreeRanges：11:00-12:00
├── 原始气泡时段过滤后剩余：12:00-13:00
├── 本次使用气泡部分：12:00-13:00（60分钟）
├── 周期再延长：60分钟 → cycleBoundary = 次日 10:00
└── usedFreeRanges 更新：11:00-13:00（合并）
```

**关键点**：
- 气泡部分使用：只对计算窗口内的部分延长周期
- 结转未用部分：通过 `usedFreeRanges` 过滤自动实现
- 累积延长：每次计算根据已使用气泡，累积延长周期边界

---

## 四、与现有功能结合

### 4.1 CONTINUE 模式

| 状态 | 处理方式 |
|------|---------|
| cycleBoundary | 结转时已包含延长，恢复时直接使用 |
| usedFreeRanges | 复用现有机制，气泡型统一处理 |

**无冲突**：周期边界延长体现在 `cycleBoundary` 中，CONTINUE 恢复时自然正确。

### 4.2 长期简化计算

**原则**：有气泡型免费时段的周期不简化。

**简化时的边界计算**：
- 触发简化说明前面的周期已处理完毕
- 当前 `cycleBoundary` 已包含之前的延长
- 直接用 `cycleBoundary` 作为简化周期的起点

```java
// 简化单元时间计算
LocalDateTime simplifiedStart = state.getCycleBoundary(); // 已延长
LocalDateTime simplifiedEnd = simplifiedStart.plusHours(24 * cycleCount);
```

**无冲突**：有气泡不简化，简化时使用已延长的边界。

### 4.3 优惠结转

**完全复用现有机制**：

| 维度 | 普通型 | 气泡型 |
|------|--------|--------|
| 记录位置 | usedFreeRanges | usedFreeRanges |
| 过滤逻辑 | filterUsedFreeRanges | filterUsedFreeRanges |
| 额外效果 | 无 | 周期边界延长 |

---

## 五、实现计划

### Phase 1: 数据结构定义

1. 新增 `FreeTimeRangeType` 枚举
2. `FreeTimeRange` 增加 `rangeType` 字段

### Phase 2: 公共逻辑提取

1. `AbstractTimeBasedRule.calculateBubbleExtension()` — 计算周期延长
2. 修改 `initializeState` 中的边界计算逻辑
3. 修改 `restoreState` 中的边界计算逻辑

### Phase 3: 规则实现

1. `DayNightRule.calculateContinuous()` — 集成周期延长
2. `RelativeTimeRule.calculateContinuous()` — 同上
3. `CompositeTimeRule.calculateContinuous()` — 同上

### Phase 4: 测试验证

1. 单气泡场景测试
2. 多气泡场景测试
3. 跨计算段气泡测试
4. CONTINUE 模式测试
5. 与简化计算结合测试

---

## 六、影响范围

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `FreeTimeRange.java` | 修改 | 增加 rangeType 字段 |
| `FreeTimeRangeType.java` | 新增 | 枚举定义 |
| `AbstractTimeBasedRule.java` | 修改 | 周期延长计算逻辑 |
| `DayNightRule.java` | 修改 | 集成周期延长 |
| `RelativeTimeRule.java` | 修改 | 集成周期延长 |
| `CompositeTimeRule.java` | 修改 | 集成周期延长 |

---

## 七、待实现优化

### 扩大长期简化的适用条件

**当前条件**：周期内无任何优惠 → 可简化

**潜在优化方向**：
- 有普通免费时段但不影响周期边界 → 是否可简化？
- 需要评估简化的判断条件和计算复杂度
- 另行设计讨论