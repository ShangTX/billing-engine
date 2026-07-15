# 自定义计费规则开发指南

本文说明如何在当前四层架构下编写自定义计费规则。本指南先采用路径 A：

Gitee 仓库地址：https://gitee.com/shtx/charge

自定义扩展可直接参考 Gitee 仓库中的实现示例：
https://gitee.com/shtx/charge/tree/master/bill-test/src/main/java/cn/shang/charging/examples

> 直接实现 `BillingRule`，按需复用轻量公共原语，例如 `BoundaryDrivenLoop`。

这适合第一版自定义规则示例：不新增公共 API，不引入新的基类，也不要求规则作者理解完整的
`RuleSemantics` 时长模式扩展路径。

## 适用边界

路径 A 适合：

- 规则逻辑相对独立，不需要接入全部内置模式。
- 规则作者希望完全掌控 `BillingSegmentResult` 的产出。
- 规则仍可复用 `BoundaryDrivenLoop`、`BoundaryProvider`、`HomogeneousSegment` 等小工具。

路径 A 不适合：

- 希望一个规则族天然支持 `DURATION_PERIOD` / `DURATION_GLOBAL`。
- 希望复用周期封顶、时段封顶、SMART_FREE_MINUTES 等完整公共语义。
- 规则本质是新的通用时间规则族。此类规则后续应评估 `RuleSemantics` 路径。

## 示例规则：高峰/平峰按比例计费

这个示例规则叫 `peakOffPeak`：

- 每天配置一个高峰时段，例如 08:00-20:00。
- 高峰和平峰使用不同单元价格。
- 按 `unitMinutes` 作为价格单位，边界处不足一个单元时按分钟比例收费。
- 使用 `BoundaryDrivenLoop` 按以下边界切分：
  - 费率边界：高峰开始/结束。
  - 单元边界：从当前同质段起点向后推 `unitMinutes`。
  - 免费段边界：`FREE_RANGE` 的起止时间。
  - 计算窗口终点：`calcEnd`。

示例只演示 `FREE_RANGE`。如果要支持 `FREE_MINUTES`，应在策略入口调用
`RuleSupport.materializeFreeMinutes(...)`，再把时段化后的免费段传给
`BoundaryProviders.freeRangeEdges(...)`。

完整的自包含 Java 示例见
`bill-test/src/main/java/cn/shang/charging/examples/PeakOffPeakRule.java`。
该文件把配置类写成 `PeakOffPeakRule.Config` 嵌套类，便于单文件阅读和复制。
下方代码片段保留拆分写法，用于解释结构。

## 配置类

```java
package demo.billing;

import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class PeakOffPeakConfig implements RuleConfig {
    private String id;
    private int unitMinutes = 60;
    private int peakBeginMinute = 8 * 60;
    private int peakEndMinute = 20 * 60;
    private BigDecimal peakUnitPrice = BigDecimal.ZERO;
    private BigDecimal offPeakUnitPrice = BigDecimal.ZERO;

    @Override
    public String getType() {
        return "peakOffPeak";
    }
}
```

## 规则实现

