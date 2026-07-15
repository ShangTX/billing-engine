# 时间计费引擎 — 使用指南

本文是面向调用方（人类开发者和 AI agent）的唯一详细使用说明。
README 只作为项目入口；完整接入方式、API 契约和字段语义以本文为准。

> **AI agent 注意**：本文包含完整包名、类名、字段名、枚举值和代码示例。
> 生成代码时应优先参考本文「常用包名速查」章节获取准确 import 路径。

---

## 目录

1. [适用场景](#1-适用场景)
2. [常用包名速查](#2-常用包名速查)
3. [安装与模块选择](#3-安装与模块选择)
4. [核心概念](#4-核心概念)
5. [手动接入（纯 Java）](#5-手动接入纯-java)
6. [Spring Boot 接入](#6-spring-boot-接入)
7. [发起计费](#7-发起计费)
8. [请求参数详解](#8-请求参数详解)
9. [结果结构详解](#9-结果结构详解)
10. [计费规则](#10-计费规则)
11. [优惠系统](#11-优惠系统)
12. [方案切换（多段计费）](#12-方案切换多段计费)
13. [时间取整](#13-时间取整)
14. [优惠等效金额](#14-优惠等效金额)
15. [计算模式详解](#15-计算模式详解)
16. [自定义计费规则](#16-自定义计费规则)
17. [常用枚举与常量](#17-常用枚举与常量)
18. [设计原则与禁止事项](#18-设计原则与禁止事项)

---

## 1. 适用场景

本项目是一个**时间计费引擎**，适用于：
- 停车收费
- 场地/设备租赁
- 服务时长计费
- 其他按时间收费的场景

核心流程：

```
时间轴 → 方案分段 → 优惠聚合 → 应用计费规则 → 生成计费明细 → 汇总费用
```

---

## 2. 常用包名速查

### 2.1 核心计费类

| 类 | 包名 |
|------|------|
| `BillingService` | `cn.shang.charging.billing.BillingService` |
| `BillingConfigResolver` | `cn.shang.charging.billing.BillingConfigResolver` |
| `BillingCalculator` | `cn.shang.charging.billing.BillingCalculator` |
| `SegmentBuilder` | `cn.shang.charging.billing.SegmentBuilder` |
| `CalculationWindowFactory` | `cn.shang.charging.billing.CalculationWindowFactory` |
| `PromotionEquivalentCalculator` | `cn.shang.charging.billing.PromotionEquivalentCalculator` |
| `ResultAssembler` | `cn.shang.charging.settlement.ResultAssembler` |

### 2.2 核心 POJO

| 类 | 包名 |
|------|------|
| `BillingRequest` | `cn.shang.charging.billing.pojo.BillingRequest` |
| `BillingResult` | `cn.shang.charging.billing.pojo.BillingResult` |
| `BillingContext` | `cn.shang.charging.billing.pojo.BillingContext` |
| `BillingUnit` | `cn.shang.charging.billing.pojo.BillingUnit` |
| `BillingSegmentResult` | `cn.shang.charging.billing.pojo.BillingSegmentResult` |
| `CalculationWindow` | `cn.shang.charging.billing.pojo.CalculationWindow` |
| `DurationSegment` | `cn.shang.charging.billing.pojo.DurationSegment` |
| `EquivalentAmountSpec` | `cn.shang.charging.billing.pojo.EquivalentAmountSpec` |
| `IncompleteUnitChargeSpec` | `cn.shang.charging.billing.pojo.IncompleteUnitChargeSpec` |
| `SchemeChange` | `cn.shang.charging.billing.pojo.SchemeChange` |
| `RuleConfig` | `cn.shang.charging.billing.pojo.RuleConfig` |
| `PromotionRuleConfig` | `cn.shang.charging.billing.pojo.PromotionRuleConfig` |
| `BConstants` | `cn.shang.charging.billing.pojo.BConstants` |
| `TimeRoundingMode` | `cn.shang.charging.billing.pojo.TimeRoundingMode` |
| `SegmentContext` | `cn.shang.charging.billing.pojo.SegmentContext` |

### 2.3 billing-api 便捷封装

| 类 | 包名 |
|------|------|
| `BillingTemplate` | `cn.shang.charging.wrapper.BillingTemplate` |
| `PromotionSavingsAnalyzer` | `cn.shang.charging.wrapper.PromotionSavingsAnalyzer` |

### 2.4 计费规则类

| 类 | 包名 |
|------|------|
| `BillingRule` | `cn.shang.charging.charge.rules.BillingRule` |
| `BillingRuleRegistry` | `cn.shang.charging.charge.rules.BillingRuleRegistry` |
| `DayNightRule` | `cn.shang.charging.charge.rules.daynight.DayNightRule` |
| `DayNightConfig` | `cn.shang.charging.charge.rules.daynight.DayNightConfig` |
| `RelativeTimeRule` | `cn.shang.charging.charge.rules.relativetime.RelativeTimeRule` |
| `RelativeTimeConfig` | `cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig` |
| `RelativeTimePeriod` | `cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod` |
| `NaturalTimeRule` | `cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule` |
| `NaturalTimeConfig` | `cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig` |
| `CompositeTimeRule` | `cn.shang.charging.charge.rules.compositetime.CompositeTimeRule` |
| `CompositeTimeConfig` | `cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig` |
| `CompositePeriod` | `cn.shang.charging.charge.rules.compositetime.CompositePeriod` |
| `NaturalPeriod` | `cn.shang.charging.charge.rules.compositetime.NaturalPeriod` |
| `CrossPeriodMode` | `cn.shang.charging.charge.rules.compositetime.CrossPeriodMode` |
| `FlatFreeRule` | `cn.shang.charging.charge.rules.flatfree.FlatFreeRule` |
| `FlatFreeConfig` | `cn.shang.charging.charge.rules.flatfree.FlatFreeConfig` |

### 2.5 优惠类

| 类 | 包名 |
|------|------|
| `PromotionEngine` | `cn.shang.charging.promotion.PromotionEngine` |
| `FreeTimeRangeMerger` | `cn.shang.charging.promotion.FreeTimeRangeMerger` |
| `FreeMinuteAllocator` | `cn.shang.charging.promotion.FreeMinuteAllocator` |
| `PromotionAggregateUtil` | `cn.shang.charging.promotion.PromotionAggregateUtil` |
| `PromotionRule` | `cn.shang.charging.promotion.rules.PromotionRule` |
| `PromotionRuleRegistry` | `cn.shang.charging.promotion.rules.PromotionRuleRegistry` |
| `FreeMinutesPromotionRule` | `cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule` |
| `FreeMinutesPromotionConfig` | `cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig` |
| `StartFreePromotionRule` | `cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule` |
| `StartFreePromotionConfig` | `cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig` |
| `PromotionGrant` | `cn.shang.charging.promotion.pojo.PromotionGrant` |
| `PromotionUsage` | `cn.shang.charging.promotion.pojo.PromotionUsage` |
| `PromotionAggregate` | `cn.shang.charging.promotion.pojo.PromotionAggregate` |
| `FreeTimeRange` | `cn.shang.charging.promotion.pojo.FreeTimeRange` |
| `FreeTimeRangeType` | `cn.shang.charging.promotion.pojo.FreeTimeRangeType` |
| `FreeMinutes` | `cn.shang.charging.promotion.pojo.FreeMinutes` |
| `FreeMinuteAllocationResult` | `cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult` |
| `TimeRangeMergeResult` | `cn.shang.charging.promotion.pojo.TimeRangeMergeResult` |

---

## 3. 安装与模块选择

**环境要求**：JDK 21+，Maven 3.6+（JDK 25 已验证兼容）。

### 3.1 推荐：billing-api

`billing-api` 提供 `BillingTemplate`，封装了基础计费、时间取整、优惠等效金额等便捷方法。
**普通调用方应优先使用此模块。**

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>2.1.2</version>
</dependency>
```

### 3.2 Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>2.1.2</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>2.1.2</version>
</dependency>
```

Starter 自动注册 `dayNight`、`compositeTime`、`relativeTime` 计费规则和 `freeMinutes` 优惠规则。

### 3.3 直接使用 core

仅在需要完全控制组件组装时使用。

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-core</artifactId>
    <version>2.1.2</version>
</dependency>
```

### 模块依赖关系

```
billing-v3/v4-spring-boot-starter → billing-api → core（无外部依赖）
bill-test → core, billing-api（测试与示例）
```

---

## 4. 核心概念

### 4.1 计费管道

```
BillingService.calculate()
├── SegmentBuilder.buildSegments()       # 按方案切换切割分段
├── BillingConfigResolver                # 调用方实现：解析每个分段的规则、优惠、模式
├── PromotionEngine.evaluate()           # 聚合优惠（免费时段、免费分钟、折扣、减免）
├── BillingCalculator.calculate()        # 分派到具体 BillingRule 执行计费
└── ResultAssembler.assemble()           # 汇总分段结果（compact 段内直接产出，跨段不合并）
```

### 4.2 关键接口/类

| 角色 | 类/接口 | 说明 |
|------|---------|------|
| 调用入口 | `BillingTemplate` | 便捷 API，推荐直接使用 |
| 核心调度 | `BillingService` | 编排完整计费管道 |
| 配置解析 | `BillingConfigResolver` | **调用方必须实现**：按方案ID返回规则配置 |
| 规则接口 | `BillingRule<C extends RuleConfig>` | 计费规则实现接口 |
| 输入 | `BillingRequest` | 计费请求 POJO |
| 输出 | `BillingResult` | 计费结果 POJO |
| 单元明细 | `BillingUnit` | 最小计费单元（CONTINUOUS/UNIT_BASED 模式） |
| 时长段 | `DurationSegment` | 时长计费段（DURATION_PERIOD/DURATION_GLOBAL 模式） |
| 外部优惠 | `PromotionGrant` | 优惠券等外部输入的优惠 |

---

## 5. 手动接入（纯 Java）

### 5.1 实现 BillingConfigResolver

这是接入的核心：告诉引擎「每个方案用什么规则、什么优惠、什么计算模式」。

```java
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class MyBillingConfigResolver implements BillingConfigResolver {

    @Override
    public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
        return BConstants.CalculationMode.CONTINUOUS;
    }

    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd,
                                          Map<String, Object> context) {
        // 返回具体规则配置（如 DayNightConfig）
        return new DayNightConfig()
                .setId("daynight-1")
                .setDayBeginMinute(8 * 60)       // 白天开始：08:00
                .setDayEndMinute(20 * 60)         // 白天结束：20:00
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setUnitMinutes(60);
    }

    @Override
    public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                           LocalDateTime segmentStart,
                                                           LocalDateTime segmentEnd,
                                                           Map<String, Object> context) {
        return List.of();  // 无方案内优惠
    }
}
```

### 5.2 组装组件并创建 BillingTemplate

```java
import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.charge.rules.*;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.promotion.*;
import cn.shang.charging.promotion.rules.*;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;

BillingConfigResolver configResolver = new MyBillingConfigResolver();

// 1. 注册计费规则（构造函数已注册 dayNight/relativeTime/compositeTime，按需补充）
BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
billingRuleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());

// 2. 注册优惠规则
PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

// 3. 组装引擎
PromotionEngine promotionEngine = new PromotionEngine(
        configResolver,
        new FreeTimeRangeMerger(),
        promotionRuleRegistry
);

BillingService billingService = new BillingService(
        new SegmentBuilder(),
        configResolver,
        promotionEngine,
        new BillingCalculator(billingRuleRegistry),
        new ResultAssembler()
);

// 4. 创建便捷入口
BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);
```

---

## 6. Spring Boot 接入

### 6.1 添加依赖

参见 [3.2 节](#32-spring-boot-starter)。

### 6.2 提供 BillingConfigResolver Bean

Starter 已自动注册所有引擎组件，业务侧只需提供 `BillingConfigResolver` 实现：

```java
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MyBillingConfigResolver implements BillingConfigResolver {

    @Override
    public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
        return BConstants.CalculationMode.CONTINUOUS;
    }

    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd,
                                          Map<String, Object> context) {
        // 根据 schemeId 返回对应规则配置
        // ...
        return null;
    }

    @Override
    public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                           LocalDateTime segmentStart,
                                                           LocalDateTime segmentEnd,
                                                           Map<String, Object> context) {
        return List.of();
    }
}
```

### 6.3 注入 BillingTemplate

```java
import cn.shang.charging.wrapper.BillingTemplate;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import org.springframework.stereotype.Service;

@Service
public class BillingAppService {

    private final BillingTemplate billingTemplate;

    public BillingAppService(BillingTemplate billingTemplate) {
        this.billingTemplate = billingTemplate;
    }

    public BillingResult calculate(BillingRequest request) {
        return billingTemplate.calculate(request);
    }
}
```

### 6.4 Starter 自动装配的 Bean 列表

| Bean | 类型 |
|------|------|
| `billingRuleRegistry` | `BillingRuleRegistry`（已注册 dayNight/relativeTime/compositeTime） |
| `promotionRuleRegistry` | `PromotionRuleRegistry`（已注册 freeMinutes） |
| `freeTimeRangeMerger` | `FreeTimeRangeMerger` |
| `promotionEngine` | `PromotionEngine` |
| `billingService` | `BillingService` |
| `billingTemplate` | `BillingTemplate` |

所有 Bean 均标注 `@ConditionalOnMissingBean`，可通过自定义 Bean 覆盖。

---

## 7. 发起计费

### 7.1 基础调用

```java
import cn.shang.charging.billing.pojo.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

BillingRequest request = new BillingRequest();
request.setBeginTime(LocalDateTime.of(2026, 5, 8, 9, 0));
request.setEndTime(LocalDateTime.of(2026, 5, 8, 12, 30));
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);

// 获取结果
BigDecimal finalAmount = result.getFinalAmount();
List<BillingUnit> units = result.getUnits();
List<PromotionUsage> usages = result.getPromotionUsages();
```

### 7.2 带外部优惠的调用

```java
import cn.shang.charging.promotion.pojo.PromotionGrant;

// 外部 30 分钟免费
PromotionGrant freeMinutes = PromotionGrant.builder()
        .id("coupon-30min")
        .type(BConstants.PromotionType.FREE_MINUTES)
        .source(BConstants.PromotionSource.COUPON)
        .freeMinutes(30)
        .priority(1)
        .build();

BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
request.setExternalPromotions(List.of(freeMinutes));

BillingResult result = billingTemplate.calculate(request);
```

---

## 8. 请求参数详解

### BillingRequest 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | `String` | 否 | 请求标识，用于追踪和日志 |
| `beginTime` | `LocalDateTime` | **是** | 计费开始时间 |
| `endTime` | `LocalDateTime` | **是** | 计费结束时间 |
| `calcEndTime` | `LocalDateTime` | 否 | 实际计算终点（用于查询时点/局部计算），不提供时使用 endTime |
| `schemeId` | `String` | 条件 | 单方案 ID，与 `schemeChanges` 二选一 |
| `schemeChanges` | `List<SchemeChange>` | 条件 | 方案切换时间轴，与 `schemeId` 二选一 |
| `segmentCalculationMode` | `BConstants.SegmentCalculationMode` | **是** | 分段计算方式：`SINGLE`（单段）/ `SEGMENT_LOCAL`（分段独立起算） |
| `externalPromotions` | `List<PromotionGrant>` | 否 | 外部优惠列表（跨分段共享，整笔计费享一次） |
| `timeRoundingMode` | `TimeRoundingMode` | 否 | 时间取整模式（默认 `CEIL_BEGIN_TRUNCATE_END`） |
| `context` | `Map<String, Object>` | 否 | 传递给 `BillingConfigResolver` 的自定义上下文参数 |
| `disableSimplification` | `Boolean` | 否 | 精确查询时设为 true，禁用简化计算以保证完整明细 |
| `equivalentAmountSpec` | `EquivalentAmountSpec` | 否 | 等效金额计算规格（详见[第14节](#14-优惠等效金额)） |

### SchemeChange 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastSchemeId` | `String` | 变更前方案 ID |
| `nextSchemeId` | `String` | 变更后方案 ID |
| `changeTime` | `LocalDateTime` | 变更发生时间（分段切割点） |

---

## 9. 结果结构详解

### 9.1 BillingResult

| 字段 | 类型 | 说明 |
|------|------|------|
| `units` | `List<BillingUnit>` | 计费单元明细（CONTINUOUS/UNIT_BASED 模式产出） |
| `durationSegments` | `List<DurationSegment>` | 时长计费段明细（DURATION_PERIOD/DURATION_GLOBAL 模式产出） |
| `promotionUsages` | `List<PromotionUsage>` | 优惠使用记录（含 source、equivalentAmount） |
| `finalAmount` | `BigDecimal` | **最终应收金额**（各分段 chargedAmount 之和） |
| `totalEquivalentAmount` | `BigDecimal` | 等效优惠金额汇总（仅当 equivalentAmountSpec 非 null 时有值） |
| `calculationEndTime` | `LocalDateTime` | 实际计算窗口结束时间 |

> **注意**：`units` 和 `durationSegments` 互斥 — CONTINUOUS/UNIT_BASED 产出 `units`，DURATION_PERIOD/DURATION_GLOBAL 产出 `durationSegments`。

### 9.2 BillingUnit

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginTime` | `LocalDateTime` | 单元开始时间 |
| `endTime` | `LocalDateTime` | 单元结束时间 |
| `durationMinutes` | `int` | 单元时长（分钟） |
| `unitPrice` | `BigDecimal` | 单元单价（由具体规则解释） |
| `originalAmount` | `BigDecimal` | 原始金额（优惠前） |
| `chargedAmount` | `BigDecimal` | 实际收费金额（优惠后） |
| `accumulatedAmount` | `BigDecimal` | 段内累计金额（从分段起点到本单元的 chargedAmount 之和） |
| `free` | `boolean` | 是否被优惠完全覆盖（免费） |
| `freePromotionId` | `String` | 免费原因（优惠 ID 或特殊标记如 `"PERIOD_CAP"`、`"CYCLE_CAP"`） |
| `isTruncated` | `Boolean` | 是否不足一个完整单元（含末段截断与段内余数，按不足单元计费模式处理） |
| `compact` | `boolean` | 是否为 compact 单元（段内 N 个连续相同整单元合并产出） |
| `count` | `int` | compact 单元代表的子单元数量（非 compact 始终为 1） |
| `ruleData` | `Object` | 规则扩展数据（规则私有） |

### 9.3 DurationSegment

`record DurationSegment`，不可变。

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginTime` | `LocalDateTime` | 段起点 |
| `endTime` | `LocalDateTime` | 段终点 |
| `periodLabel` | `String` | 时段标签（"day"/"night"/"period-1"，规则自定义；`compositeTime` 为 `r:x-y|n:a-b`，同时表达外层相对时段和内部自然时段） |
| `chargedMinutes` | `int` | 收费分钟数（免费段=0） |
| `unitPrice` | `BigDecimal` | 单价 |
| `chargedAmount` | `BigDecimal` | 应收金额（时段封顶后，周期封顶前） |
| `periodCap` | `BigDecimal` | 该时段封顶金额（null=无封顶） |
| `freePromotionId` | `String` | 免费段对应的 FreeTimeRange.id（非免费段为 null） |
| `originalAmount` | `BigDecimal` | 按规则原价（封顶前；免费段非 0，用于等效金额计算） |

> `DURATION_GLOBAL` 产出为全局收费汇总桶：同质收费分钟会聚合到一个 `DurationSegment`，`beginTime` / `endTime` 为 `null`，免费分钟和免费时段通过 `PromotionUsage` 跟踪，不再作为时间轴免费段落盘。

### 9.4 PromotionUsage

| 字段 | 类型 | 说明 |
|------|------|------|
| `promotionId` | `String` | 优惠来源 ID |
| `type` | `BConstants.PromotionType` | 优惠类型 |
| `source` | `BConstants.PromotionSource` | 优惠来源：`RULE`（方案内）/ `COUPON`（外部优惠券） |
| `grantedMinutes` | `long` | 授予的总分钟数 |
| `usedMinutes` | `long` | 实际使用的分钟数 |
| `usedFrom` | `LocalDateTime` | 实际使用区间起点 |
| `usedTo` | `LocalDateTime` | 实际使用区间终点 |
| `equivalentAmount` | `BigDecimal` | 等效优惠金额 |

---

## 10. 计费规则

### 10.1 dayNight（日夜分时段计费）

白天/夜间使用不同单价，支持周期封顶和混合单元。

```java
import cn.shang.charging.charge.rules.daynight.DayNightConfig;

DayNightConfig config = new DayNightConfig()
        .setId("daynight-1")
        .setDayBeginMinute(8 * 60)        // 白天开始（分钟数：08:00）
        .setDayEndMinute(20 * 60)         // 白天结束（分钟数：20:00）
        .setDayUnitPrice(new BigDecimal("2"))
        .setNightUnitPrice(new BigDecimal("1"))
        .setMaxChargeOneDay(new BigDecimal("50"))  // 每日封顶
        .setUnitMinutes(60)               // 计费单元 60 分钟
        .setBlockWeight(new BigDecimal("0.5"));  // 跨日夜混合单元权重
```

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `dayBeginMinute` | `int` | 白天开始（从 00:00 起算的分钟数） |
| `dayEndMinute` | `int` | 白天结束 |
| `dayUnitPrice` | `BigDecimal` | 白天单价 |
| `nightUnitPrice` | `BigDecimal` | 夜间单价 |
| `maxChargeOneDay` | `BigDecimal` | 每日封顶金额（周期封顶） |
| `unitMinutes` | `int` | 计费单元时长（分钟） |
| `blockWeight` | `BigDecimal` | 跨日夜混合单元价格权重（仅 BLOCK_WEIGHT 模式） |
| `crossPeriodMode` | `CrossPeriodMode` | 跨时段处理模式（默认 BLOCK_WEIGHT） |
| `splitDayNightBoundary` | `Boolean` | CONTINUOUS 是否在日夜边界切断单元（默认 true 切断；false 跨日夜按 crossPeriodMode 归属） |

### 10.2 relativeTime（相对时间段计费）

按周期内偏移分钟数划分时段，每个时段有独立单价。

```java
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;

RelativeTimeConfig config = new RelativeTimeConfig()
        .setId("relative-1")
        .setMaxChargeOneCycle(new BigDecimal("30"))
        .setPeriods(List.of(
                new RelativeTimePeriod()
                        .setBeginMinute(0)
                        .setEndMinute(720)         // 0:00 - 12:00
                        .setUnitMinutes(60)
                        .setUnitPrice(new BigDecimal("1")),
                new RelativeTimePeriod()
                        .setBeginMinute(720)
                        .setEndMinute(1440)        // 12:00 - 24:00
                        .setUnitMinutes(60)
                        .setUnitPrice(new BigDecimal("2"))
        ));
```

### 10.3 compositeTime（混合时间计费）

最灵活的规则：组合相对时段和自然时段价格，支持时段独立封顶。自然时段边界统一切断，
不再配置 `crossPeriodMode`；不足单元推荐通过 `IncompleteUnitChargeSpec` 控制，旧
`incompleteUnitChargeMode` 字段仍兼容。
时长模式的 `DurationSegment.periodLabel` 同时包含外层相对时段与内部自然时段，例如
`r:0-1440|n:480-1200`。

```java
import cn.shang.charging.charge.rules.compositetime.*;

CompositeTimeConfig config = new CompositeTimeConfig()
        .setId("composite-1")
        .setMaxChargeOneCycle(new BigDecimal("50"))
        .setIncompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.FULL_CHARGE)
        .setPeriods(List.of(
                CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .naturalPeriods(List.of(
                                new NaturalPeriod(8 * 60, 20 * 60, new BigDecimal("2")),  // 白天
                                new NaturalPeriod(20 * 60, 24 * 60, new BigDecimal("1"))  // 夜间
                        ))
                        .build()
        ));
```

### 10.4 flatFree（统一免费计费）

全部免费，常用于调试或特殊场景。

```java
import cn.shang.charging.charge.rules.flatfree.FlatFreeConfig;

FlatFreeConfig config = FlatFreeConfig.builder()
        .id("flat-free-1")
        .build();
```

---

## 11. 优惠系统

### 11.1 优惠类型

| 类型 | 枚举值 | 说明 |
|------|--------|------|
| 免费时间段 | `FREE_RANGE` | 指定时间段内免费（如 12:00-14:00 免费） |
| 免费分钟数 | `FREE_MINUTES` | 从窗口起点顺序分配 N 分钟免费 |
| 智能免费分钟 | `SMART_FREE_MINUTES` | 仅 DURATION_GLOBAL 模式，按单价降序优先覆盖高价时段 |

> 金额减免（AMOUNT）/折扣（DISCOUNT）已移出引擎，由业务系统在最终金额上自行结算。

### 11.2 外部免费分钟数

```java
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.promotion.pojo.PromotionActivationMode;

PromotionGrant freeMinutes = PromotionGrant.builder()
        .id("coupon-30min")
        .type(BConstants.PromotionType.FREE_MINUTES)
        .source(BConstants.PromotionSource.COUPON)
        .freeMinutes(30)
        .priority(1)
        .activationMode(PromotionActivationMode.ALWAYS)
        .build();

request.setExternalPromotions(List.of(freeMinutes));
```

### 11.3 外部免费时段

```java
PromotionGrant freeRange = PromotionGrant.builder()
        .id("free-lunch")
        .type(BConstants.PromotionType.FREE_RANGE)
        .source(BConstants.PromotionSource.COUPON)
        .beginTime(LocalDateTime.of(2026, 5, 8, 12, 0))
        .endTime(LocalDateTime.of(2026, 5, 8, 14, 0))
        .priority(1)
        .build();
```

### 11.4 条件生效优惠

`FREE_RANGE`、`FREE_MINUTES`、`SMART_FREE_MINUTES` 以及方案内 `freeMinutes` / `startFree` 规则均支持 `activationMode`：

| 值 | 说明 |
|------|------|
| `ALWAYS` | 默认值，优惠总是生效 |
| `END_WITHIN_RANGE` | 仅当整笔计费结束时间落在该优惠时间范围内时生效 |

`END_WITHIN_RANGE` 只支持 `DURATION_PERIOD` / `DURATION_GLOBAL` 时长计费模式；`CONTINUOUS` / `UNIT_BASED` 遇到该模式会抛出 `IllegalStateException`。条件优惠先参与免费段合并或免费分钟分配，再按结束时间过滤最终计费段和 `PromotionUsage`，因此失效优惠不会重新触发其他优惠重排。

```java
PromotionGrant conditionalRange = PromotionGrant.builder()
        .id("conditional-night")
        .type(BConstants.PromotionType.FREE_RANGE)
        .beginTime(LocalDateTime.of(2026, 5, 8, 22, 0))
        .endTime(LocalDateTime.of(2026, 5, 9, 1, 0))
        .activationMode(PromotionActivationMode.END_WITHIN_RANGE)
        .priority(1)
        .build();
```

### 11.5 气泡型免费时段

```java
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;

PromotionGrant bubbleRange = PromotionGrant.builder()
        .id("bubble-free")
        .type(BConstants.PromotionType.FREE_RANGE)
        .rangeType(FreeTimeRangeType.BUBBLE)  // 延长周期边界
        .beginTime(beginTime)
        .endTime(endTime)
        .priority(1)
        .build();
```

`FreeTimeRangeType` 枚举：
- `NORMAL`：普通免费时段，不影响周期边界
- `BUBBLE`：气泡型，延长周期边界，后续时间段边界整体后移

### 11.6 智能免费分钟数

```java
PromotionGrant smartFreeMinutes = PromotionGrant.builder()
        .id("smart-30min")
        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
        .source(BConstants.PromotionSource.COUPON)
        .freeMinutes(30)
        .priority(1)
        .build();
```

**限制**：`SMART_FREE_MINUTES` 仅在 `DURATION_GLOBAL` 模式下消费。其他模式遇到此类型会抛出 `IllegalStateException`。

### 11.7 优惠叠加规则

- `FREE_RANGE` 和 `FREE_MINUTES` 可同时存在，由引擎合并处理
- `FREE_MINUTES` 与 `SMART_FREE_MINUTES` 共用 `freeMinutes` 字段，按 `priority` 排序各自分配
- 条件生效优惠失效时不会重新分配其他 `FREE_MINUTES` / `SMART_FREE_MINUTES` 的占用空间，调用方可通过 `priority` 控制优惠占用顺序

---

## 12. 方案切换（多段计费）

当计费方案会随时间变化时，使用 `schemeChanges`：

```java
import cn.shang.charging.billing.pojo.SchemeChange;

List<SchemeChange> changes = List.of(
        new SchemeChange()
                .setLastSchemeId("scheme-a")
                .setNextSchemeId("scheme-b")
                .setChangeTime(LocalDateTime.of(2026, 5, 8, 12, 0))
);

BillingRequest request = new BillingRequest();
request.setBeginTime(LocalDateTime.of(2026, 5, 8, 9, 0));
request.setEndTime(LocalDateTime.of(2026, 5, 8, 18, 0));
request.setSchemeChanges(changes);
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
```

引擎自动按 `changeTime` 切割为多段，各段使用对应方案的规则。

### SegmentCalculationMode

| 值 | 说明 |
|------|------|
| `SINGLE` | 仅单个分段（无方案切换时使用） |
| `SEGMENT_LOCAL` | 分段独立起算（各段从自身起点开始计算） |

---

## 13. 时间取整

引擎按**分钟精度**计费，所有时间通过 `BillingTemplate` 入口时**统一向下取整**（秒数置0）。这是一条确定性规则，不区分计费时间与优惠时间、不带业务策略：

- `BillingRequest.beginTime` / `endTime` / `calcEndTime` → 向下取整
- `externalPromotions` 中 `FREE_RANGE` 的 `beginTime` / `endTime` → 向下取整

向下取整不会产生 `beginTime > endTime` 的倒置（最多相等 → 计费 0），无需守卫。取整后所有时间对齐到分钟，`Duration.toMinutes()` 不损失精度，不产生 0 分钟段。

### 13.1 业务策略由调用方预处理

引擎不再提供向上取整模式（原 `TimeRoundingMode` 枚举保留向后兼容，但引擎忽略，统一向下）。调用方若有「进场多算」（beginTime 向上）、「优惠尽量长」（endTime 向上）等业务需求，应在构造 `BillingRequest` 前通过 `TimeRounding` 工具自行预处理：

```java
import cn.shang.charging.wrapper.TimeRounding;

// 外部预处理：beginTime 向上取整（"进场多算"）
LocalDateTime rawBegin = LocalDateTime.of(2026, 7, 7, 9, 0, 30);
request.setBeginTime(TimeRounding.ceil(rawBegin));   // → 09:01:00

// endTime 保持向下（引擎默认行为）
request.setEndTime(endTime);                          // 引擎内部 truncate

BillingResult result = billingTemplate.calculate(request);
// 引擎对已对齐到分钟的时间向下取整是 no-op，外部预处理意图保留
```

`TimeRounding` 工具方法：

| 方法 | 说明 |
|------|------|
| `TimeRounding.truncate(time)` | 向下取整（秒数置0）。引擎内部统一使用 |
| `TimeRounding.ceil(time)` | 向上取整（秒数>0 则 +1 分钟）。仅供外部预处理 |

### 13.2 `TimeRoundingMode` 字段说明

`BillingRequest.timeRoundingMode` 和 `BillingTemplate.calculate(request, mode)` 保留向后兼容，但**引擎忽略 `mode`**，始终统一向下取整。现有调用方无需移除该字段，但建议逐步迁移到外部预处理方式。

### 13.3 直接使用 `BillingService`

`BillingService` 不做取整，直接使用时应自行保证时间对齐到分钟（或接受 `toMinutes()` 截断秒数的精度损失）。推荐通过 `BillingTemplate` 入口，由引擎统一向下取整。

> 注：引擎内部计算（边界驱动循环、免费分钟分配等）均按分钟精度。统一向下取整后秒数为0，`toMinutes()` 精确，无 0 分钟段与精度损失问题。

---

## 14. 优惠等效金额

### 14.1 独立计算

```java
Map<String, BigDecimal> equivalents = billingTemplate.calculatePromotionEquivalents(request);
// 返回：优惠ID → 等效金额
```

### 14.2 按需计算 + 自动回填

设置 `BillingRequest.equivalentAmountSpec` 后，引擎会在结算后自动用消去法计算并回填：

```java
import cn.shang.charging.billing.pojo.EquivalentAmountSpec;
import java.util.Set;

request.setEquivalentAmountSpec(EquivalentAmountSpec.builder()
        .promotionIds(Set.of("coupon-1"))          // null = 不限 id
        .types(Set.of(BConstants.PromotionType.FREE_RANGE))  // null = 不限类型
        .build());

BillingResult result = billingTemplate.calculate(request);

// 结果中：
// result.getTotalEquivalentAmount() → 命中优惠的等效金额之和
// result.getPromotionUsages().get(i).getEquivalentAmount() → 被回填为精确值
```

### 14.3 EquivalentAmountSpec

| 字段 | 类型 | 说明 |
|------|------|------|
| `promotionIds` | `Set<String>` | 指定优惠 ID（null=不限，全部参与） |
| `types` | `Set<BConstants.PromotionType>` | 指定优惠类型（null=不限） |

两个维度取交集。

- `equivalentAmountSpec == null`（默认）→ 不计算等效金额，`totalEquivalentAmount` 为 `null`
- `equivalentAmountSpec != null` → 按规格过滤，回填到 `PromotionUsage.equivalentAmount` 和 `BillingResult.totalEquivalentAmount`

---

## 15. 计算模式详解

| 模式 | 说明 | 产出类型 |
|------|------|----------|
| `CONTINUOUS` | 连续时间计费（边界驱动循环，段内直接产出 compact） | `BillingUnit`（含 compact） |
| `UNIT_BASED` | 固定单元对齐计费 | `BillingUnit` |
| `DURATION_PERIOD` | 周期内时长计费，周期封顶+时段封顶 | `DurationSegment` |
| `DURATION_GLOBAL` | 全局时长计费；按同质收费桶汇总，并对完整周期与尾周期分别应用封顶 | `DurationSegment` |

### 规则族支持矩阵

| 规则族 | CONTINUOUS | UNIT_BASED | DURATION_PERIOD | DURATION_GLOBAL |
|--------|:----------:|:----------:|:---------------:|:---------------:|
| `dayNight` | ✅ | ✅ | ✅ | ✅ |
| `relativeTime` | ✅ | ❌ | ✅ | ✅ |
| `naturalTime` | ✅ | ❌ | ✅ | ✅ |
| `compositeTime` | ✅ | ❌ | ✅ | ✅ |
| `flatFree` | ✅ | ✅ | ❌ | ❌ |

### 不足单元 / 余数处理（IncompleteUnitChargeMode）

当计费时间不是 `unitMinutes` 的整数倍时，不足一个 `unitMinutes` 的部分如何收费，由 `RuleConfig` 公共默认方法描述（默认 `FULL_CHARGE`）。推荐通过 `IncompleteUnitChargeSpec` 统一配置 `mode`、`thresholdMinutes`、`thresholdRatio`；旧的 `incompleteUnitChargeMode`、`thresholdMinutes`、`thresholdRatio` 散字段仍兼容，内置规则优先读取 `IncompleteUnitChargeSpec`。

| 模式 | CONTINUOUS / UNIT_BASED（截断单元） | DURATION_PERIOD / DURATION_GLOBAL（余数部分） |
|------|-------------------------------------|----------------------------------------------|
| `FULL_CHARGE`（默认） | 不足单元收一个完整 `unitPrice` | 余数收一个完整 `unitPrice`（"不满一小时按一小时算"） |
| `PROPORTIONAL` | `unitPrice × segMinutes / unitMinutes` | 余数按比例 `unitPrice × remainder / unitMinutes` |
| `FREE` | 不足单元免费 | 余数免费（整除部分仍收） |
| `THRESHOLD_MINUTES` | 不足单元超阈值收全额，否则免费 | 余数超阈值收全额，否则免费 |
| `THRESHOLD_RATIO` | 不足单元超阈值比例收全额，否则按比例 | 余数超阈值比例收全额，否则按比例 |

- **CONTINUOUS / UNIT_BASED**：作用于「截断单元」（`isTruncated=true` 的末段，`segMinutes < unitMinutes`）
- **DURATION_PERIOD / DURATION_GLOBAL**：作用于时长计费中「不足 `unitMinutes` 的余数部分」。`DURATION_PERIOD` 按同质段计算；`DURATION_GLOBAL` 先按同质收费桶汇总分钟，再对汇总桶计算余数。整除部分（`fullUnits × unitPrice`）始终照收，余数按上表模式处理。`PROPORTIONAL` 与原按比例行为一致；`FULL_CHARGE` 实现"不满一小时按一小时算"

推荐配置示例（时长模式按比例收费）：

```java
new DayNightConfig()
        .setUnitMinutes(60)
        .setIncompleteUnitChargeSpec(IncompleteUnitChargeSpec.builder()
                .mode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .build())
        ...
```

兼容配置示例（旧散字段仍可用）：

```java
new DayNightConfig()
        .setUnitMinutes(60)
        .setIncompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.THRESHOLD_MINUTES)
        .setThresholdMinutes(30)
        ...
```

`DurationSegment.chargedMinutes` 始终记实际分钟数，`chargedAmount` 反映取整后金额。

---

## 16. 自定义计费规则

自定义计费规则当前推荐先走**路径 A**：直接实现 `BillingRule`，按需复用轻量公共原语。
完整示例见 [自定义计费规则开发指南](guides/custom-rule-guide.md)。

路径 A 适合规则作者先把业务规则接入引擎，而不引入新的公共 API 或重型继承基类。
如果规则后续需要完整支持 `DURATION_PERIOD` / `DURATION_GLOBAL`，再评估基于
`RuleSemantics` 的语义驱动路径。

### 16.1 最小结构

```java
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class MyRuleConfig implements RuleConfig {
    private String id;
    private BigDecimal unitPrice;

    @Override
    public String getType() {
        return "myRule";  // 自定义规则类型标识
    }
}
```

### 16.2 直接实现 BillingRule

```java
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import java.util.Set;

public class MyBillingRule implements BillingRule<MyRuleConfig> {

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          MyRuleConfig config,
                                          PromotionAggregate promotionAggregate) {
        // 规则必须是纯计算：只根据输入生成结果，不访问数据库或外部服务
        // ...
    }

    @Override
    public Class<MyRuleConfig> configClass() {
        return MyRuleConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return Set.of(BConstants.CalculationMode.CONTINUOUS);
    }
}
```

规则内部可以复用 `BoundaryDrivenLoop`、`BoundaryProvider`、`BoundaryProviders` 和
`HomogeneousSegment` 来切分时间轴。示例指南中的 `peakOffPeak` 规则展示了如何按费率边界、
单元边界、免费段边界和 `calcEnd` 统一切分，再生成 `BillingUnit`。
`progressiveDailyCap` 规则展示了另一类更高定制度的写法：不使用单元边界，只按自然日边界、
免费段边界和 `calcEnd` 切分，并用增量封顶数组表达非线性累计总封顶，同时分别产出
`CONTINUOUS`、`DURATION_PERIOD`、`DURATION_GLOBAL` 三种模式的结果结构。

### 16.3 注册规则

```java
billingRuleRegistry.register("myRule", new MyBillingRule());
```

Spring Boot starter 当前可通过自定义 `BillingRuleRegistry` bean 注册自定义规则。
本阶段不新增 registry customizer 或自动扫描机制。

### 16.4 规则开发原则

1. **纯计算**：输入 → 输出，不访问数据库、不调用远程接口
2. **无副作用**：不修改全局状态、不修改共享对象
3. **确定性**：同样输入 → 同样输出
4. **规则不相互调用**：引擎统一编排，规则 A 不调用规则 B
5. **配置与实现分离**：`RuleConfig` 只描述参数，`BillingRule` 负责计算

---

## 17. 常用枚举与常量

### BConstants.CalculationMode

| 值 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间计费（边界驱动切断） |
| `UNIT_BASED` | 固定单元对齐计费 |
| `DURATION_PERIOD` | 周期内时长计费 |
| `DURATION_GLOBAL` | 全局时长计费（唯一消费 SMART_FREE_MINUTES 的模式） |

### BConstants.PromotionType

| 值 | 说明 |
|------|------|
| `FREE_RANGE` | 免费时间段 |
| `FREE_MINUTES` | 免费分钟数（在窗口起点附近分配） |
| `SMART_FREE_MINUTES` | 智能免费分钟数（仅 DURATION_GLOBAL，按单价降序优先高价分配） |

### BConstants.PromotionSource

| 值 | 说明 |
|------|------|
| `RULE` | 方案内规则产生的优惠 |
| `COUPON` | 外部优惠券 |

### PromotionActivationMode

| 值 | 说明 |
|------|------|
| `ALWAYS` | 默认值，优惠总是生效 |
| `END_WITHIN_RANGE` | 计费结束时间落在优惠时间范围内才生效，仅支持 `DURATION_PERIOD` / `DURATION_GLOBAL` |

### BConstants.ChargeRuleType

| 常量 | 值 | 状态 |
|------|------|------|
| `DAY_NIGHT` | `"dayNight"` | 已实现 |
| `RELATIVE_TIME` | `"relativeTime"` | 已实现 |
| `NATURAL_TIME` | `"naturalTime"` | 已实现 |
| `COMPOSITE_TIME` | `"compositeTime"` | 已实现 |
| `FLAT_FREE` | `"flatFree"` | 已实现 |
| `TIMES` | `"times"` | 预留，当前无实现 |
| `NR_TIME_MIX` | `"nrTimeMix"` | **已废弃**，由 `compositeTime` 替代 |

### BConstants.PromotionRuleType

| 常量 | 值 |
|------|------|
| `FREE_MINUTES` | `"freeMinutes"` |
| `START_FREE` | `"startFree"` |

### BConstants.IncompleteUnitChargeMode

| 值 | 说明 |
|------|------|
| `FULL_CHARGE` | 不足单元完整收费（默认） |
| `PROPORTIONAL` | 按时长比例收费 |
| `FREE` | 不足单元免费 |
| `THRESHOLD_MINUTES` | 超过阈值分钟数后全额收费，否则免费 |
| `THRESHOLD_RATIO` | 超过阈值比例后全额收费，否则按比例收费 |

### BConstants.SegmentCalculationMode

| 值 | 说明 |
|------|------|
| `SINGLE` | 仅单个分段 |
| `SEGMENT_LOCAL` | 分段独立起算 |

### TimeRoundingMode

| 值 | 说明 |
|------|------|
| `KEEP_SECONDS` | 保留秒数 |
| `TRUNCATE_BOTH` | 起止时间均去秒 |
| `CEIL_BEGIN_TRUNCATE_END` | 开始向上取整，结束去秒（默认） |
| `TRUNCATE_BEGIN_CEIL_END` | 开始去秒，结束向上取整 |

### FreeTimeRangeType

| 值 | 说明 |
|------|------|
| `NORMAL` | 普通免费时段 |
| `BUBBLE` | 气泡型（延长周期边界） |

---

## 18. 设计原则与禁止事项

### 核心引擎原则

1. **核心引擎只负责计算** — 不处理缓存、数据库、持久化等副作用
2. **规则必须是纯计算** — 输入→输出，确定性，无副作用
3. **时间计算必须可重复** — 同样输入→同样输出
4. **规则不应相互依赖** — 引擎统一编排，规则间不直接调用
5. **配置与实现分离** — `RuleConfig` 只描述参数，`BillingRule` 负责计算
6. **简单优先** — 简单场景零额外复杂度，高级特性隔离

### 禁止事项

| 禁止行为 | 错误示例 |
|---------|---------|
| 在规则中访问数据库 | `rule.calculate()` 内部查询数据库 |
| 规则修改全局状态 | 修改全局变量、修改共享对象 |
| 规则改变计费流程 | 改变引擎执行顺序 |
| 规则之间相互调用 | `RuleA.calculate()` → `RuleB.calculate()` |
