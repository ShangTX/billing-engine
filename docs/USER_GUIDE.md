# 时间计费引擎使用指南

## 简介

本系统是一个**可扩展、可追溯、可组合规则**的时间计费引擎，适用于场地使用费、设备租赁、服务时长计费等按时间收费的场景。

**核心设计思想**：
```
时间轴 → 计费单元切割 → 应用优惠 → 应用收费规则 → 生成计费明细 → 汇总费用
```

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+

### 添加依赖

#### 方式一：使用 billing-api（推荐）

`billing-api` 提供了 `BillingTemplate` 便捷封装，包含查询时间点计算、优惠等效金额计算等高级功能。

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 方式二：Spring Boot 项目

根据 Spring Boot 版本选择对应的 Starter：

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

#### 方式三：直接使用核心模块

适用于需要完全控制组件组装的场景：

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 使用 billing-api（推荐）

### 1. 实现 BillingConfigResolver

```java
public class MyBillingConfigResolver implements BillingConfigResolver {

    @Override
    public BConstants.BillingMode resolveBillingMode(String schemeId) {
        return BConstants.BillingMode.CONTINUOUS;
    }

    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd) {
        // 返回对应 schemeId 的计费规则配置
        return new DayNightConfig()
            .setId("daynight-1")
            .setDayBeginMinute(740)              // 白天开始：12:20
            .setDayEndMinute(1140)               // 白天结束：19:00
            .setDayUnitPrice(new BigDecimal("2")) // 白天单价：2元/小时
            .setNightUnitPrice(new BigDecimal("1")) // 夜间单价：1元/小时
            .setMaxChargeOneDay(new BigDecimal("50")) // 每日封顶：50元
            .setUnitMinutes(60)                  // 计费单元：60分钟
            .setBlockWeight(new BigDecimal("0.5")); // 跨时段权重阈值
    }

    @Override
    public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                           LocalDateTime segmentStart,
                                                           LocalDateTime segmentEnd) {
        // 返回规则级别的优惠配置（可选）
        return List.of(
            new FreeMinutesPromotionConfig()
                .setId("free-30min")
                .setPriority(1)
                .setMinutes(30)
        );
    }
}
```

### 2. 创建 BillingTemplate 并使用

```java
public class BillingService {

    private final BillingTemplate billingTemplate;

    public BillingService() {
        BillingConfigResolver configResolver = new MyBillingConfigResolver();
        BillingService billingService = BillingServiceFactory.create(configResolver);
        this.billingTemplate = new BillingTemplate(billingService, configResolver);
    }

    public BillingResult calculateFee(LocalDateTime beginTime,
                                      LocalDateTime endTime) {
        BillingRequest request = new BillingRequest();
        request.setId(UUID.randomUUID().toString());
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeId("scheme-1");
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

        return billingTemplate.calculate(request);
    }
}
```

### 3. BillingTemplate 提供的方法

| 方法 | 说明 |
|------|------|
| `calculate(request)` | 基础计费计算 |
| `calculateWithQuery(request, queryTime)` | 计算并返回指定时间点的费用状态 |
| `calculatePromotionEquivalents(request)` | 计算每个优惠的等效金额 |
| `calculatePromotionSavings(result)` | 分析已使用优惠节省的金额 |

---

## Spring Boot 集成

### 1. 配置 application.yml

```yaml
billing:
  schemes:
    scheme-1:
      rule-type: dayNight          # 规则类型
      billing-mode: CONTINUOUS     # 计费模式
      simplified-threshold: 10     # 简化计算阈值（0表示禁用）
    scheme-2:
      rule-type: relativeTime
      billing-mode: CONTINUOUS
```

### 2. 实现 BillingConfigResolver

```java
@Component
public class MyBillingConfigResolver implements BillingConfigResolver {
    // 实现同上...
}
```

### 3. 注入 BillingTemplate 使用

```java
@Service
public class BillingAppService {

    private final BillingTemplate billingTemplate;

    public BillingAppService(BillingTemplate billingTemplate) {
        this.billingTemplate = billingTemplate;
    }

    public BillingResult calculateFee(LocalDateTime beginTime,
                                      LocalDateTime endTime) {
        BillingRequest request = new BillingRequest();
        request.setId(UUID.randomUUID().toString());
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeId("scheme-1");
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

        return billingTemplate.calculate(request);
    }
}
```

