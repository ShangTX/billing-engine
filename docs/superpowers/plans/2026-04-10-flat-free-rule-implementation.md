# FlatFreeRule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个统一免费计费规则，返回一个覆盖整个窗口的免费计费单元，费用始终为 0。

**Architecture:** 新增 `FlatFreeConfig` 配置类和 `FlatFreeRule` 规则实现类，注册到 `BillingRuleRegistry` 后由 `BillingCalculator` 通过 `ruleConfig.getType()` 自动路由。规则忽略所有优惠，返回单个 `BillingUnit` 且 `chargedAmount = 0`。

**Tech Stack:** Java 21, Lombok, Maven, existing core billing engine infrastructure

---

### Task 1: 新增 FLAT_FREE 常量到 BConstants

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`

- [ ] **Step 1: 在 `ChargeRuleType` 类中新增 `FLAT_FREE` 常量**

在 `BConstants.java` 的 `ChargeRuleType` 类中新增：

```java
public static String FLAT_FREE = "flatFree"; // 统一免费计费
```

放在现有 `COMPOSITE_TIME` 之后。

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java
git commit -m "feat(core): add FLAT_FREE charge rule type constant"
```

---

### Task 2: 创建 FlatFreeConfig 配置类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeConfig.java`

- [ ] **Step 1: 创建 FlatFreeConfig 类**

遵循现有配置类的模式（参考 `DayNightConfig`）：

```java
package cn.shang.charging.charge.rules.flatfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class FlatFreeConfig implements RuleConfig {

    String id;

    @Builder.Default
    String type = BConstants.ChargeRuleType.FLAT_FREE;
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeConfig.java
git commit -m "feat(core): add FlatFreeConfig for flat-free billing rule"
```

---

### Task 3: 创建 FlatFreeRule 规则实现

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeRule.java`

- [ ] **Step 1: 创建 FlatFreeRule 类**

不继承 `AbstractTimeBasedRule`，直接实现 `BillingRule<FlatFreeConfig>` 接口：

```java
package cn.shang.charging.charge.rules.flatfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一免费计费规则
 * <p>
 * 忽略所有优惠，返回一个覆盖整个计算窗口的免费计费单元。
 * 无论 CONTINUOUS 还是 UNIT_BASED 模式，都只返回一个免费单元。
 */
public class FlatFreeRule implements BillingRule<FlatFreeConfig> {

    private static final String FREE_PROMOTION_ID = "FLAT_FREE";

