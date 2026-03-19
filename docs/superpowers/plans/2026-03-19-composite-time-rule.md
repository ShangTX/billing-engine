# CompositeTimeRule 混合计费规则实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 CompositeTimeRule 混合计费规则，支持两层嵌套（相对时间段 + 自然时段价格）、双层封顶、多种跨时段处理模式、延伸逻辑、CONTINUOUS 模式。

**Architecture:** 创建新的规则包 `compositetime`，包含配置类、规则实现类。遵循现有规则模式（BillingRule 接口），支持 UNIT_BASED 和 CONTINUOUS 两种计费模式。

**Tech Stack:** Java 17+, Lombok, BigDecimal

---

## 文件结构

```
core/src/main/java/cn/shang/charging/
├── charge/rules/compositetime/
│   ├── CompositeTimeRule.java          # 规则实现（主类）
│   ├── CompositeTimeConfig.java        # 配置类
│   ├── CompositePeriod.java            # 相对时间段配置
│   ├── NaturalPeriod.java              # 自然时段配置
│   ├── CrossPeriodMode.java            # 跨时段处理模式枚举
│   └── InsufficientUnitMode.java       # 不足单元计费模式枚举
└── billing/pojo/BConstants.java        # 新增规则类型常量

bill-test/src/main/java/cn/shang/charging/
└── CompositeTimeTest.java              # 测试类
```

---

## Phase 1: 数据结构定义

### Task 1: 创建 InsufficientUnitMode 枚举

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/InsufficientUnitMode.java`

- [ ] **Step 1: 创建枚举类**

```java
package cn.shang.charging.charge.rules.compositetime;

/**
 * 不足单元计费模式
 */
public enum InsufficientUnitMode {

    /** 全额收费 */
    FULL,

