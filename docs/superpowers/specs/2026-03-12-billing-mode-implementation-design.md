# 计费模式差异化实现设计

## 概述

在 DayNightRule 和 RelativeTimeRule 中根据 `billingMode` 实现不同的计算方式，支持 UNIT_BASED 和 CONTINUOUS 两种模式。

## 模式行为对比

### UNIT_BASED（计费单位模式）

```
场景：停车 05:00 - 08:15，单元60分钟，免费时段 06:30-07:30

05:00-06:00  2元  (完整单元)
06:00-07:00  2元  (免费时段未完全覆盖单元 → 收费)
07:00-08:00  2元  (免费时段未完全覆盖单元 → 收费)
08:00-08:15  2元  (不足单元，收全额)
─────────────────
总计：8元
```

**特点**：
- 时间按固定单元对齐（从计费起点开始）
- 免费时段必须完全覆盖整个单元才免费
- 不足一个单元也收全额
- 封顶时按比例削减每个收费单元

### CONTINUOUS（连续时间模式）

```
场景：停车 05:00 - 08:15，单元60分钟，免费时段 06:30-07:30

05:00-06:00  2元  (完整单元)
06:00-06:30  2元  (在免费边界切分，收全额)
06:30-07:30  0元  (免费时段精确覆盖)
07:30-08:15  2元  (从免费结束重新计时，收全额)
─────────────────
总计：6元
```

**特点**：
- 在免费时段边界精确切分时间轴
- 切分后的片段从起点重新按单元划分
- 不足一个单元也收全额
- 封顶后截止，剩余时间合并为一个免费单元

## 核心差异

| 维度 | UNIT_BASED | CONTINUOUS |
|------|------------|------------|
| 单元对齐 | 固定从计费起点对齐 | 在免费边界切分后重新计时 |
| 免费判断 | 必须完全覆盖整个单元 | 精确覆盖时间段 |
| 片段拆分 | 无 | 切分后超过单元长度的片段继续拆分 |
| 封顶处理 | 按比例削减每个收费单元 | 封顶后截止，剩余时间合并为免费单元 |
| 周期封顶 | 按周期独立计算 | 按周期独立计算 |

## 封顶行为详解

### UNIT_BASED 封顶

```
场景：停车 05:00 - 12:00，封顶10元

原始：14元 → 按比例削减每个收费单元
结果：每个收费单元金额 × (10/14)
```

### CONTINUOUS 封顶

```
场景：停车 05:00 - 12:00，封顶10元

05:00-06:00  2元
06:00-06:30  2元
06:30-07:30  0元  (免费时段)
07:30-08:30  2元
08:30-09:30  2元
09:30-10:30  2元  ← 累计达到10元封顶
10:30-12:00  0元  (封顶截止，合并为免费单元)
─────────────────
总计：10元
```

**封顶截止边界**：封顶后截止到以下任意一个（先碰到哪个用哪个）：
- 计费结束时间
- 24小时周期结束时间
- 下一个免费时间段

**免费时段后继续免费**：封顶截止后遇到免费时段，免费时段结束后继续生成免费单元直至计费结束或周期结束。

## 设计方案

采用方案A：规则内部策略方法，但为未来重构为方案B（策略类分离）留出空间。

### 架构

```
BillingRule.calculate()
    ├── if UNIT_BASED → calculateUnitBased()
    └── if CONTINUOUS → calculateContinuous()

共享方法（两种模式复用）：
├── findFreePromotionId()      // 查找免费时段
├── findFreeTimeRangeById()    // 根据ID查找免费时段
├── calculateEffectiveFrom()   // 计算费用稳定开始时间
├── calculateEffectiveTo()     // 计算费用稳定结束时间
└── 周期划分相关逻辑
```

### DayNightRule 变更

```java
@Override
public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
        return calculateUnitBased(context, config, promotionAggregate);
    } else {
        return calculateContinuous(context, config, promotionAggregate);
    }
}

// 现有逻辑移入此方法（重命名）
private BillingSegmentResult calculateUnitBased(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    // 现有 UNIT_BASED 逻辑
}

// 新增 CONTINUOUS 模式实现
private BillingSegmentResult calculateContinuous(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
    // CONTINUOUS 逻辑
}
```

### RelativeTimeRule 变更

采用相同结构。

### CONTINUOUS 模式算法

```
1. 获取计费时间范围和免费时段列表
2. 按免费时段边界切分时间轴：
   a. 收集所有免费边界时间点
   b. 切分成多个时间片段
3. 对每个片段：
   a. 从片段起点开始按单元长度划分计费单元
   b. 片段内超过单元长度的部分继续拆分
   c. 不足单元长度收全额
   d. 判断免费（片段恰好等于某个免费时段）
4. 按周期应用封顶：
   a. 累计收费金额
   b. 达到封顶后，找到截止边界
   c. 截止边界后的所有单元合并为一个免费单元
5. 汇总结果
```

## 涉及文件

| 文件 | 变更类型 |
|------|----------|
| `DayNightRule.java` | 新增 calculateUnitBased/calculateContinuous 方法 |
| `RelativeTimeRule.java` | 新增 calculateUnitBased/calculateContinuous 方法 |

## 测试场景

### 测试1：免费时段边界切分

```
停车 05:00 - 08:15，单元60分钟，免费时段 06:30-07:30

UNIT_BASED: 8元
CONTINUOUS: 6元
```

### 测试2：封顶截止

```
停车 05:00 - 12:00，单元60分钟每单元2元，封顶10元

UNIT_BASED: 10元（按比例削减）
CONTINUOUS: 10元（封顶截止，后面免费）
```

### 测试3：跨周期

```
停车 05:00 - 次日 10:00，封顶每周期10元

两周期各自独立封顶
```

## 后续演进

未来可将 `calculateUnitBased` 和 `calculateContinuous` 抽取为独立的策略类（方案B），进一步解耦。当前实现应保持方法边界清晰，便于未来重构。