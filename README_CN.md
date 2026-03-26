# 时间计费引擎

[English](README.md)

一个可扩展、可追溯、可组合规则的时间计费引擎，适用于场地使用费、设备租赁、服务时长计费等按时间收费的场景。

## 特性

- **可扩展规则**：新增计费规则无需修改核心引擎
- **可追溯过程**：完整的计费明细，便于审计和调试
- **继续计算**：支持从上次结果增量计算
- **灵活优惠**：免费时间段、免费分钟数等多种优惠类型

## 环境要求

- JDK 21+
- Maven 3.6+

## 快速开始

### 添加依赖

#### 方式一：billing-api（推荐）

提供 `BillingTemplate` 便捷封装，包含查询时间点计算、优惠等效金额计算等高级功能。

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>1.0.2</version>
</dependency>
```

#### 方式二：Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>1.0.2</version>
</dependency>
```

### 基本用法

```java
// 1. 实现 BillingConfigResolver
public class MyBillingConfigResolver implements BillingConfigResolver {
    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd) {
        return new DayNightConfig()
            .setId("daynight-1")
            .setDayBeginMinute(740)              // 白天开始：12:20
            .setDayEndMinute(1140)               // 白天结束：19:00
            .setDayUnitPrice(new BigDecimal("2")) // 白天单价：2元/小时
            .setNightUnitPrice(new BigDecimal("1")) // 夜间单价：1元/小时
            .setMaxChargeOneDay(new BigDecimal("50")) // 每日封顶：50元
            .setUnitMinutes(60)
            .setBlockWeight(new BigDecimal("0.5"));
    }
    // ... 其他方法
}

// 2. 创建 BillingTemplate
BillingConfigResolver configResolver = new MyBillingConfigResolver();
BillingService billingService = BillingServiceFactory.create(configResolver);
BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);

// 3. 计算费用
BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

## API 参考

### 核心服务类

#### BillingService（cn.shang.charging.billing）

核心计费服务，执行完整的计费计算流程。

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `calculate` | `BillingRequest request` | `BillingResult` | 执行计费计算 |
| `prepareContexts` | `BillingRequest request` | `List<SegmentContext>` | 准备分段上下文（仅 FROM_SCRATCH 模式） |
| `calculateWithContexts` | `List<SegmentContext> contexts, BillingRequest request` | `BillingResult` | 用分段上下文计算 |

#### BillingTemplate（cn.shang.charging.wrapper）

便捷 API 封装，提供高级功能。

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `calculate` | `BillingRequest request` | `BillingResult` | 基础计费计算 |
| `calculateWithQuery` | `BillingRequest request, LocalDateTime queryTime` | `CalculationWithQueryResult` | 计算并返回指定时间点的费用状态 |
| `calculatePromotionEquivalents` | `BillingRequest request` | `Map<String, BigDecimal>` | 计算每个优惠的等效金额 |
| `calculatePromotionSavings` | `BillingResult result` | `Map<String, BigDecimal>` | 分析优惠节省金额 |
| `getConfigResolver` | - | `BillingConfigResolver` | 获取配置解析器 |

#### BillingConfigResolver（cn.shang.charging.billing）

计费配置解析器接口，用户必须实现。

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `resolveBillingMode` | `String schemeId` | `BConstants.BillingMode` | 获取计费模式 |
| `resolveChargingRule` | `String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd` | `RuleConfig` | 获取计费规则配置 |
| `resolvePromotionRules` | `String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd` | `List<PromotionRuleConfig>` | 获取优惠规则配置 |
| `getSimplifiedCycleThreshold` | - | `int` | 简化计算周期阈值，默认 0 禁用 |

---

### 请求与结果类

#### BillingRequest（cn.shang.charging.billing.pojo）

计费请求输入。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | 否 | 请求唯一标识 |
| `beginTime` | LocalDateTime | **是** | 计费开始时间 |
| `endTime` | LocalDateTime | **是** | 计费结束时间 |
| `queryTime` | LocalDateTime | 否 | 查询时间点（用于截取结果） |
| `calcEndTime` | LocalDateTime | 否 | 计算结束时间（用于控制计算进度） |
| `schemeId` | String | 条件 | 计费方案ID（与 `schemeChanges` 二选一） |
| `schemeChanges` | List\<SchemeChange\> | 条件 | 方案变更时间轴 |
| `segmentCalculationMode` | SegmentCalculationMode | **是** | 分段计算模式 |
| `externalPromotions` | List\<PromotionGrant\> | 否 | 外部优惠列表 |
| `previousCarryOver` | BillingCarryOver | 否 | 上次结转状态（CONTINUE 模式） |

#### BillingResult（cn.shang.charging.billing.pojo）

计费结果输出。

| 字段 | 类型 | 说明 |
|------|------|------|
| `units` | List\<BillingUnit\> | 计费单元明细 |
| `promotionUsages` | List\<PromotionUsage\> | 优惠使用记录 |
| `settlementAdjustments` | List\<SettlementAdjustment\> | 结算调整记录 |
| `finalAmount` | BigDecimal | 最终应收金额 |
| `effectiveFrom` | LocalDateTime | 价格有效起始时间 |
| `effectiveTo` | LocalDateTime | 价格有效结束时间 |
| `calculationEndTime` | LocalDateTime | 实际计算到的时间点（延伸后） |
| `carryOver` | BillingCarryOver | 结转状态，供下次计算使用 |

#### BillingUnit（cn.shang.charging.billing.pojo）

计费单元明细。

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginTime` | LocalDateTime | 单元开始时间 |
| `endTime` | LocalDateTime | 单元结束时间 |
| `durationMinutes` | int | 单元时长（分钟） |
| `unitPrice` | BigDecimal | 单元单价 |
| `originalAmount` | BigDecimal | 原始金额（应用优惠前） |
| `chargedAmount` | BigDecimal | 实际金额（应用优惠后） |
| `free` | boolean | 是否免费（被优惠完全覆盖） |
| `freePromotionId` | String | 免费原因（优惠ID） |
| `isTruncated` | Boolean | 是否被 calcEndTime 截断 |
| `ruleData` | Object | 规则扩展数据 |