    /** 按比例收费 */
    PROPORTIONAL
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/InsufficientUnitMode.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 InsufficientUnitMode 枚举"
```

---

### Task 2: 创建 CrossPeriodMode 枚举

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CrossPeriodMode.java`

- [ ] **Step 1: 创建枚举类**

```java
package cn.shang.charging.charge.rules.compositetime;

/**
 * 跨自然时段处理模式
 */
public enum CrossPeriodMode {

    /** 按时间比例判断用哪个价格（类似 DayNightRule 的 blockWeight） */
    BLOCK_WEIGHT,

    /** 取较高价格 */
    HIGHER_PRICE,

    /** 取较低价格 */
    LOWER_PRICE,

    /** 按比例拆分计算 */
    PROPORTIONAL,

    /** 取开始时间所在时段的价格 */
    BEGIN_TIME_PRICE,

    /** 取结束时间所在时段的价格 */
    END_TIME_PRICE,

    /** 取开始时间价格，并用自然时段边界截断单元 */
    BEGIN_TIME_TRUNCATE
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CrossPeriodMode.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 CrossPeriodMode 枚举"
```

---

### Task 3: 创建 NaturalPeriod 配置类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/NaturalPeriod.java`

- [ ] **Step 1: 创建配置类**

```java
package cn.shang.charging.charge.rules.compositetime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 自然时段配置
 * <p>
 * 表示一天内的自然时间段，支持跨天表示
 * 例如：beginMinute=1200(20:00), endMinute=480(08:00) 表示 20:00 到次日 08:00
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NaturalPeriod {

    /**
     * 自然时段开始分钟（一天内的分钟数，0-1440）
     * 小于 endMinute 时表示不跨天
     * 大于 endMinute 时表示跨天
     */
    private int beginMinute;

    /**
     * 自然时段结束分钟
     * 小于 beginMinute 表示跨天
     */
    private int endMinute;

    /**
     * 单元价格
     */
    private BigDecimal unitPrice;
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/NaturalPeriod.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 NaturalPeriod 配置类"
```

---

### Task 4: 创建 CompositePeriod 配置类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositePeriod.java`

- [ ] **Step 1: 创建配置类**

```java
package cn.shang.charging.charge.rules.compositetime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 相对时间段配置
 * <p>
 * 以计费起点为基准的相对时间段，每个时间段内可有不同的单元长度和自然时段价格
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositePeriod {

    /**
     * 相对开始分钟（相对于计费起点，0-1440）
     */
    private int beginMinute;

    /**
     * 相对结束分钟
     */
    private int endMinute;

    /**
     * 计费单元长度（分钟）
     */
    private int unitMinutes;

    /**
     * 时间段独立封顶（可选）
     */
    private BigDecimal maxCharge;

    /**
     * 跨自然时段处理模式
     */
    private CrossPeriodMode crossPeriodMode;

    /**
     * 自然时段价格列表
     * 必须覆盖全天（0-1440分钟）
     */
    private List<NaturalPeriod> naturalPeriods;
}
```

- [ ] **Step 2: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositePeriod.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 CompositePeriod 配置类"
```

---

### Task 5: 创建 CompositeTimeConfig 配置类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeConfig.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`

- [ ] **Step 1: 在 BConstants 中添加规则类型常量**

在 `BConstants.ChargeRuleType` 类中添加：

```java
public static String COMPOSITE_TIME = "compositeTime"; // 混合时间计费
```

- [ ] **Step 2: 创建配置类**

```java
package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 混合计费规则配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class CompositeTimeConfig implements RuleConfig {

    private String id;

    @Builder.Default
    private String type = BConstants.ChargeRuleType.COMPOSITE_TIME;

    /** 周期封顶金额（必填） */
    private BigDecimal maxChargeOneCycle;

    /** 不足单元计费模式（默认全额） */
    @Builder.Default
    private InsufficientUnitMode insufficientUnitMode = InsufficientUnitMode.FULL;

    /** 相对时间段列表 */
    private List<CompositePeriod> periods;
}
```

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeConfig.java
git add core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 CompositeTimeConfig 配置类"
```

---

## Phase 2: 配置校验与规则框架

### Task 6: 创建测试类和配置校验测试

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java`

- [ ] **Step 1: 创建测试类**

```java
package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingSegment;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompositeTimeTest {

    // ========== 配置校验测试 ==========

    @Test
    void testConfigValidation_NaturalPeriodNotFullCoverage() {
        CompositeTimeConfig config = createBaseConfig();
        config.getPeriods().get(0).setNaturalPeriods(List.of(
                NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.ONE).build()
        ));

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    @Test
    void testConfigValidation_PeriodsNotContinuous() {
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0)
                                .endMinute(60)
                                .unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(createFullCoverageNaturalPeriods())
                                .build()
                ))
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    @Test
    void testConfigValidation_MaxChargeOneCycleRequired() {
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .periods(createValidPeriods())
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    // ========== 辅助方法 ==========

    private CompositeTimeConfig createBaseConfig() {
        return CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(createValidPeriods())
                .build();
    }

    private List<CompositePeriod> createValidPeriods() {
        return List.of(
                CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                        .naturalPeriods(createFullCoverageNaturalPeriods())
                        .build()
        );
    }

    private List<NaturalPeriod> createFullCoverageNaturalPeriods() {
        return List.of(
                NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
        );
    }

    private BillingContext createBaseContext() {
        BillingSegment segment = BillingSegment.builder()
                .billingBeginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .build();
        return BillingContext.builder()
                .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                .billingMode(BConstants.BillingMode.UNIT_BASED)
                .segment(segment)
                .build();
    }
}
```

- [ ] **Step 2: 提交测试框架**

```bash
git add bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java
git commit -m "[claude-code|glm-5|superpowers] test: 新增 CompositeTimeRule 配置校验测试"
```

---

### Task 7: 实现 CompositeTimeRule 框架和配置校验

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 创建规则类框架**

```java
package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public class CompositeTimeRule implements BillingRule<CompositeTimeConfig> {

    private static final int MINUTES_PER_DAY = 1440;

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig ruleConfig,
                                          cn.shang.charging.promotion.pojo.PromotionAggregate promotionAggregate) {
        validateConfig(ruleConfig);
        // TODO: 实现计费逻辑
        return BillingSegmentResult.builder().build();
    }

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.UNIT_BASED, BConstants.BillingMode.CONTINUOUS);
    }

    private void validateConfig(CompositeTimeConfig config) {
        if (config.getMaxChargeOneCycle() == null || config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle 必填且必须为正数");
        }

        List<CompositePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }

        validatePeriodsContinuous(periods);

        for (CompositePeriod period : periods) {
            validateNaturalPeriodsCoverage(period.getNaturalPeriods());
        }
    }

    private void validatePeriodsContinuous(List<CompositePeriod> periods) {
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("第一个时间段必须从 0 分钟开始");
        }
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_DAY) {
            throw new IllegalArgumentException("最后一个时间段必须结束于 1440 分钟");
        }
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("相邻时间段必须首尾相连");
            }
        }
    }

    private void validateNaturalPeriodsCoverage(List<NaturalPeriod> naturalPeriods) {
        if (naturalPeriods == null || naturalPeriods.isEmpty()) {
            throw new IllegalArgumentException("naturalPeriods 不能为空");
        }
        int totalCovered = 0;
        for (NaturalPeriod period : naturalPeriods) {
            if (period.getBeginMinute() < period.getEndMinute()) {
                totalCovered += period.getEndMinute() - period.getBeginMinute();
            } else {
                totalCovered += (MINUTES_PER_DAY - period.getBeginMinute()) + period.getEndMinute();
            }
        }
        if (totalCovered != MINUTES_PER_DAY) {
            throw new IllegalArgumentException("自然时段必须覆盖全天（0-1440分钟）");
        }
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `mvn test -pl bill-test -Dtest=CompositeTimeTest -q`
Expected: PASS (配置校验测试通过)

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: 实现 CompositeTimeRule 配置校验逻辑"
```

---

## Phase 3: UNIT_BASED 模式核心实现

### Task 8: 测试 - 基本计费场景

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java`

- [ ] **Step 1: 添加基本计费测试**

```java
// ========== UNIT_BASED 模式测试 ==========

@Test
void testUnitBased_BasicCalculation() {
    CompositeTimeConfig config = createBaseConfig();

    CompositeTimeRule rule = new CompositeTimeRule();
    BillingSegmentResult result = rule.calculate(createBaseContext(), config, PromotionAggregate.empty());

    assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
    assertEquals(2, result.getBillingUnits().size());
}

@Test
void testUnitBased_TwoRelativePeriods() {
    CompositeTimeConfig config = CompositeTimeConfig.builder()
            .id("test")
            .maxChargeOneCycle(BigDecimal.valueOf(50))
            .periods(List.of(
                    CompositePeriod.builder()
                            .beginMinute(0).endMinute(120).unitMinutes(60)
                            .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                            .naturalPeriods(List.of(NaturalPeriod.builder()
                                    .beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()))
                            .build(),
                    CompositePeriod.builder()
                            .beginMinute(120).endMinute(1440).unitMinutes(30)
                            .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                            .naturalPeriods(List.of(NaturalPeriod.builder()
                                    .beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()))
                            .build()
            ))
            .build();

    BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                                               LocalDateTime.of(2026, 1, 1, 11, 0));