---

## 输入输出参数说明

### BillingRequest（输入）

计费请求对象，包含计费所需的所有信息。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | 否 | 请求标识，用于追踪和调试 |
| `beginTime` | LocalDateTime | **是** | 计费开始时间 |
| `endTime` | LocalDateTime | **是** | 计费结束时间 |
| `schemeId` | String | 条件 | 计费方案ID，与 `schemeChanges` 二选一 |
| `schemeChanges` | List\<SchemeChange\> | 条件 | 方案变更时间轴，用于规则随时间变化的场景 |
| `segmentCalculationMode` | SegmentCalculationMode | **是** | 分段计算模式 |
| `externalPromotions` | List\<PromotionGrant\> | 否 | 外部优惠列表（优惠券等） |
| `previousCarryOver` | BillingCarryOver | 否 | 上次计算的结转状态，用于 CONTINUE 模式 |
| `queryTime` | LocalDateTime | 否 | 查询时间点，用于返回该时刻的费用状态 |
| `calcEndTime` | LocalDateTime | 否 | 计算结束时间，用于控制计算进度，默认使用 endTime |

### BillingResult（输出）

计费结果对象，包含费用明细和结转状态。

| 字段 | 类型 | 说明 |
|------|------|------|
| `finalAmount` | BigDecimal | 最终应收金额 |
| `units` | List\<BillingUnit\> | 计费单元明细列表 |
| `promotionUsages` | List\<PromotionUsage\> | 优惠使用情况 |
| `settlementAdjustments` | List\<SettlementAdjustment\> | 结算调整记录 |
| `calculationEndTime` | LocalDateTime | 实际计算到的时间点（用于 CONTINUE 模式） |
| `effectiveFrom` | LocalDateTime | 价格有效起始时间 |
| `effectiveTo` | LocalDateTime | 价格有效结束时间 |
| `carryOver` | BillingCarryOver | 结转状态，供下次继续计算使用 |

### BillingUnit（计费单元）

最小计费单位，记录单个时间段的计费详情。

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
| `isTruncated` | Boolean | 是否被截断（用于 CONTINUE 模式恢复） |
| `ruleData` | Object | 规则扩展数据 |

### PromotionGrant（优惠输入）

外部传入的优惠信息。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | **是** | 优惠ID |
| `type` | PromotionType | **是** | 优惠类型 |
| `source` | PromotionSource | 否 | 优惠来源（RULE/COUPON） |
| `priority` | Integer | 否 | 优先级，数值越大优先级越高 |
| `freeMinutes` | Integer | 条件 | 免费分钟数（FREE_MINUTES 类型必填） |
| `beginTime` | LocalDateTime | 条件 | 时段开始时间（FREE_RANGE 类型必填） |
| `endTime` | LocalDateTime | 条件 | 时段结束时间（FREE_RANGE 类型必填） |
| `rangeType` | FreeTimeRangeType | 否 | 免费时段类型（NORMAL/BUBBLE），仅 FREE_RANGE 有效 |

### PromotionUsage（优惠使用）

记录优惠的实际使用情况。

| 字段 | 类型 | 说明 |
|------|------|------|
| `promotionId` | String | 优惠ID |
| `type` | PromotionType | 优惠类型 |
| `grantedMinutes` | long | 授予的免费分钟数 |
| `usedMinutes` | long | 实际使用的免费分钟数 |
| `usedFrom` | LocalDateTime | 使用开始时间 |
| `usedTo` | LocalDateTime | 使用结束时间 |
| `equivalentAmount` | BigDecimal | 等效优惠金额 |

### BillingCarryOver（结转状态）

用于 CONTINUE 模式的状态传递。

| 字段 | 类型 | 说明 |
|------|------|------|
| `calculatedUpTo` | LocalDateTime | 已计算到的时间点 |
| `segments` | Map\<String, SegmentCarryOver\> | 各分段的结转状态 |

### SchemeChange（方案变更）

