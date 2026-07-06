# 时间计费引擎使用指南

本文是面向调用者的主要使用文档。README 只作为项目入口；完整接入方式、常见场景和字段语义以本文为准。

## 1. 适用场景

本项目适用于停车收费、场地租赁、设备租赁、服务时长计费等按时间收费的场景。

核心概念：

- `BillingRequest`：一次计费请求。
- `BillingConfigResolver`：调用方实现的配置解析器，决定每个方案使用什么规则、优惠和计费模式。
- `BillingRule`：具体计费规则，例如 `dayNight`、`relativeTime`、`compositeTime`。
- `PromotionGrant`：外部优惠输入，例如免费时间段、免费分钟数。
- `BillingUnit`：计费明细中的最小单元。

推荐调用入口是 `billing-api` 模块中的 `BillingTemplate`。

---

## 2. 常用包名速查

人类开发者通常可以依赖 IDE 自动导入包名；AI agent 或纯文本环境编写示例代码时，应优先参考本节。

### 2.1 核心计费类

| 类 | 包名 |
|------|------|
| `BillingService` | `cn.shang.charging.billing.BillingService` |
| `BillingConfigResolver` | `cn.shang.charging.billing.BillingConfigResolver` |
| `BillingCalculator` | `cn.shang.charging.billing.BillingCalculator` |
| `SegmentBuilder` | `cn.shang.charging.billing.SegmentBuilder` |
| `ResultAssembler` | `cn.shang.charging.settlement.ResultAssembler` |
| `BillingRequest` | `cn.shang.charging.billing.pojo.BillingRequest` |
| `BillingResult` | `cn.shang.charging.billing.pojo.BillingResult` |
| `BillingUnit` | `cn.shang.charging.billing.pojo.BillingUnit` |
| `BillingCarryOver` | `cn.shang.charging.billing.pojo.BillingCarryOver` |
| `SchemeChange` | `cn.shang.charging.billing.pojo.SchemeChange` |
| `RuleConfig` | `cn.shang.charging.billing.pojo.RuleConfig` |
| `PromotionRuleConfig` | `cn.shang.charging.billing.pojo.PromotionRuleConfig` |
| `BConstants` | `cn.shang.charging.billing.pojo.BConstants` |
| `TimeRoundingMode` | `cn.shang.charging.billing.pojo.TimeRoundingMode` |

### 2.2 `billing-api` 类

| 类 | 包名 |
|------|------|
| `BillingTemplate` | `cn.shang.charging.wrapper.BillingTemplate` |
| `CalculationWithQueryResult` | `cn.shang.charging.wrapper.CalculationWithQueryResult` |
| `PromotionEquivalentCalculator` | `cn.shang.charging.wrapper.PromotionEquivalentCalculator` |

### 2.3 计费规则类

| 类 | 包名 |
|------|------|
| `BillingRule` | `cn.shang.charging.charge.rules.BillingRule` |
| `BillingRuleRegistry` | `cn.shang.charging.charge.rules.BillingRuleRegistry` |
| `DayNightRule` | `cn.shang.charging.charge.rules.daynight.DayNightRule` |
| `DayNightConfig` | `cn.shang.charging.charge.rules.daynight.DayNightConfig` |
| `RelativeTimeRule` | `cn.shang.charging.charge.rules.relativetime.RelativeTimeRule` |
| `RelativeTimeConfig` | `cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig` |
| `RelativeTimePeriod` | `cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod` |
| `CompositeTimeRule` | `cn.shang.charging.charge.rules.compositetime.CompositeTimeRule` |
| `CompositeTimeConfig` | `cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig` |
| `CompositePeriod` | `cn.shang.charging.charge.rules.compositetime.CompositePeriod` |
| `NaturalPeriod` | `cn.shang.charging.charge.rules.compositetime.NaturalPeriod` |
| `CrossPeriodMode` | `cn.shang.charging.charge.rules.compositetime.CrossPeriodMode` |
| `InsufficientUnitMode` | `cn.shang.charging.charge.rules.compositetime.InsufficientUnitMode` |
| `FlatFreeRule` | `cn.shang.charging.charge.rules.flatfree.FlatFreeRule` |
| `FlatFreeConfig` | `cn.shang.charging.charge.rules.flatfree.FlatFreeConfig` |

### 2.4 优惠类

| 类 | 包名 |
|------|------|
| `PromotionEngine` | `cn.shang.charging.promotion.PromotionEngine` |
| `FreeTimeRangeMerger` | `cn.shang.charging.promotion.FreeTimeRangeMerger` |
| `FreeMinuteAllocator` | `cn.shang.charging.promotion.FreeMinuteAllocator` |
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