    @Override
    public Class<FlatFreeConfig> configClass() {
        return FlatFreeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          FlatFreeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

        int durationMinutes = (int) Duration.between(calcBegin, calcEnd).toMinutes();

        BillingUnit unit = BillingUnit.builder()
                .beginTime(calcBegin)
                .endTime(calcEnd)
                .durationMinutes(durationMinutes)
                .unitPrice(BigDecimal.ZERO)
                .originalAmount(BigDecimal.ZERO)
                .chargedAmount(BigDecimal.ZERO)
                .free(true)
                .freePromotionId(FREE_PROMOTION_ID)
                .build();

        // 保存结转状态，使调用方能识别这是 CONTINUE 模式的计算结果
        Map<String, Object> ruleOutputState = Map.of(
                "flatFree", Map.of("calculatedUpTo", calcEnd)
        );

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(BigDecimal.ZERO)
                .billingUnits(List.of(unit))
                .promotionUsages(List.of())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(calcBegin)
                .feeEffectiveEnd(calcEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeRule.java
git commit -m "feat(core): add FlatFreeRule for flat-free billing"
```

---

### Task 4: 创建 FlatFreeTest 测试类

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/FlatFreeTest.java`

- [ ] **Step 1: 创建测试类**

遵循 `DayNightTest` 的模式（`public class` + `static void main` + 匿名 `BillingConfigResolver`）：

```java
package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.flatfree.FlatFreeConfig;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.settlement.ResultAssembler;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一免费计费规则测试
 */
public class FlatFreeTest {

    public static void main(String[] args) {
        System.out.println("========== 统一免费计费规则测试 ==========\n");

        // 测试1: CONTINUOUS 模式 - 免费
        testContinuousMode();

        // 测试2: UNIT_BASED 模式 - 免费
        testUnitBasedMode();

        // 测试3: CONTINUE 模式 - 从上次结果继续
        testContinueMode();

        // 测试4: 带外部优惠但仍返回免费（优惠被忽略）
        testWithPromotionsIgnored();

        System.out.println("\n========== 测试完成 ==========");
    }

    /**
     * 测试1: CONTINUOUS 模式
     */
    static void testContinuousMode() {
        System.out.println("=== 测试1: CONTINUOUS 模式 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("模式: CONTINUOUS");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元";
        assert result.getUnits().get(0).isFree() : "单元应标记为免费";
        assert result.getUnits().get(0).getFreePromotionId().equals("FLAT_FREE") : "freePromotionId 应为 FLAT_FREE";
    }

    /**
     * 测试2: UNIT_BASED 模式
     */
    static void testUnitBasedMode() {
        System.out.println("=== 测试2: UNIT_BASED 模式 ===");

        var billingService = getBillingService(BConstants.BillingMode.UNIT_BASED);
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("模式: UNIT_BASED");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元（两种模式行为一致）";
        assert result.getUnits().get(0).isFree() : "单元应标记为免费";
    }

    /**
     * 测试3: CONTINUE 模式
     */
    static void testContinueMode() {
        System.out.println("=== 测试3: CONTINUE 模式 ===");

        // 第一次计算
        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request1 = new BillingRequest();
        request1.setId("test-3");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("free-scheme");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00");
        System.out.println("finalAmount = " + result1.getFinalAmount());
        System.out.println("carryOver.calculatedUpTo = " + result1.getCarryOver().getCalculatedUpTo());
        System.out.println();

        // 验证第一次计算
        assert result1.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "第一次金额应为 0";
        assert result1.getCarryOver() != null : "应携带结转状态";
        assert result1.getCarryOver().getCalculatedUpTo() != null : "应携带 calculatedUpTo";
        assert result1.getCarryOver().getSegments() != null : "应携带分段状态";

        // 从第一次结果继续计算
        var request2 = new BillingRequest();
        request2.setId("test-3");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("free-scheme");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE): 08:00 - 14:00");
        System.out.println("finalAmount = " + result2.getFinalAmount());
        System.out.println("carryOver.calculatedUpTo = " + result2.getCarryOver().getCalculatedUpTo());
        System.out.println(JacksonUtils.toJsonString(result2));
        System.out.println();

        // 验证第二次计算
        assert result2.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "第二次金额应为 0";
        assert result2.getCarryOver().getCalculatedUpTo() != null : "应携带新的 calculatedUpTo";
    }

    /**
     * 测试4: 带外部优惠但仍返回免费（优惠被忽略）
     */
    static void testWithPromotionsIgnored() {
        System.out.println("=== 测试4: 外部优惠被忽略 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-4");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");

        // 添加外部免费时段优惠（应被规则忽略）
        request.setExternalPromotions(List.of(
                new PromotionGrant()
                        .setId("external-free-range")
                        .setType(BConstants.PromotionType.FREE_RANGE)
                        .setFreeTimeRanges(List.of(
                                new FreeTimeRange(
                                        LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0),
                                        LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0),
                                        "free-range-1"
                                )
                        ))
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("外部优惠: 09:00-11:00 免费时段");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元（不因外部优惠切分）";
    }

    // ==================== 辅助方法 ====================

    static BillingService getBillingService(BConstants.BillingMode billingMode) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return billingMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return FlatFreeConfig.builder()
                        .id("flat-free-001")
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                // 返回免费分钟规则配置，但 FlatFreeRule 会忽略它
                return List.of(
                        new FreeMinutesPromotionConfig()
                                .setId("rule-free-min")
                                .setPriority(1)
                                .setMinutes(30)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
```

- [ ] **Step 2: 编译 bill-test 模块并运行测试**

```bash
mvn compile -pl bill-test -am -q && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.FlatFreeTest" -q
```

Expected: 所有测试通过，输出测试结果，无 assert 错误。

- [ ] **Step 3: 编译 core 模块验证无破坏**

```bash
mvn clean install -pl core -DskipTests -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add bill-test/src/main/java/cn/shang/charging/FlatFreeTest.java
git commit -m "test: add FlatFreeTest with CONTINUOUS, UNIT_BASED, CONTINUE, and promotion-ignore tests"
```

---

## Self-Review

### 1. Spec coverage check

| 需求 | 对应 Task |
|------|----------|
| 忽略所有优惠 | Task 3: `calculate()` 不使用 `promotionAggregate`，`promotionUsages` 为空列表 |
| 返回免费计费单元 | Task 3: 返回单个 `BillingUnit`，所有金额字段为 0 |
| 费用始终为 0 | Task 3: `chargedAmount = BigDecimal.ZERO` |
| 支持 CONTINUOUS 和 UNIT_BASED | Task 3: `supportedModes()` 返回两个模式，两种模式返回相同结果 |
| CONTINUE 模式 ruleOutputState | Task 3: 保存 `{"flatFree": {"calculatedUpTo": calcEnd}}` |
| BConstants 新增常量 | Task 1 |
| FlatFreeConfig | Task 2 |
| FlatFreeRule | Task 3 |
| 测试覆盖 | Task 4: 4 个测试用例 |

### 2. Placeholder scan

无 TBD/TODO/placeholder。所有代码步骤包含完整实现代码。

### 3. Type consistency

- `BConstants.ChargeRuleType.FLAT_FREE` 在 Task 1 定义，Task 2 的 `FlatFreeConfig.type` 默认值使用它，Task 4 的 registry 注册使用它 — 一致
- `freePromotionId = "FLAT_FREE"` 使用常量 `FREE_PROMOTION_ID` — 一致
- `BillingSegmentResult` 和 `BillingUnit` 字段与现有代码一致 — 已验证
- `Map.of()` 返回不可变 Map，与现有 `AbstractTimeBasedRule.buildRuleOutputState()` 用 `HashMap` 不同但功能等效

### 4. Scope check

4 个 Task，聚焦于单一功能。无额外重构或无关变更。
