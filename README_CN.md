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
    <version>1.0.0</version>
</dependency>
```

#### 方式二：Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>1.0.0</version>
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

### BillingTemplate 方法

| 方法 | 说明 |
|------|------|
| `calculate(request)` | 基础计费计算 |
| `calculateWithQuery(request, queryTime)` | 计算并返回指定时间点的费用状态 |
| `calculatePromotionEquivalents(request)` | 计算每个优惠的等效金额 |

### 输入：BillingRequest

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `beginTime` | LocalDateTime | **是** | 计费开始时间 |
| `endTime` | LocalDateTime | **是** | 计费结束时间 |
| `schemeId` | String | 条件 | 计费方案ID（与 `schemeChanges` 二选一） |
| `schemeChanges` | List\<SchemeChange\> | 条件 | 方案变更时间轴 |
| `segmentCalculationMode` | SegmentCalculationMode | **是** | 分段计算模式 |
| `externalPromotions` | List\<PromotionGrant\> | 否 | 外部优惠列表 |
| `previousCarryOver` | BillingCarryOver | 否 | 上次结转状态（CONTINUE 模式） |

### 输出：BillingResult

| 字段 | 类型 | 说明 |
|------|------|------|
| `finalAmount` | BigDecimal | 最终应收金额 |
| `units` | List\<BillingUnit\> | 计费单元明细 |
| `promotionUsages` | List\<PromotionUsage\> | 优惠使用记录 |
| `carryOver` | BillingCarryOver | 结转状态，供下次计算使用 |

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