---

## 3. 安装与模块选择

### 推荐：`billing-api`

`billing-api` 提供 `BillingTemplate`，包含基础计费、查询时点金额、优惠等效金额和时间取整能力。

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>2.1.2</version>
</dependency>
```

### Spring Boot Starter

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

Spring Boot starter 默认自动注册 `dayNight`、`compositeTime`、`relativeTime` 和 `freeMinutes`。`flatFree`、`startFree` 已实现，但根据构造方式可能需要手动注册。

### 直接使用 `core`

只有在需要完全控制组件组装时才建议直接使用 `core`。普通调用方优先使用 `billing-api`。

---

## 4. 最小手动接入

### 4.1 实现 `BillingConfigResolver`

```java
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
        return new DayNightConfig()
                .setId("daynight-1")
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setUnitMinutes(60)
                .setBlockWeight(new BigDecimal("0.5"));
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

### 4.2 创建 `BillingTemplate`

```java
BillingConfigResolver configResolver = new MyBillingConfigResolver();

BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
billingRuleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
billingRuleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
billingRuleRegistry.register(BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());
billingRuleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());

PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

PromotionEngine promotionEngine = new PromotionEngine(
        configResolver,
        new FreeTimeRangeMerger(),
        new FreeMinuteAllocator(),
        promotionRuleRegistry
);

BillingService billingService = new BillingService(
        new SegmentBuilder(),
        configResolver,
        promotionEngine,
        new BillingCalculator(billingRuleRegistry),
        new ResultAssembler()
);

BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);
```

### 4.3 发起计费

```java
BillingRequest request = new BillingRequest();
request.setBeginTime(LocalDateTime.of(2026, 5, 8, 9, 0));
request.setEndTime(LocalDateTime.of(2026, 5, 8, 12, 30));
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

---

## 5. Spring Boot 接入

引入对应 starter 后，业务侧通常只需要提供 `BillingConfigResolver` Bean。

```java
@Component
public class MyBillingConfigResolver implements BillingConfigResolver {
    // 实现 resolveCalculationMode、resolveChargingRule、resolvePromotionRules
}
```

然后直接注入 `BillingTemplate`：

```java
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

starter 的自动装配范围见能力文档。复杂注册需求仍可手动装配。

---

## 6. `BillingTemplate` 常用方法

| 方法 | 用途 |
|------|------|
| `calculate(request)` | 执行基础计费，默认使用 `CEIL_BEGIN_TRUNCATE_END` 时间取整 |
| `calculate(request, roundingMode)` | 使用指定时间取整模式执行计费 |
| `calculatePromotionEquivalents(request)` | 计算每个优惠的等效金额 |
| `calculatePromotionSavings(result)` | 基于已有结果分析优惠节省金额 |
| `getConfigResolver()` | 获取配置解析器 |

---

## 7. 请求参数

### `BillingRequest`

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 否 | 请求标识，用于追踪 |
| `beginTime` | 是 | 计费开始时间 |
| `endTime` | 是 | 计费结束时间 |
| `calcEndTime` | 否 | 实际计算终点，用于局部计算 |
| `schemeId` | 条件 | 单方案 ID，与 `schemeChanges` 二选一 |
| `schemeChanges` | 条件 | 多方案切换时间轴，与 `schemeId` 二选一 |
| `segmentCalculationMode` | 是 | 分段计算模式 |
| `externalPromotions` | 否 | 外部优惠列表 |
| `timeRoundingMode` | 否 | 时间取整模式 |
| `disableSimplification` | 否 | 是否禁用长期简化计算 |
| `context` | 否 | 传递给 `BillingConfigResolver` 的上下文 |


### `SchemeChange`

| 字段 | 说明 |
|------|------|
| `lastSchemeId` | 变更前方案 ID |
| `nextSchemeId` | 变更后方案 ID |
| `changeTime` | 变更发生时间 |

---

## 8. 结果结构

### `BillingResult`

| 字段 | 说明 |
|------|------|
| `units` | 计费单元明细 |
| `promotionUsages` | 优惠使用记录 |
| `settlementAdjustments` | 结算调整记录 |
| `finalAmount` | 最终应收金额 |
| `calculationEndTime` | 实际计算到的时间 |

### `BillingUnit`

