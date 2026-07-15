# 新增 naturalTime 简化规则

---
id: TODO-20260508-006
type: feature
priority: P2
status: done
source_git: b1a1a6c
created_at: 2026-05-08
completed_at: 2026-05-18
---

## 背景

现有 `dayNight` 规则只能表达白天和夜间两个自然时间段。业务需要把 24 小时按自然时间划分为 1 个或多个时间段，每个时间段有独立价格。

`compositeTime` 技术上可覆盖此需求，但配置复杂（需理解 CompositePeriod + NaturalPeriod）。需要一个简化版本。

## 决策结果

**采用方案 B：新增简化版 `naturalTime` 规则**

## 规则定位

| 规则 | 配置复杂度 | 适用场景 |
|------|------------|----------|
| `dayNight` | 最简单 | 固定日夜二元 |
| `naturalTime` | 简单 | 多自然时段 |
| `relativeTime` | 中等 | 相对时段 |
| `compositeTime` | 最复杂 | 组合场景 |

## naturalTime 设计

### 配置结构

```java
public class NaturalTimeConfig implements RuleConfig {
    String id;
    
    /**
     * 自然时段列表，必须覆盖 0-1440 分钟
     */
    List<NaturalPeriod> periods;
    
    /**
     * 计费单元长度（分钟），统一时长
     */
    int unitMinutes;
    
    /**
     * 每日封顶（可选）
     */
    BigDecimal maxChargeOneDay;
}
```

### NaturalPeriod 结构

```java
public class NaturalPeriod {
    int beginMinute;    // 开始分钟（0-1440）
    int endMinute;      // 结束分钟（0-1440）
    BigDecimal unitPrice;  // 单元价格
}
```

### 与 compositeTime 的区别

| 对比项 | naturalTime | compositeTime |
|--------|-------------|---------------|
| 周期类型 | 固定 24 小时自然周期 | 可配置相对周期 |
| 单元时长 | 统一 `unitMinutes` | 各时段可不同 |
| 时段类型 | 仅自然时段 | 自然时段 + 相对时段 |
| 配置层次 | 一层（periods） | 两层（CompositePeriod + NaturalPeriod） |

## 实施进展

### 已完成（2026-05-18）

1. **BConstants 更新**
   - 移除 `NATURAL_TIME` 的 deprecated 标记
   - 作为正式规则类型常量

2. **NaturalTimeConfig 配置类**
   - `periods`: 自然时段列表
   - `unitMinutes`: 统一单元时长
   - `maxChargeOneDay`: 每日封顶（可选）
   - `simplifiedSupported`: 简化计算支持标记

3. **NaturalTimeRule 规则实现**
   - 继承 `AbstractTimeBasedRule<NaturalTimeConfig>`
   - 支持 `UNIT_BASED` 和 `CONTINUOUS` 模式
   - 时段覆盖校验
   - 自然时段边界统一切断

4. **子组件拆分（遵循已有优化原则）**
   - `NaturalTimeUnitBasedCalculator`: UNIT_BASED 模式计算
   - `NaturalTimeContinuousCalculator`: CONTINUOUS 模式计算
   - `NaturalTimePeriodResolver`: 时段定位
   - `NaturalTimeCrossPeriodPriceResolver`: 跨时段价格计算
   - `NaturalTimeCycleStateManager`: 周期状态管理

5. **规则注册**
   - 已注册到 `BillingRuleRegistry`
   - 可通过 `BConstants.ChargeRuleType.NATURAL_TIME` 获取

6. **测试验证**
   - `NaturalTimeSmokeTest`: 5 个测试场景全部通过
   - UNIT_BASED 基本计算、跨时段高价模式、每日封顶
   - CONTINUOUS 基本计算、跨时段切分

## 验收标准

- ✓ 可配置 1-N 个自然时段覆盖全天
- ✓ 自然时段边界统一切断
- ✓ 配置比 compositeTime 简洁直观
- ✓ 测试和文档同步完成

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/`（新建）
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/NaturalPeriod.java`（复用）
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRuleRegistry.java`（注册）
- `bill-test/src/test/java/cn/shang/charging/NaturalTimeSmokeTest.java`（测试）

## 备注

naturalTime 是简化版 compositeTime，只关注自然时段场景，配置更直观。

2026-07-15 修订：为避免 non-dayNight 规则继续扩散跨时段归属策略，`naturalTime`
移除 `crossPeriodMode` 配置，统一在自然时段边界切断；不足单元收费仍通过
`incompleteUnitChargeMode` / `thresholdMinutes` / `thresholdRatio` 控制。
