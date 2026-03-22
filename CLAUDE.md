# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 项目愿景

实现一个**可扩展、可追溯、可组合规则**的时间计费引擎（Time-based Billing Engine）。

适用场景：停车收费、场地使用费、设备租赁、按时间计费的服务费用。

**核心设计思想**：
```
时间轴 → 计费单元切割 → 应用优惠 → 应用收费规则 → 生成计费明细 → 汇总费用
```

---

## 系统目标

### 1. 可扩展规则系统
- 新增规则时**不修改核心计费引擎**，只需新增规则实现类
- 规则通过配置启用

### 2. 可追溯计费过程
- 必须输出完整的计费过程明细
- 方便用户查看账单、排查问题、调试

### 3. 支持继续计算
- 支持从已有计算结果继续计算
- 避免重复计算

### 4. 支持计费分段
- 收费规则可能随时间变化，每个 `BillingSegment` 独立计算

### 5. 支持多种优惠规则
- **FREE_RANGE**: 免费时间段
- **FREE_MINUTES**: 免费分钟数（避开已有免费时间段，像水流入石头缝隙）

---

## 开发原则（必须遵守）

### 原则1：核心引擎只负责计算
- ✅ 计费计算
- ❌ 缓存、数据库、持久化、日志存储（这些在外层实现）

### 原则2：规则必须是纯计算
- ✅ 无副作用：输入 BillingContext + RuleConfig + PromotionAggregate → 输出 BillingSegmentResult
- ❌ 访问数据库、调用远程接口、依赖外部状态

### 原则3：时间计算必须可重复
- 同样输入必须得到完全相同的结果（确定性）

### 原则4：规则不应相互依赖
- ❌ 规则 A 调用规则 B
- ✅ Engine → RuleA, Engine → RuleB（所有规则通过 Engine 统一执行）

### 原则5：规则配置与规则实现分离
- `RuleConfig`: 只描述规则参数
- `BillingRule`: 负责计算

### 原则6：简单优先，高级特性隔离
- 高级特性通过**配置化跳过**隔离，简单场景零判断开销
- 复杂度判断集中在计费开始时，后续关键点直接跳过

**简单场景定义**：
- 计费模式：UNIT_BASED 或 CONTINUOUS
- 继续模式：FROM_SCRATCH
- 优惠状态：无优惠 或 单一优惠类型
- 无特殊配置：无时间段封顶等

**跳过点**：
1. 状态恢复 - FROM_SCRATCH 模式跳过
2. 优惠处理 - 无优惠时跳过
3. 状态输出 - FROM_SCRATCH 模式简化

---

## 禁止事项

| 禁止行为 | 错误示例 |
|---------|---------|
| 在规则中访问数据库 | `rule.calculate()` 内部查询数据库 |
| 规则修改全局状态 | 修改全局变量、修改共享对象 |
| 规则改变计费流程 | 改变引擎执行顺序 |
| 规则之间相互调用 | RuleA → 调用 RuleB |

---

## 核心代码修改原则

当新计费规则无法通过现有核心计算逻辑满足时，**允许修改核心计算代码**，但必须满足：

1. **经过评估**：确认无法通过扩展规则实现
2. **不影响其他规则**：现有规则的测试必须全部通过
3. **向后兼容**：已有方案的计算结果保持不变

---

## 计费模式（BillingMode）

| 模式 | 说明 |
|------|------|
| CONTINUOUS | 连续时间模式，时间单位不固定，可被优惠/规则打断 |
| UNIT_BASED | 计费单位模式，时间被切分为固定单位，每个单位独立计算 |

**注意**：不同模式可能产生不同结果。规则必须通过 `supportedModes()` 声明支持的模式，不支持时抛异常。

计费模式由 `ChargingScheme` 决定，计费请求不允许直接指定 BillingMode。

---

## Build & Test

```bash
# Build all modules
mvn clean install

# Build single module
mvn clean install -pl core
mvn clean install -pl bill-test

# Run test (main entry point for testing)
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.PromotionTest"
```

## Architecture

This is a parking billing system with a modular Maven multi-module structure:

- **charge** (parent) - Root POM with shared dependencies (Lombok, Apache Commons, Jackson)
- **core** - Core billing engine and business logic
- **bill-test** - Test module that depends on core, contains integration tests

### Core Architecture (core module)

The billing calculation follows a pipeline architecture:

```
BillingService.calculate()
├── SegmentBuilder.buildSegments()     # Split by billing scheme changes
├── RuleResolver                       # Resolve charging & promotion rules per segment
├── PromotionEngine.evaluate()         # Aggregate free time ranges & minutes
├── BillingCalculator.calculate()      # Apply billing rules with promotions
└── ResultAssembler.assemble()         # Combine segment results
```