| 字段 | 说明 |
|------|------|
| `beginTime` / `endTime` | 单元起止时间 |
| `durationMinutes` | 单元时长 |
| `unitPrice` | 单元价格，由具体规则解释 |
| `originalAmount` | 优惠前金额 |
| `chargedAmount` | 单元完整结束后的最终金额 |
| `accumulatedAmount` | 单元完整结束后的累计金额 |
| `free` / `freePromotionId` | 是否由优惠完全覆盖及对应优惠 ID |
| `isTruncated` | 是否被 `calcEndTime` 截断 |
| `ruleData` | 规则私有扩展数据 |
| `compact` | 是否为 compact 单元（合并了 N 个连续相同子单元） |
| `count` | compact 单元代表的子单元数量，非 compact 始终为 1 |


---

## 10. 优惠使用

### 外部免费分钟数

```java
PromotionGrant freeMinutes = PromotionGrant.builder()
        .id("coupon-30min")
        .type(BConstants.PromotionType.FREE_MINUTES)
        .source(BConstants.PromotionSource.COUPON)
        .freeMinutes(30)
        .priority(1)
        .build();
```

### 外部免费时段

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

### 气泡型免费时段

```java
PromotionGrant bubbleRange = PromotionGrant.builder()
        .id("bubble-free")
        .type(BConstants.PromotionType.FREE_RANGE)
        .rangeType(FreeTimeRangeType.BUBBLE)
        .beginTime(beginTime)
        .endTime(endTime)
        .priority(1)
        .build();
```

`AMOUNT` 和 `DISCOUNT` 当前已作为优惠类型能力接入：会被 `PromotionEngine` 汇总，并由 `AmountDiscountApplier` 作用到结算结果；但它们仍不是独立 `PromotionRuleType`。

### 智能免费分钟数（SMART_FREE_MINUTES）

`SMART_FREE_MINUTES` 与 `FREE_MINUTES` 字段相同（都走 `freeMinutes`），区别在 `type`。它仅在 `DURATION_GLOBAL` 模式下消费——规则侧用自身价格信息按单价降序优先覆盖高价时段；其他模式遇到 `SMART_FREE_MINUTES` 会抛异常。

```java
PromotionGrant smartFreeMinutes = PromotionGrant.builder()
        .id("smart-30min")
        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
        .source(BConstants.PromotionSource.COUPON)
        .freeMinutes(30)
        .priority(1)
        .build();
```

同时存在普通 `FREE_MINUTES` 与 `SMART_FREE_MINUTES` 时，按 `priority` 排序各自分配，后分配者跳过已被占用的时段。`SMART_FREE_MINUTES` 不计入简化计算的总免费分钟数判断（仅标量透传到 `DurationGlobalStrategy`）。

---

## 11. 计费规则配置

### `dayNight`

```java
DayNightConfig config = new DayNightConfig()
        .setId("daynight-1")
        .setDayBeginMinute(8 * 60)
        .setDayEndMinute(20 * 60)
        .setDayUnitPrice(new BigDecimal("2"))
        .setNightUnitPrice(new BigDecimal("1"))
        .setMaxChargeOneDay(new BigDecimal("50"))
        .setUnitMinutes(60)
        .setBlockWeight(new BigDecimal("0.5"));
```

`blockWeight` 用于跨日夜混合单元的最终价格判断。当前 `dayNight` 已支持混合单元和封顶单元。

### `relativeTime`

```java
RelativeTimeConfig config = new RelativeTimeConfig()
        .setId("relative-1")
        .setMaxChargeOneCycle(new BigDecimal("30"))
        .setPeriods(List.of(
                new RelativeTimePeriod()
                        .setBeginMinute(0)
                        .setEndMinute(720)
                        .setUnitMinutes(60)
                        .setUnitPrice(new BigDecimal("1")),
                new RelativeTimePeriod()
                        .setBeginMinute(720)
                        .setEndMinute(1440)
                        .setUnitMinutes(60)
                        .setUnitPrice(new BigDecimal("2"))
        ));
```

### `compositeTime`

```java
CompositeTimeConfig config = new CompositeTimeConfig()
        .setId("composite-1")
        .setMaxChargeOneCycle(new BigDecimal("50"))
        .setInsufficientUnitMode(InsufficientUnitMode.FULL)
        .setPeriods(List.of(
                CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                        .naturalPeriods(List.of(
                                new NaturalPeriod(8 * 60, 20 * 60, new BigDecimal("2")),
                                new NaturalPeriod(20 * 60, 24 * 60, new BigDecimal("1"))
                        ))
                        .build()
        ));
```

### `flatFree`

```java
FlatFreeConfig config = FlatFreeConfig.builder()
        .id("flat-free-1")
        .build();
```

完整规则能力和限制见 `docs/billing-engine-capabilities-zh.md`。

---

## 13. 方案切换