#### BillingSegmentResult（cn.shang.charging.billing.pojo）

分段计费结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `segmentId` | String | 分段ID |
| `segmentStartTime` | LocalDateTime | 分段逻辑起始时间 |
| `segmentEndTime` | LocalDateTime | 分段逻辑结束时间 |
| `calculationStartTime` | LocalDateTime | 实际计算起始时间 |
| `calculationEndTime` | LocalDateTime | 实际计算结束时间 |
| `chargedAmount` | BigDecimal | 本分段应收金额 |
| `chargedDuration` | Integer | 本分段实际计费时长（分钟） |
| `feeEffectiveStart` | LocalDateTime | 费用确定起始时间 |
| `feeEffectiveEnd` | LocalDateTime | 费用稳定结束时间 |
| `promotionAggregate` | PromotionAggregate | 优惠聚合结果 |
| `billingUnits` | List\<BillingUnit\> | 计费单元明细 |
| `carryOverAfter` | BillingCarryOver | 结转状态 |
| `ruleOutputState` | Map\<String, Object\> | 规则输出状态 |
| `promotionUsages` | List\<PromotionUsage\> | 优惠使用记录 |

---

### 结转状态类

#### BillingCarryOver（cn.shang.charging.billing.pojo）

顶层结转对象，用于 CONTINUE 模式。

| 字段 | 类型 | 说明 |
|------|------|------|
| `calculatedUpTo` | LocalDateTime | 已计算到的时间点 |
| `segments` | Map\<String, SegmentCarryOver\> | 按分段ID存储的结转状态 |

#### SegmentCarryOver（cn.shang.charging.billing.pojo）

分段级结转状态。

| 字段 | 类型 | 说明 |
|------|------|------|
| `ruleState` | Map\<String, Object\> | 规则状态（key: 规则类型） |
| `promotionState` | PromotionCarryOver | 优惠结转状态 |