### Key Components

**Billing Flow:**
- `BillingService` - Orchestrates the billing calculation pipeline
- `SegmentBuilder` - Splits time range into segments based on `SchemeChange` events
- `RuleResolver` - Interface for resolving `RuleConfig` and `PromotionRuleConfig` by schemeId + time range
- `PromotionEngine` - Aggregates promotions from rules and external sources (coupons)
- `BillingCalculator` - Delegates to registered `BillingRule` implementations
- `ResultAssembler` - Combines segment results into final `BillingResult`

**Rule System:**
- `BillingRuleRegistry` / `PromotionRuleRegistry` - Strategy pattern registries
- Rules are resolved by type string constants in `BConstants.ChargeRuleType` / `PromotionRuleType`
- Built-in rules: `DayNightRule` (charges by day/night time periods), `FreeMinutesPromotionRule`, `FreeTimeRangePromotionRule`

**Promotion Types:**
- `FREE_RANGE` - Free time periods (e.g., 01:00-04:00 is free)
- `FREE_MINUTES` - Free minutes allocated optimally within the time window
- Promotions can come from rules (scheme-based) or external sources (coupons)
- Priority-based resolution when multiple promotions apply

**Segment Calculation Modes (`SegmentCalculationMode`):**
- `SINGLE` - Only single segment calculation
- `SEGMENT_LOCAL` - Each segment calculates from its own start time
- `GLOBAL_ORIGIN` - Global time window with segment截取

### Data Model

Key POJOs in `billing.pojo` and `promotion.pojo`:
- `BillingRequest` - Input with time range, schemeId/schemeChanges, external promotions
- `BillingResult` - Output with billing units, promotion usages, final amount
- `BillingContext` - Immutable context holding segment, window, rules for calculation
- `PromotionAggregate` - Aggregated free time ranges and usage tracking

### Implementation Notes

- `ChargingEngine` is a placeholder (returns null)
- Rule implementations use generic type safety with `configClass()` for type checking
- Lombok used extensively for POJOs (`@Data`, `@Builder`, `@AllArgsConstructor`, `@Accessors(chain=true)`)

### DayNightRule Implementation

The `DayNightRule` implements time-based billing with:
- **24-hour billing cycle** starting from the billing begin time
- **Day/night pricing**: Different unit prices for day period (e.g., 12:20-19:00) vs night period
- **Daily cap**: Maximum charge per 24-hour cycle
- **Block weight threshold**: When a billing unit spans both day and night, use day price if day minutes ratio >= blockWeight (0.5), otherwise use night price
- **Free time ranges**: Promotions that completely cover a billing unit make it free with `freePromotionId="DAILY_CAP"`

---

## 计费单元延伸机制

### 延伸目的

延伸是为了预测下次 CONTINUE 模式的起点，提供"费用稳定时间窗口"。

### 延伸规则

1. **普通情况**：延伸到完整单元长度，不超过下一个边界
2. **封顶情况**：封顶免费单元（CYCLE_CAP/DAILY_CAP）可延伸到下一个周期边界
3. **遇优惠边界**：延伸停在优惠边界，不进入未处理的优惠区域

### 延伸与优惠交互

**核心原则**：优惠分配在延伸之前，延伸不应"闯入"新的优惠区域。

**典型场景**：
```
免费时段：09:20-09:50
计算窗口：07:30-09:00
单元长度：60分钟
最后单元：08:30-09:00

期望延伸到：09:20（停在免费时段边界）
```

**解决方案**：
- `TimeRangeMergeResult.boundaryReferences`：存储窗口外的免费时段，作为延伸边界参考
- 边界参考时段**不参与**当前窗口的优惠结算，**不影响** usedFreeRanges
- 延伸时检查 `freeTimeRanges + boundaryReferences`，停在最近的优惠边界

**实现要点**：
- `FreeTimeRangeMerger.preprocessRanges()`：窗口后的时段记录到 boundaryReferences
- `PromotionAggregate.boundaryReferences`：传递边界参考给规则层
- `findNextFreeRangeBoundary()`：检查两个列表，找到最近的边界

---

## 优惠结算逻辑

### usedFreeRanges 记录规则

只记录在当前计算窗口内**实际使用**的免费时段部分：
- 时段完全在窗口内：完整记录
- 时段跨越窗口边界：只记录窗口内部分
- 时段完全在窗口外：不记录（作为边界参考保留）

### calculationEndTime 语义

- 表示"已计算到哪里"
- 可能延伸超过请求的计算窗口结束时间
- 下次 CONTINUE 从此时间点开始