```java
package demo.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class PeakOffPeakRule implements BillingRule<PeakOffPeakConfig> {

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          PeakOffPeakConfig config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        List<FreeTimeRange> freeRanges = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(rateBoundaryProvider(config));
        providers.add(unitBoundaryProvider(config.getUnitMinutes()));
        providers.add(BoundaryProviders.freeRangeEdges(freeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(
                calcBegin,
                calcEnd,
                providers,
                (begin, end) -> buildSegment(begin, end, config, freeRanges));

        List<BillingUnit> units = toUnits(segments);
        BigDecimal totalAmount = units.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PromotionUsage> usages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeRanges, calcBegin, calcEnd);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(units)
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .promotionUsages(usages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    @Override
    public Class<PeakOffPeakConfig> configClass() {
        return PeakOffPeakConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    private HomogeneousSegment buildSegment(LocalDateTime begin,
                                            LocalDateTime end,
                                            PeakOffPeakConfig config,
                                            List<FreeTimeRange> freeRanges) {
        for (FreeTimeRange range : freeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return new HomogeneousSegment(begin, end, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), range.getRangeType(), null);
            }
        }

        BigDecimal unitPrice = isPeak(begin, config)
                ? config.getPeakUnitPrice()
                : config.getOffPeakUnitPrice();
        int minutes = (int) Duration.between(begin, end).toMinutes();
        BigDecimal amount = unitPrice
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(config.getUnitMinutes()), 2, RoundingMode.HALF_UP);

        return new HomogeneousSegment(begin, end, unitPrice, amount, false, null, null);
    }

    private List<BillingUnit> toUnits(List<HomogeneousSegment> segments) {
        List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (HomogeneousSegment segment : segments) {
            BigDecimal charged = segment.isFree()
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : segment.getOriginalAmount().setScale(2, RoundingMode.HALF_UP);
            accumulated = accumulated.add(charged);
            units.add(BillingUnit.builder()
                    .beginTime(segment.getBeginTime())
                    .endTime(segment.getEndTime())
                    .durationMinutes(segment.durationMinutes())
                    .unitPrice(segment.getUnitPrice())
                    .originalAmount(segment.getOriginalAmount())
                    .free(segment.isFree())
                    .freePromotionId(segment.getFreePromotionId())
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .ruleData(segment.getRuleData())
                    .compact(false)
                    .count(1)
                    .build());
        }
        return units;
    }

    private BoundaryProvider unitBoundaryProvider(int unitMinutes) {
        return (current, calcEnd) -> {
            LocalDateTime boundary = current.plusMinutes(unitMinutes);
            return boundary.isAfter(calcEnd) ? null : boundary;
        };
    }

    private BoundaryProvider rateBoundaryProvider(PeakOffPeakConfig config) {
        return (current, calcEnd) -> {
            LocalDateTime dayStart = current.toLocalDate().atStartOfDay();
            LocalDateTime nearest = null;
            for (int i = 0; i <= 1; i++) {
                LocalDateTime candidateDay = dayStart.plusDays(i);
                nearest = nearer(current, calcEnd, nearest, candidateDay.plusMinutes(config.getPeakBeginMinute()));
                nearest = nearer(current, calcEnd, nearest, candidateDay.plusMinutes(config.getPeakEndMinute()));
            }
            return nearest;
        };
    }

    private LocalDateTime nearer(LocalDateTime current,
                                 LocalDateTime calcEnd,
                                 LocalDateTime nearest,
                                 LocalDateTime candidate) {
        if (!candidate.isAfter(current) || candidate.isAfter(calcEnd)) {
            return nearest;
        }
        return nearest == null || candidate.isBefore(nearest) ? candidate : nearest;
    }

    private boolean isPeak(LocalDateTime time, PeakOffPeakConfig config) {
        int minute = time.getHour() * 60 + time.getMinute();
        return minute >= config.getPeakBeginMinute() && minute < config.getPeakEndMinute();
    }

    private void validateConfig(PeakOffPeakConfig config) {
        if (config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getPeakBeginMinute() < 0 || config.getPeakEndMinute() > 1440
                || config.getPeakBeginMinute() >= config.getPeakEndMinute()) {
            throw new IllegalArgumentException("peak range must be within [0,1440] and begin < end");
        }
        if (config.getPeakUnitPrice() == null || config.getPeakUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("peakUnitPrice must be non-negative");
        }
        if (config.getOffPeakUnitPrice() == null || config.getOffPeakUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("offPeakUnitPrice must be non-negative");
        }
    }
}
```

## 示例规则：非线性自然日累计封顶

更贴近实际业务的高度定制示例见
`bill-test/src/main/java/cn/shang/charging/examples/ProgressiveDailyCapRule.java`。

该规则叫 `progressiveDailyCap`：