记录计费规则的变更时间点。

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastSchemeId` | String | 变更前的方案ID |
| `nextSchemeId` | String | 变更后的方案ID |
| `changeTime` | LocalDateTime | 变更时间 |

### 枚举类型

#### BillingMode（计费模式）

| 值 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间计费，时间可被优惠打断 |
| `UNIT_BASED` | 按计费单元独立计算 |

#### PromotionType（优惠类型）

| 值 | 说明 |
|------|------|
| `FREE_MINUTES` | 免费分钟数 |
| `FREE_RANGE` | 免费时间段 |
| `AMOUNT` | 金额减免（待实现） |
| `DISCOUNT` | 折扣优惠（待实现） |

#### PromotionSource（优惠来源）

| 值 | 说明 |
|------|------|
| `RULE` | 规则级别优惠 |
| `COUPON` | 外部优惠券 |

#### SegmentCalculationMode（分段计算模式）

| 值 | 说明 |
|------|------|
| `SINGLE` | 仅单个分段 |
| `SEGMENT_LOCAL` | 每个分段独立起算 |
| `GLOBAL_ORIGIN` | 全局起算 + 分段截取 |

#### FreeTimeRangeType（免费时段类型）

| 值 | 说明 |
|------|------|
| `NORMAL` | 普通免费时段 |
| `BUBBLE` | 气泡型，延长周期边界 |

---

## 核心概念

### 计费模式（BillingMode）

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| `CONTINUOUS` | 连续时间计费，时间可被优惠打断 | 免费时段中间部分不收费 |
| `UNIT_BASED` | 按计费单元独立计算 | 按小时/半小时固定收费 |

**示例对比**：
- 计费时间 05:00 - 08:15，免费时段 06:30-07:30
- CONTINUOUS：6元（免费时段被打断，只收 05:00-06:30 和 07:30-08:15）
- UNIT_BASED：8元（免费时段未完全覆盖计费单元，按全额收取）

### 计费规则类型

| 规则类型 | 说明 |
|---------|------|
| `dayNight` | 日夜分时段计费，不同时段不同价格 |
| `relativeTime` | 相对时间段计费，从开始时间起算 |
| `compositeTime` | 混合时间计费，组合多个时间段规则 |

### 分段计算模式

| 模式 | 说明 |
|------|------|
| `SINGLE` | 仅单个分段 |
| `SEGMENT_LOCAL` | 每个分段独立起算 |
| `GLOBAL_ORIGIN` | 全局起算 + 分段截取 |

---

## 优惠类型

### 免费分钟数（FREE_MINUTES）

```java
PromotionGrant freeMinutes = PromotionGrant.builder()
    .id("coupon-30min")
    .type(BConstants.PromotionType.FREE_MINUTES)
    .source(BConstants.PromotionSource.COUPON)
    .freeMinutes(30)
    .priority(1)
    .build();
```

### 免费时间段（FREE_RANGE）

```java
PromotionGrant freeRange = PromotionGrant.builder()
    .id("free-lunch")
    .type(BConstants.PromotionType.FREE_RANGE)
    .source(BConstants.PromotionSource.COUPON)
    .beginTime(LocalDateTime.of(2026, 3, 25, 12, 0))
    .endTime(LocalDateTime.of(2026, 3, 25, 14, 0))
    .priority(1)
    .build();
```

### 使用外部优惠

```java
List<PromotionGrant> externalPromotions = new ArrayList<>();
externalPromotions.add(freeMinutes);
externalPromotions.add(freeRange);

request.setExternalPromotions(externalPromotions);
```

---

## 继续计算模式（CONTINUE）

支持从上次计算结果继续计算，适用于长期计费场景。

### 基本用法

```java
// 首次计算
BillingRequest request1 = new BillingRequest();
request1.setBeginTime(beginTime);
request1.setEndTime(firstQueryTime);
// ... 其他设置

BillingResult result1 = billingTemplate.calculate(request1);

// 继续计算
BillingRequest request2 = new BillingRequest();
request2.setBeginTime(beginTime);  // 原始开始时间
request2.setEndTime(secondQueryTime);
request2.setPreviousCarryOver(result1.getCarryOver()); // 传入上次结转状态