#### PromotionCarryOver（cn.shang.charging.billing.pojo）

优惠结转状态。

| 字段 | 类型 | 说明 |
|------|------|------|
| `remainingMinutes` | Map\<String, Integer\> | 剩余免费分钟数（key: promotionId） |
| `usedFreeRanges` | List\<FreeTimeRange\> | 已使用的免费时段 |

---

### 优惠相关类

#### PromotionGrant（cn.shang.charging.promotion.pojo）

可计算的优惠输入。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 优惠ID |
| `type` | PromotionType | 优惠类型 |
| `source` | PromotionSource | 优惠来源 |
| `beginTime` | LocalDateTime | 时间段开始（FREE_RANGE 类型） |
| `endTime` | LocalDateTime | 时间段结束（FREE_RANGE 类型） |
| `freeMinutes` | Integer | 免费分钟数（FREE_MINUTES 类型） |
| `priority` | Integer | 优先级 |
| `rangeType` | FreeTimeRangeType | 免费时间段类型 |

#### FreeTimeRange（cn.shang.charging.promotion.pojo）

免费时间段。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 唯一标识符 |
| `beginTime` | LocalDateTime | 开始时间 |
| `endTime` | LocalDateTime | 结束时间 |
| `priority` | int | 优先级 |
| `promotionType` | PromotionType | 优惠类型 |
| `rangeType` | FreeTimeRangeType | 时间段类型（NORMAL/BUBBLE） |
| `data` | Object | 扩展数据 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `isValid()` | boolean | 检查时间段是否有效 |
| `overlaps(FreeTimeRange other)` | boolean | 检查是否有重合 |
| `getOverlap(FreeTimeRange other)` | FreeTimeRange | 获取重合部分 |
| `copy()` | FreeTimeRange | 复制 |
| `copyWithNewId()` | FreeTimeRange | 复制并生成新ID |

#### PromotionUsage（cn.shang.charging.promotion.pojo）

优惠使用记录。

| 字段 | 类型 | 说明 |
|------|------|------|
| `promotionId` | String | 优惠来源ID |
| `type` | PromotionType | 优惠类型 |
| `grantedMinutes` | long | 授权分钟数 |
| `usedMinutes` | long | 已使用分钟数 |
| `usedFrom` | LocalDateTime | 使用起始时间 |
| `usedTo` | LocalDateTime | 使用结束时间 |
| `equivalentAmount` | BigDecimal | 等效优惠金额 |

#### PromotionAggregate（cn.shang.charging.promotion.pojo）

优惠聚合结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `freeTimeRanges` | List\<FreeTimeRange\> | 免费时间段列表 |
| `freeMinutes` | long | 总免费分钟数 |
| `usages` | List\<PromotionUsage\> | 使用统计 |
| `equivalentAmount` | BigDecimal | 等效金额 |
| `promotionCarryOver` | PromotionCarryOver | 优惠结转输出 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `isEmpty()` | boolean | 是否无优惠 |
| `hasMultiplePromotionTypes()` | boolean | 是否有多种优惠类型 |
| `hasSinglePromotionType()` | boolean | 是否为单一优惠类型 |

#### TimeRangeMergeResult（cn.shang.charging.promotion.pojo）

时间段合并结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `mergedRanges` | List\<FreeTimeRange\> | 合并后的时间段 |
| `discardedRanges` | List\<FreeTimeRange\> | 被舍弃的时间段 |
| `originalToDiscarded` | Map\<String, List\<FreeTimeRange\>\> | 原始与舍弃映射 |

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `addMergedRange` | `FreeTimeRange range` | void | 添加合并时间段 |
| `addDiscardedRange` | `FreeTimeRange range` | void | 添加舍弃时间段 |
| `addDiscardedRanges` | `List<FreeTimeRange> ranges` | void | 批量添加舍弃时间段 |
| `getDiscardedParts` | `String originalId` | List\<FreeTimeRange\> | 获取被舍弃的部分 |
| `getRemainingParts` | `String originalId` | List\<FreeTimeRange\> | 获取剩余的部分 |