- 每小时 5 元，按分钟比例计费。
- 以自然日为周期，但不加入单元边界。
- 第 1 天累计总封顶 35 元，第 2 天 45 元，第 3 天 60 元，第 4 天 80 元，后续每天继续增加 20 元。
- 从周期封顶视角看，等价于每日增量封顶数组 `[35, 10, 15, 20]`。
- 数组最后一项代表后续自然日的重复增量，便于从既有“数组保存增量”的实现平滑迁移。
- 同一个规则类直接支持 `CONTINUOUS`、`DURATION_PERIOD`、`DURATION_GLOBAL` 三种模式。

配置核心形态如下：

```java
@Data
@Accessors(chain = true)
public static class Config implements RuleConfig {
    private String id;
    private BigDecimal unitPricePerHour = new BigDecimal("5.00");

    // 第 1 项表示第 1 个自然日增量封顶；超过数组长度后复用最后一项。
    private BigDecimal[] dailyIncrementCaps = new BigDecimal[] {
            new BigDecimal("35.00"),
            new BigDecimal("10.00"),
            new BigDecimal("15.00"),
            new BigDecimal("20.00")
    };

    @Override
    public String getType() {
        return "progressiveDailyCap";
    }
}
```

这个示例仍属于路径 A：它直接实现 `BillingRule`，复用 `BoundaryDrivenLoop` 做自然日边界、
免费段边界和 `calcEnd` 边界切分，但封顶、累计、三种模式的结果结构都由规则自己掌控。
原因是它的封顶序列不是通用 `cap × 周期数`，而是业务私有的非线性曲线。

## 注册规则

纯 Java 组装时，直接注册到 `BillingRuleRegistry`：

```java
BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
billingRuleRegistry.register("peakOffPeak", new PeakOffPeakRule());
```

Spring Boot starter 当前没有专门的 registry customizer。可以提供一个自己的
`BillingRuleRegistry` bean，并在其中注册内置规则和自定义规则；后续如果需要降低样板，
再考虑新增 `BillingRuleRegistryCustomizer`。

## 配置解析

`BillingConfigResolver` 返回自定义配置即可：

```java
@Override
public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
    return BConstants.CalculationMode.CONTINUOUS;
}

@Override
public RuleConfig resolveChargingRule(String schemeId,
                                      LocalDateTime segmentStart,
                                      LocalDateTime segmentEnd,
                                      Map<String, Object> context) {
    return new PeakOffPeakConfig()
            .setId("peak-off-peak-1")
            .setUnitMinutes(60)
            .setPeakBeginMinute(8 * 60)
            .setPeakEndMinute(20 * 60)
            .setPeakUnitPrice(new BigDecimal("6.00"))
            .setOffPeakUnitPrice(new BigDecimal("2.00"));
}
```

## 验证清单

自定义规则至少应验证：

- 规则类型未注册时会报错，注册后可正常计费。
- `configClass()` 与实际配置类型不匹配时会报错。
- `supportedCalculationModes()` 不包含请求模式时会报错。
- 计费窗口命中费率边界时，会按边界切开。
- 计费窗口命中 `FREE_RANGE` 时，免费段单独产出且金额为 0。
- `BillingSegmentResult.chargedAmount` 等于所有 `BillingUnit.chargedAmount` 之和。
- 规则实现不访问数据库、缓存、远程服务或全局可变状态。

## 后续方向

如果一个自定义规则只是普通的时间规则族，并希望复用通用周期封顶、时段封顶、
`SMART_FREE_MINUTES` 等能力，应评估 `RuleSemantics` 路径，让时长模式复用
`DurationPeriodStrategy` 和 `DurationGlobalStrategy`。

如果规则像 `progressiveDailyCap` 一样带有强业务私有语义，例如非线性的累计总封顶曲线，
继续直接实现 `BillingRule` 会更清晰，但要自己保证不同模式下的结果结构和金额一致性。