    CompositeTimeRule rule = new CompositeTimeRule();
    BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.empty());

    // 08:00-10:00: 2单元 × 1元 = 2元
    // 10:00-11:00: 2单元 × 2元 = 4元
    assertEquals(0, BigDecimal.valueOf(6).compareTo(result.getChargedAmount()));
}

private BillingContext createBaseContext(LocalDateTime begin, LocalDateTime end) {
    BillingSegment segment = BillingSegment.builder()
            .billingBeginTime(begin)
            .build();
    return BillingContext.builder()
            .beginTime(begin)
            .endTime(end)
            .billingMode(BConstants.BillingMode.UNIT_BASED)
            .segment(segment)
            .build();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl bill-test -Dtest=CompositeTimeTest#testUnitBased_BasicCalculation -q`
Expected: FAIL

- [ ] **Step 3: 提交测试**

```bash
git add bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java
git commit -m "[claude-code|glm-5|superpowers] test: 新增基本计费测试"
```

---

### Task 9: 实现 UNIT_BASED 基本计算

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 实现计算逻辑**

参考 RelativeTimeRule 的实现模式，添加：
- `calculate()` 主方法
- `calculateCycle()` 周期计算
- `generateUnitsInPeriod()` 单元生成
- `calculateUnitPrice()` 价格计算
- `findNaturalPeriod()` 时段查找
- 封顶逻辑

（完整代码参考设计文档，此处省略详细代码）

- [ ] **Step 2: 运行测试确认通过**

Run: `mvn test -pl bill-test -Dtest=CompositeTimeTest -q`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: 实现 UNIT_BASED 模式基础计算"
```

---

### Task 10: 测试与实现 - CrossPeriodMode 各模式

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 添加跨时段模式测试**

```java
@Test
void testCrossPeriodMode_HigherPrice() {
    // 自然时段：08:00-20:00 单价2元，20:00-08:00 单价1元
    // 计费单元跨越边界，取较高价格
    CompositeTimeConfig config = CompositeTimeConfig.builder()
            .id("test")
            .maxChargeOneCycle(BigDecimal.valueOf(50))
            .periods(List.of(
                    CompositePeriod.builder()
                            .beginMinute(0).endMinute(1440).unitMinutes(60)
                            .crossPeriodMode(CrossPeriodMode.HIGHER_PRICE)
                            .naturalPeriods(List.of(
                                    NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                    NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                    NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                            ))
                            .build()
            ))
            .build();

    // 19:30-20:30 跨越边界
    BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 19, 30),
                                               LocalDateTime.of(2026, 1, 1, 20, 30));

    CompositeTimeRule rule = new CompositeTimeRule();
    BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.empty());

    // 应该取较高价格 2元
    assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
}