当计费方案会随时间变化时，使用 `schemeChanges`：

```java
List<SchemeChange> changes = List.of(
        new SchemeChange()
                .setLastSchemeId("scheme-a")
                .setNextSchemeId("scheme-b")
                .setChangeTime(LocalDateTime.of(2026, 5, 8, 12, 0))
);

BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeChanges(changes);
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.GLOBAL_ORIGIN);
```

`SEGMENT_LOCAL` 表示每段独立起算，`GLOBAL_ORIGIN` 表示所有分段共享全局起算点再裁剪。**注意**：GLOBAL_ORIGIN 减法当前为半成品（TODO-20260702-001），多分段下会双重计费，已被守卫拦截抛异常；仅单分段可用（等价 SEGMENT_LOCAL），且 UNIT_BASED 与 GLOBAL_ORIGIN 不兼容。

---

## 14. 优惠等效金额

```java
Map<String, BigDecimal> equivalents = billingTemplate.calculatePromotionEquivalents(request);
```

等效金额基于完整计费结果计算，不依赖查询时点投影。查询不影响完整结算结果。

---

## 15. 自定义计费规则

### 15.1 定义配置

```java
@Data
public class MyRuleConfig implements RuleConfig {
    private String id;
    private BigDecimal unitPrice;

    @Override
    public String getType() {
        return "myRule";
    }
}
```

### 15.2 实现规则

```java
public class MyBillingRule implements BillingRule<MyRuleConfig> {

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          MyRuleConfig config,
                                          PromotionAggregate promotionAggregate) {
        // 规则必须是纯计算：只根据输入生成结果，不访问数据库或外部服务。
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

### 15.3 注册规则

```java
billingRuleRegistry.register("myRule", new MyBillingRule());
```

规则私有逻辑可以保存在 `ruleData` 中，

---

## 16. 常用枚举与常量

### `CalculationMode`

| 值 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间计费，时间轴可被免费时段和规则边界切分 |
| `UNIT_BASED` | 固定计费单元模式（当前仅 `dayNight` 门面下 `DayNightUnitBasedStrategy` 承载） |
| `DURATION_PERIOD` | 周期内时长计费，周期封顶 + 时段封顶，产出 `DurationSegment` |
| `DURATION_GLOBAL` | 全局时长计费，封顶按周期数倍乘，产出 `DurationSegment`；唯一消费 `SMART_FREE_MINUTES` 的模式 |

4 个规则族（`dayNight`/`relativeTime`/`naturalTime`/`compositeTime`）通过 `supportedCalculationModes()` 声明支持的模式：`dayNight` 支持 4 种；其余 3 族支持 `CONTINUOUS`/`DURATION_PERIOD`/`DURATION_GLOBAL`（不含 `UNIT_BASED`）。门面按请求模式分派到对应 `ModeStrategy`。

### `PromotionType`

| 值 | 说明 |
|------|------|
| `FREE_RANGE` | 免费时间段 |
| `FREE_MINUTES` | 免费分钟数（在窗口起点附近分配） |
| `SMART_FREE_MINUTES` | 智能免费分钟数，仅 `DURATION_GLOBAL` 模式消费，按单价降序优先高价分配；非 GLOBAL 模式报错 |
| `AMOUNT` | 金额减免，已作为优惠类型能力接入 |
| `DISCOUNT` | 折扣优惠，已作为优惠类型能力接入 |

### `ChargeRuleType`

| 常量 | 值 | 状态 |
|------|------|------|
| `DAY_NIGHT` | `dayNight` | 已实现 |
| `RELATIVE_TIME` | `relativeTime` | 已实现 |
| `COMPOSITE_TIME` | `compositeTime` | 已实现 |
| `FLAT_FREE` | `flatFree` | 已实现 |
| `TIMES` | `times` | 预留，当前无实现 |
| `NATURAL_TIME` | `naturalTime` | 已实现 |
| `NR_TIME_MIX` | `nrTimeMix` | 已废弃，由 `compositeTime` 覆盖 |

---

## 17. 能力边界

当前能力边界以以下文档为准：

- `docs/billing-engine-capabilities-zh.md`
- `docs/TODO.md`
- `docs/tracking/items/`

当本文和能力文档不一致时，应先以代码和能力文档为准，再修正本文。

---

## 18. 设计原则

- `core` 只负责纯计费计算，不访问数据库、不做持久化。
- 规则实现必须是确定性的纯计算。
- 规则配置和规则实现分离。
- 规则之间不相互调用，由引擎统一编排。
- 查询时点金额直接按 IncompleteUnitChargeMode 计费，不依赖投影机制。