---

### 配置接口

#### RuleConfig（cn.shang.charging.billing.pojo）

计费规则配置接口。

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getId()` | String | 获取规则ID |
| `getType()` | String | 获取规则类型 |
| `getSimplifiedSupported()` | Boolean | 是否支持简化计算（默认 null 表示支持） |

#### PromotionRuleConfig（cn.shang.charging.billing.pojo）

优惠规则配置接口。

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getId()` | String | 获取规则ID |
| `getType()` | String | 获取规则类型 |
| `getPriority()` | Integer | 获取优先级 |

---

### 规则配置实现类

#### DayNightConfig（cn.shang.charging.charge.rules.daynight）

日夜分时段计费规则配置。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 规则ID |
| `type` | String | 规则类型（默认 "dayNight"） |
| `dayBeginMinute` | Integer | 白天开始分钟（0点为0） |
| `dayEndMinute` | Integer | 白天结束分钟 |
| `unitMinutes` | Integer | 计费单元长度（分钟） |
| `blockWeight` | BigDecimal | 白天黑夜判断权重 |
| `dayUnitPrice` | BigDecimal | 白天单价 |
| `nightUnitPrice` | BigDecimal | 夜间单价 |
| `maxChargeOneDay` | BigDecimal | 每日封顶金额 |
| `simplifiedSupported` | Boolean | 是否支持简化计算 |

#### RelativeTimeConfig（cn.shang.charging.charge.rules.relativetime）

相对时间段计费规则配置。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 规则ID |
| `type` | String | 规则类型（默认 "relativeTime"） |
| `periods` | List\<RelativeTimePeriod\> | 时间段列表 |
| `maxChargeOneCycle` | BigDecimal | 每周期封顶金额 |
| `simplifiedSupported` | Boolean | 是否支持简化计算 |

#### RelativeTimePeriod（cn.shang.charging.charge.rules.relativetime）

相对时间段定义。

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginMinute` | int | 相对开始分钟（0-1439） |
| `endMinute` | int | 相对结束分钟（1-1440） |
| `unitMinutes` | int | 计费单元长度（分钟） |
| `unitPrice` | BigDecimal | 单价 |

#### CompositeTimeConfig（cn.shang.charging.charge.rules.compositetime）

混合时间计费规则配置。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 规则ID |
| `type` | String | 规则类型（默认 "compositeTime"） |
| `periods` | List\<CompositePeriod\> | 相对时间段列表 |
| `maxChargeOneCycle` | BigDecimal | 周期封顶金额 |
| `insufficientUnitMode` | InsufficientUnitMode | 不足单元计费模式 |
| `simplifiedSupported` | Boolean | 是否支持简化计算 |

#### CompositePeriod（cn.shang.charging.charge.rules.compositetime）

相对时间段配置（混合时间规则）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginMinute` | int | 相对开始分钟（0-1440） |
| `endMinute` | int | 相对结束分钟 |
| `unitMinutes` | int | 计费单元长度（分钟） |
| `maxCharge` | BigDecimal | 时间段独立封顶（可选） |
| `crossPeriodMode` | CrossPeriodMode | 跨自然时段处理模式 |
| `naturalPeriods` | List\<NaturalPeriod\> | 自然时段价格列表 |

#### NaturalPeriod（cn.shang.charging.charge.rules.compositetime）

自然时段配置。

| 字段 | 类型 | 说明 |
|------|------|------|
| `beginMinute` | int | 自然时段开始分钟 |
| `endMinute` | int | 自然时段结束分钟 |
| `unitPrice` | BigDecimal | 单元价格 |

#### FreeMinutesPromotionConfig（cn.shang.charging.promotion.rules.minutes）

免费分钟数优惠规则配置。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 规则ID |
| `type` | String | 规则类型（默认 "freeMinutes"） |
| `priority` | Integer | 优先级 |
| `minutes` | int | 免费分钟数 |

#### InsufficientUnitMode（cn.shang.charging.charge.rules.compositetime）