@Test
void testCrossPeriodMode_LowerPrice() {
    // 类似上面，但取较低价格
    // 测试代码类似，使用 CrossPeriodMode.LOWER_PRICE
}

@Test
void testCrossPeriodMode_BeginTimePrice() {
    // 取开始时间所在时段的价格
}
```

- [ ] **Step 2: 确保所有模式实现完整**

- [ ] **Step 3: 运行测试**

- [ ] **Step 4: 提交**

```bash
git add bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: 完善跨时段处理模式实现"
```

---

## Phase 4: 封顶逻辑

### Task 11: 时间段封顶测试与实现

- [ ] **Step 1: 添加测试**
- [ ] **Step 2: 实现封顶逻辑**
- [ ] **Step 3: 提交**

---

### Task 12: 周期封顶测试与实现

- [ ] **Step 1: 添加测试**
- [ ] **Step 2: 实现封顶逻辑**
- [ ] **Step 3: 提交**

---

## Phase 5: 延伸逻辑（设计文档 4.6）

### Task 13: 测试 - 延伸逻辑

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java`

- [ ] **Step 1: 添加延伸测试**

```java
@Test
void testExtension_StopAtRelativePeriodBoundary() {
    // 最后单元不足，但遇到相对时间段边界，不延伸
}

@Test
void testExtension_StopAtFreeTimeBoundary() {
    // 延伸到免费时间段边界停止
}

@Test
void testExtension_IgnoreNaturalPeriodBoundary() {
    // 自然时段边界不影响延伸
}
```

- [ ] **Step 2: 实现延伸逻辑**

- [ ] **Step 3: 提交**

---

## Phase 6: CONTINUOUS 模式（设计文档第五节）

### Task 14: 测试 - CONTINUOUS 模式气泡抽出

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/CompositeTimeTest.java`

- [ ] **Step 1: 添加气泡抽出测试**

```java
@Test
void testContinuous_BubbleExtraction() {
    // 免费时段：10:30-11:30
    // 片段1：08:00-10:30，相对位置 0-150分钟
    // 片段2：11:30-12:00，相对位置 210-240分钟
}
```

- [ ] **Step 2: 实现 CONTINUOUS 模式**

- [ ] **Step 3: 提交**

---

## Phase 7: 注册与整合

### Task 15: 注册规则

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/BillingRuleRegistry.java`

- [ ] **Step 1: 添加注册代码**
- [ ] **Step 2: 运行全部测试**
- [ ] **Step 3: 最终提交**

---

## 后续待办（Phase 2 特性）

1. **气泡型免费时间段支持**：气泡弹开模型（设计文档第六节）
2. **CONTINUE 模式支持**：规则状态结转
3. **长期简化计算**：O1 特性