BillingResult result2 = billingTemplate.calculate(request2);
```

### 结转状态说明

`BillingCarryOver` 包含：
- `calculatedUpTo`: 已计算到的时间点
- `segments`: 各分段的结转状态（封顶累计、优惠使用情况等）

---

## 规则配置详解

### DayNightConfig（日夜分时段）

```java
DayNightConfig config = new DayNightConfig()
    .setId("daynight-1")
    .setDayBeginMinute(740)                  // 白天开始分钟（12:20 = 12*60+20）
    .setDayEndMinute(1140)                   // 白天结束分钟（19:00 = 19*60）
    .setDayUnitPrice(new BigDecimal("2"))    // 白天单价
    .setNightUnitPrice(new BigDecimal("1"))  // 夜间单价
    .setMaxChargeOneDay(new BigDecimal("50")) // 每日封顶
    .setUnitMinutes(60)                      // 计费单元（分钟）
    .setBlockWeight(new BigDecimal("0.5"));  // 跨时段权重阈值
```

**参数说明**：
- `dayBeginMinute`/`dayEndMinute`: 一天内的分钟数（0-1439）
- `blockWeight`: 当计费单元跨越日夜边界时，白天占比>=此值则按白天价格

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
            .setType("natural")              // 自然时间段
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

---

## 计费结果解析

```java
BillingResult result = billingTemplate.calculate(request);

// 最终金额
BigDecimal finalAmount = result.getFinalAmount();

// 计费单元明细
List<BillingUnit> units = result.getUnits();
for (BillingUnit unit : units) {
    System.out.println("时段: " + unit.getBeginTime() + " - " + unit.getEndTime());
    System.out.println("时长: " + unit.getDurationMinutes() + "分钟");
    System.out.println("单价: " + unit.getUnitPrice());
    System.out.println("原价: " + unit.getOriginalAmount());
    System.out.println("实收: " + unit.getChargedAmount());
    System.out.println("是否免费: " + unit.isFree());
}

// 优惠使用情况
List<PromotionUsage> usages = result.getPromotionUsages();

// 继续计算的结转状态
BillingCarryOver carryOver = result.getCarryOver();
```

---

## 高级功能

### 查询时间点计算

返回指定时刻的费用状态，同时保留完整计算结果：

```java
CalculationWithQueryResult result = billingTemplate.calculateWithQuery(request, queryTime);

BillingResult fullResult = result.getCalculationResult();  // 完整计算结果
BillingResult queryResult = result.getQueryResult();       // 查询时刻的结果
```

### 优惠等效金额计算

计算每个优惠实际节省的金额：

```java
Map<String, BigDecimal> equivalents = billingTemplate.calculatePromotionEquivalents(request);
// 返回: promotionId → 等效金额
```

### 方案切换

当计费规则随时间变化时：

```java
List<SchemeChange> changes = List.of(
    new SchemeChange()
        .setChangeTime(LocalDateTime.of(2026, 3, 25, 12, 0))
        .setFromSchemeId("scheme-morning")
        .setToSchemeId("scheme-afternoon")
);

request.setSchemeChanges(changes);
```

---

## 直接使用核心模块

适用于需要完全控制组件组装的场景：

```java
// 1. 创建配置解析器
BillingConfigResolver configResolver = new MyBillingConfigResolver();

// 2. 注册规则
BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

// 3. 创建服务
PromotionEngine promotionEngine = new PromotionEngine(
    configResolver,
    new FreeTimeRangeMerger(),
    new FreeMinuteAllocator(),
    promotionRegistry
);

BillingService billingService = new BillingService(
    new SegmentBuilder(),
    configResolver,
    promotionEngine,
    new BillingCalculator(ruleRegistry),
    new ResultAssembler()
);

// 4. 计算费用
BillingResult result = billingService.calculate(request);
```

---

## 扩展自定义规则

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
        // ...
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

---

## 运行测试

```bash
# 编译项目
mvn clean install -DskipTests -q

# 运行测试示例
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.DayNightTest"
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.PromotionTest"
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.ContinueModeTest"
```

---

## 设计原则

1. **核心引擎只负责计算** - 无缓存、无数据库、无副作用
2. **规则必须是纯计算** - 输入相同则输出相同（确定性）
3. **规则不应相互依赖** - 所有规则通过 Engine 统一执行
4. **规则配置与实现分离** - RuleConfig 只描述参数，BillingRule 负责计算

---

## 模块结构

| 模块 | 职责 |
|------|------|
| `core` | 核心计费引擎，纯计算逻辑 |
| `billing-api` | 便捷 API 封装（推荐使用） |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0-3.4 集成 |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5-4.x 集成 |
| `bill-test` | 测试用例 |

---

## 许可证

本项目为内部使用项目。