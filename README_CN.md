# 时间计费引擎

[English](README.md) | [使用指南](docs/USER_GUIDE.md) | [能力文档](docs/billing-engine-capabilities-zh.md)

一个可扩展、可追溯的时间计费引擎，适用于停车收费、场地租赁、设备租赁等按时间计费场景。

## 核心特性

- 可扩展计费规则，新增规则无需修改核心引擎
- 5 种内置规则：`dayNight`、`relativeTime`、`naturalTime`、`compositeTime`、`flatFree`
- 4 种计算模式：`CONTINUOUS`、`UNIT_BASED`、`DURATION_PERIOD`、`DURATION_GLOBAL`
- 通过统一的 `IncompleteUnitChargeSpec` 配置不足单元计费
- 优惠系统：免费时段、免费分钟数、智能免费分钟，以及时长模式下的条件生效
- 完整计费明细，支持审计和调试
- 方案切换（多段计费）
- 优惠等效金额计算（消去法）
- 纯计算核心，无数据库、无缓存、无副作用

## 环境要求

- JDK 21+
- Maven 3.6+

## 安装

### 推荐：billing-api

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

## 快速上手

### 第一步：实现 BillingConfigResolver

这是唯一需要你实现的接口。它告诉引擎每个方案使用什么规则、什么优惠、什么计算模式。

```java
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class MyConfigResolver implements BillingConfigResolver {

    @Override
    public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
        return BConstants.CalculationMode.CONTINUOUS;
    }

    @Override
    public RuleConfig resolveChargingRule(String schemeId,
                                          LocalDateTime segmentStart,
                                          LocalDateTime segmentEnd,
                                          Map<String, Object> context) {
        // 示例：日夜分时段计费规则
        return new DayNightConfig()
                .setId("rule-1")
                .setDayBeginMinute(8 * 60)       // 白天开始：08:00
                .setDayEndMinute(20 * 60)         // 白天结束：20:00
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setMaxChargeOneDay(new BigDecimal("50"))  // 每日封顶 50 元
                .setUnitMinutes(60);               // 每小时计费单元
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

### 第二步：组装引擎

#### 方式 A：纯 Java 手动组装

```java
import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.promotion.*;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;

BillingConfigResolver configResolver = new MyConfigResolver();
BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

PromotionEngine promotionEngine = new PromotionEngine(
        configResolver, new FreeTimeRangeMerger(), promotionRuleRegistry);

BillingService billingService = new BillingService(
        new SegmentBuilder(), configResolver, promotionEngine,
        new BillingCalculator(billingRuleRegistry), new ResultAssembler());

BillingTemplate billingTemplate = new BillingTemplate(billingService, configResolver);
```

#### 方式 B：Spring Boot 自动装配

引入 Starter 依赖后，只需提供 `@Component` 注解的 `BillingConfigResolver`，引擎所有组件自动装配。

```java
import org.springframework.stereotype.Component;

@Component
public class MyConfigResolver implements BillingConfigResolver { /* 实现同上 */ }
```

```java
import cn.shang.charging.wrapper.BillingTemplate;
import org.springframework.stereotype.Service;

@Service
public class MyService {
    private final BillingTemplate billingTemplate;

    public MyService(BillingTemplate billingTemplate) {
        this.billingTemplate = billingTemplate;
    }

    public BillingResult charge(BillingRequest request) {
        return billingTemplate.calculate(request);
    }
}
```

### 第三步：发起计费

```java
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BConstants;
import java.time.LocalDateTime;

BillingRequest request = new BillingRequest();
request.setBeginTime(LocalDateTime.of(2026, 5, 8, 9, 0));   // 入场 09:00
request.setEndTime(LocalDateTime.of(2026, 5, 8, 12, 30));   // 出场 12:30
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);

// 获取结果
BigDecimal amount = result.getFinalAmount();           // 最终应收金额
List<BillingUnit> units = result.getUnits();           // 计费单元明细
List<PromotionUsage> usages = result.getPromotionUsages();  // 优惠使用记录
```

## 文档导航

| 文档 | 说明 |
|------|------|
| [使用指南](docs/USER_GUIDE.md) | 完整 API 参考、字段定义、代码示例（供人类和 AI agent 使用） |
| [能力文档](docs/billing-engine-capabilities-zh.md) | 计费规则能力矩阵和设计说明 |
| [计算流程](docs/billing-engine-calculation-flow-zh.md) | 核心计费管道和计算流程 |

## 模块结构

| 模块 | 说明 |
|------|------|
| `billing-core` | 核心计费引擎 — 纯计算，零外部依赖 |
| `billing-api` | 便捷 API 封装（`BillingTemplate`） |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0.x – 3.4.x 自动装配 |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5.x – 4.x 自动装配 |
| `bill-test` | 集成测试和示例代码 |