不足单元计费模式枚举。

| 值 | 说明 |
|------|------|
| `FULL` | 全额收费 |
| `PROPORTIONAL` | 按比例收费 |

#### CrossPeriodMode（cn.shang.charging.charge.rules.compositetime）

跨自然时段处理模式枚举。

| 值 | 说明 |
|------|------|
| `BLOCK_WEIGHT` | 按时间比例判断价格 |
| `HIGHER_PRICE` | 取较高价格 |
| `LOWER_PRICE` | 取较低价格 |
| `PROPORTIONAL` | 按比例拆分计算 |
| `BEGIN_TIME_PRICE` | 取开始时间所在时段价格 |
| `END_TIME_PRICE` | 取结束时间所在时段价格 |
| `BEGIN_TIME_TRUNCATE` | 取开始时间价格，用自然时段边界截断 |

---

### 常量与枚举

#### BConstants（cn.shang.charging.billing.pojo）

##### ContinueMode 枚举

| 值 | 说明 |
|------|------|
| `FROM_SCRATCH` | 从开始时间计算 |
| `CONTINUE` | 从上一次的结果继续计算 |

##### BillingMode 枚举

| 值 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间计费模式 |
| `UNIT_BASED` | 计费单位模式 |

##### PromotionType 枚举

| 值 | 说明 |
|------|------|
| `AMOUNT` | 金额减免（待实现） |
| `DISCOUNT` | 折扣优惠（待实现） |
| `FREE_RANGE` | 免费时间段 |
| `FREE_MINUTES` | 免费分钟数 |

##### PromotionSource 枚举

| 值 | 说明 |
|------|------|
| `RULE` | 规则优惠 |
| `COUPON` | 优惠券 |

##### SegmentCalculationMode 枚举

| 值 | 说明 |
|------|------|
| `SINGLE` | 仅单个分段 |
| `SEGMENT_LOCAL` | 分段独立起算 |
| `GLOBAL_ORIGIN` | 全局起算 + 分段截取 |

##### ChargeRuleType 常量

| 常量 | 值 | 说明 |
|------|------|------|
| `DAY_NIGHT` | "dayNight" | 日夜分时段计费 |
| `TIMES` | "times" | 按次数 |
| `NATURAL_TIME` | "naturalTime" | 按自然时间段计费 |
| `RELATIVE_TIME` | "relativeTime" | 按相对时间段计费 |
| `NR_TIME_MIX` | "nrTimeMix" | 自然时间、相对时间混合 |
| `COMPOSITE_TIME` | "compositeTime" | 混合时间计费 |

##### PromotionRuleType 常量

| 常量 | 值 | 说明 |
|------|------|------|
| `FREE_MINUTES` | "freeMinutes" | 免费分钟数规则 |

#### FreeTimeRangeType（cn.shang.charging.promotion.pojo）

| 值 | 说明 |
|------|------|
| `NORMAL` | 普通免费时间段，不影响周期边界 |
| `BUBBLE` | 气泡型免费时间段，延长计费周期边界 |

---

### 其他类

#### SchemeChange（cn.shang.charging.billing.pojo）

方案切换记录。

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastSchemeId` | String | 上一个方案ID |
| `nextSchemeId` | String | 下一个方案ID |
| `changeTime` | LocalDateTime | 变更时间 |

#### CalculationWithQueryResult（cn.shang.charging.wrapper）

计算结果与查询结果。

| 字段 | 类型 | 说明 |
|------|------|------|
| `calculationResult` | BillingResult | 完整计算结果（用于 CONTINUE 进度存储） |
| `queryResult` | BillingResult | 查询时间点的结果（用于展示） |

## 计费模式

| 模式 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间计费，时间可被优惠打断 |
| `UNIT_BASED` | 按计费单元独立计算 |

## 优惠类型

| 类型 | 说明 |
|------|------|
| `FREE_MINUTES` | 免费分钟数，智能分配到最优时段 |
| `FREE_RANGE` | 免费时间段 |
| `AMOUNT` | 金额减免（待实现） |
| `DISCOUNT` | 折扣优惠（待实现） |

## 继续计算模式

支持长期计费场景的增量计算：

```java
// 首次计算
BillingResult result1 = billingTemplate.calculate(request1);

// 继续计算
BillingRequest request2 = new BillingRequest();
request2.setBeginTime(beginTime);
request2.setEndTime(secondQueryTime);
request2.setPreviousCarryOver(result1.getCarryOver());

BillingResult result2 = billingTemplate.calculate(request2);
```

## 规则配置

### DayNightConfig（日夜分时段）

```java
DayNightConfig config = new DayNightConfig()
    .setId("daynight-1")
    .setDayBeginMinute(740)                  // 12:20
    .setDayEndMinute(1140)                   // 19:00
    .setDayUnitPrice(new BigDecimal("2"))
    .setNightUnitPrice(new BigDecimal("1"))
    .setMaxChargeOneDay(new BigDecimal("50"))
    .setUnitMinutes(60)
    .setBlockWeight(new BigDecimal("0.5"));
```

### RelativeTimeConfig（相对时间段）

```java
RelativeTimeConfig config = new RelativeTimeConfig()
    .setId("relative-1")
    .setUnitMinutes(60)
    .setMaxChargeOneDay(new BigDecimal("30"))
    .setPeriods(List.of(
        new RelativeTimePeriod()
            .setStartMinute(0)
            .setEndMinute(720)
            .setUnitPrice(new BigDecimal("1")),
        new RelativeTimePeriod()
            .setStartMinute(720)
            .setEndMinute(1440)
            .setUnitPrice(new BigDecimal("2"))
    ));
```

### CompositeTimeConfig（混合时间）

```java
CompositeTimeConfig config = new CompositeTimeConfig()
    .setId("composite-1")
    .setBillingMode(BConstants.BillingMode.CONTINUOUS)
    .setPeriods(List.of(
        new CompositePeriod()
            .setId("daytime")
            .setType("natural")
            .setNaturalPeriod(NaturalPeriod.DAY)
            .setUnitMinutes(60)
            .setUnitPrice(new BigDecimal("2")),
        new CompositePeriod()
            .setId("nighttime")
            .setType("natural")
            .setNaturalPeriod(NaturalPeriod.NIGHT)
            .setUnitMinutes(60)
            .setUnitPrice(new BigDecimal("1"))
    ));
```

### FreeMinutesPromotionConfig（免费分钟数优惠）

```java
FreeMinutesPromotionConfig promoConfig = new FreeMinutesPromotionConfig()
    .setId("free-minutes-1")
    .setMinutes(30)        // 免费时长30分钟
    .setPriority(100);     // 优先级越高越先应用
```

## 自定义规则

### 1. 实现规则配置

```java
@Data
public class MyRuleConfig implements RuleConfig {
    private String id;
    private BigDecimal unitPrice;
    private int unitMinutes;

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "myRule"; }
}
```

### 2. 实现规则逻辑

```java
public class MyBillingRule implements BillingRule<MyRuleConfig> {
    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          MyRuleConfig config,
                                          PromotionAggregate promotionAggregate) {
        // 实现计费逻辑
    }

    @Override
    public Class<MyRuleConfig> configClass() {
        return MyRuleConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.CONTINUOUS);
    }
}
```

### 3. 注册规则

```java
ruleRegistry.register("myRule", new MyBillingRule());
```

## 模块结构

| 模块 | 说明 |
|------|------|
| `billing-core` | 核心计费引擎，纯计算逻辑 |
| `billing-api` | 便捷 API 封装（推荐使用） |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0-3.4 集成 |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5-4.x 集成 |

## 设计原则

1. **核心引擎只负责计算** — 无缓存、无数据库、无副作用
2. **规则是纯函数** — 相同输入始终产生相同输出
3. **规则不相互依赖** — 所有规则通过 Engine 统一执行
4. **配置与实现分离** — RuleConfig 描述参数，BillingRule 负责计算

## 许可证

MIT License