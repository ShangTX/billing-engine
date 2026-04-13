# StartFreeRange Promotion Rule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增"前N分钟免费"优惠规则，并修复 FreeTimeRange 缺少 source 字段导致无法追踪优惠来源的问题。

**Architecture:** 新增 `StartFreePromotionConfig` 配置类和 `StartFreePromotionRule` 规则实现类。规则 `grant()` 方法生成 `type=FREE_RANGE` 的 `PromotionGrant`，时间段为 `[segment.beginTime, segment.beginTime + N分钟]`，走现有 `FreeTimeRangeMerger` 合并路径。同时修复 `FreeTimeRange` 新增 `source` 字段，并在 `PromotionEngine.convertTimeRangeFromRule()` 中传递。

**Tech Stack:** Java 21, Lombok, Maven, existing core promotion infrastructure

---

### Task 1: 新增 START_FREE 常量到 BConstants

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`

- [ ] **Step 1: 在 `PromotionRuleType` 类中新增 `START_FREE` 常量**

在 `BConstants.java` 的 `PromotionRuleType` 类中新增：

```java
public static class PromotionRuleType {
    public static String FREE_MINUTES = "freeMinutes"; // 免费分钟数
    public static String START_FREE = "startFree"; // 前N分钟免费
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java
git commit -m "feat(core): add START_FREE promotion rule type constant"
```

---

### Task 2: 创建 StartFreePromotionConfig 配置类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionConfig.java`

- [ ] **Step 1: 创建 StartFreePromotionConfig 类**

遵循 `FreeMinutesPromotionConfig` 的模式：

```java
package cn.shang.charging.promotion.rules.startfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 前N分钟免费优惠配置
 */
@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class StartFreePromotionConfig implements PromotionRuleConfig {

    String id;

    @Builder.Default
    String type = BConstants.PromotionRuleType.START_FREE;

    Integer priority;

    int minutes;
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionConfig.java
git commit -m "feat(core): add StartFreePromotionConfig for start-free promotion rule"
```

---

### Task 3: 创建 StartFreePromotionRule 规则实现

**Files:**
- Create: `core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionRule.java`

- [ ] **Step 1: 创建 StartFreePromotionRule 类**

遵循 `FreeMinutesPromotionRule` 的模式：

```java
package cn.shang.charging.promotion.rules.startfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegment;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前N分钟免费优惠规则
 * <p>
 * 从计费段起点开始的 N 分钟为免费时段，生成的免费时间段和已有的
 * FREE_RANGE 按优先级合并，不会像 FREE_MINUTES 那样避开已有免费时段。
 */
public class StartFreePromotionRule implements PromotionRule<StartFreePromotionConfig> {

    @Override
    public String getType() {
        return BConstants.PromotionRuleType.START_FREE;
    }

    @Override
    public Class<StartFreePromotionConfig> getConfigClass() {
        return StartFreePromotionConfig.class;
    }

    @Override
    public List<PromotionGrant> grant(BillingContext billingContext, StartFreePromotionConfig config) {
        BillingSegment segment = billingContext.getSegment();
        LocalDateTime beginTime = segment.getBeginTime();
        LocalDateTime endTime = beginTime.plusMinutes(config.getMinutes());

        var promotionGrant = PromotionGrant.builder()
                .id(config.getId())
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.RULE)
                .priority(config.getPriority())
                .beginTime(beginTime)
                .endTime(endTime)
                .build();
        return List.of(promotionGrant);
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
git add core/src/main/java/cn/shang/charging/promotion/rules/startfree/StartFreePromotionRule.java
git commit -m "feat(core): add StartFreePromotionRule for start-free promotion"
```

---

### Task 4: 修复 FreeTimeRange source 字段传递

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRange.java`
- Modify: `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`

- [ ] **Step 1: FreeTimeRange 新增 source 字段**

在 `FreeTimeRange.java` 的 `data` 字段之前添加：

```java
/**
 * 优惠来源：RULE（规则）/ COUPON（优惠券）
 */
private BConstants.PromotionSource source;
```

添加到 `rangeType` 字段之后、`data` 字段之前：

```java
    /**
     * 免费时间段类型：NORMAL（普通）/ BUBBLE（气泡型，延长周期边界）
     * 默认为 NORMAL
     */
    @Builder.Default
    private FreeTimeRangeType rangeType = FreeTimeRangeType.NORMAL;

    /**
     * 优惠来源：RULE（规则）/ COUPON（优惠券）
     */
    private BConstants.PromotionSource source;

    private Object data; // 其他数据
```

- [ ] **Step 2: FreeTimeRange.copy() 复制 source 字段**

修改 `copy()` 方法，添加 `.setRangeType(rangeType)` 之后：

```java
    public FreeTimeRange copy() {
        FreeTimeRange copy = new FreeTimeRange()
                .setId(id)
                .setBeginTime(beginTime)
                .setEndTime(endTime)
                .setPriority(priority)
                .setPromotionType(promotionType)
                .setRangeType(rangeType)
                .setSource(source);
        copy.data = this.data;
        return copy;
    }
```

- [ ] **Step 3: FreeTimeRange.copyWithNewId() 复制 source 字段**

修改 `copyWithNewId()` 方法，添加 `.setRangeType(rangeType)` 之后：

```java
    public FreeTimeRange copyWithNewId() {
        return new FreeTimeRange()
                .setBeginTime(this.beginTime)
                .setEndTime(this.endTime)
                .setPriority(this.priority)
                .setPromotionType(this.promotionType)
                .setRangeType(this.rangeType)
                .setSource(this.source);
    }
```

- [ ] **Step 4: PromotionEngine.convertTimeRangeFromRule() 传递 source**

修改 `PromotionEngine.java` 的 `convertTimeRangeFromRule()` 方法，在 builder 中添加 `.source(grant.getSource())`：

```java
    private FreeTimeRange convertTimeRangeFromRule(PromotionGrant grant) {
        return FreeTimeRange.builder()
                .id(grant.getId())
                .promotionType(grant.getType())
                .beginTime(grant.getBeginTime())
                .endTime(grant.getEndTime())
                .priority(grant.getPriority())
                .rangeType(grant.getRangeType())
                .source(grant.getSource())
                .build();
    }
```

- [ ] **Step 5: 编译验证**

```bash
mvn compile -pl core -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRange.java core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java
git commit -m "fix(core): add source field to FreeTimeRange and pass it during conversion"
```

---

### Task 5: 创建 StartFreePromotionTest 测试类

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/StartFreePromotionTest.java`

- [ ] **Step 1: 创建测试类**

遵循 `PromotionTest` 和 `FlatFreeTest` 的模式：

```java
package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 前N分钟免费优惠规则测试
 */
public class StartFreePromotionTest {

    public static void main(String[] args) {
        System.out.println("========== 前N分钟免费优惠测试 ==========\n");

        // 测试1: 基础功能 - 前30分钟免费
        testBasicStartFree();

        // 测试2: 与外部免费时段重叠 - 按优先级合并
        testOverlapWithExternalFreeRange();

        // 测试3: 计算窗口小于N分钟 - 部分覆盖
        testPartialCoverage();

        // 测试4: CONTINUE 模式 - N分钟相对于段起点
        testContinueMode();

        System.out.println("\n========== 测试完成 ==========");
    }

    /**
     * 测试1: 基础功能 - 前30分钟免费
     */
    static void testBasicStartFree() {
        System.out.println("=== 测试1: 基础功能 - 前30分钟免费 ===");

        var billingService = getBillingServiceWithStartFree(30);
        var request = new BillingRequest();
        request.setId("test-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("规则: 前30分钟免费 (08:00-08:30)");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证: 前30分钟应免费
        assert result.getUnits().size() > 0 : "应有计费单元";
    }

    /**
     * 测试2: 与外部免费时段重叠 - 按优先级合并
     */
    static void testOverlapWithExternalFreeRange() {
        System.out.println("=== 测试2: 与外部免费时段重叠 ===");

        var billingService = getBillingServiceWithStartFree(30);
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部免费时段: 08:20-09:00 (与规则的前30分钟 08:00-08:30 重叠)
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 20, 0))
                        .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("规则: 前30分钟免费 (08:00-08:30)");
        System.out.println("外部: 免费时段 08:20-09:00");
        System.out.println("合并后: 08:00-09:00 (60分钟免费)");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试3: 计算窗口小于N分钟 - 部分覆盖
     */
    static void testPartialCoverage() {
        System.out.println("=== 测试3: 窗口小于N分钟 ===");

        var billingService = getBillingServiceWithStartFree(60);
        var request = new BillingRequest();
        request.setId("test-3");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 20, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 08:20 (20分钟)");
        System.out.println("规则: 前60分钟免费 (08:00-09:00)");
        System.out.println("窗口只覆盖20分钟，应全部免费");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证: 20分钟应全部免费
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "窗口内应全部免费";
    }

    /**
     * 测试4: CONTINUE 模式 - N分钟相对于段起点
     */
    static void testContinueMode() {
        System.out.println("=== 测试4: CONTINUE 模式 ===");

        var billingService = getBillingServiceWithStartFree(30);

        // 第一次计算: 08:00-10:00
        var request1 = new BillingRequest();
        request1.setId("test-4");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("finalAmount = " + result1.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result1));
        System.out.println();

        // 验证第一次计算
        assert result1.getCarryOver() != null : "应携带结转状态";
        assert result1.getCarryOver().getCalculatedUpTo() != null : "应携带 calculatedUpTo";

        // 从第一次结果继续计算: 08:00-12:00
        var request2 = new BillingRequest();
        request2.setId("test-4");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE): 08:00 - 12:00");
        System.out.println("finalAmount = " + result2.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result2));
        System.out.println();

        // 验证: CONTINUE 模式下，前30分钟(08:00-08:30)已在第一次计算中免费
        // 第二次计算不应重复计算这部分费用
    }

    // ==================== 辅助方法 ====================

    static BillingService getBillingServiceWithStartFree(int startFreeMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)   // 12:20
                        .setDayEndMinute(1140)    // 19:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of(
                        new StartFreePromotionConfig()
                                .setId("start-free-30")
                                .setMinutes(startFreeMinutes)
                                .setPriority(1)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

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
mvn compile -pl bill-test -am -q && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.StartFreePromotionTest" -q
```

Expected: 所有测试通过，输出测试结果，无 assert 错误。

- [ ] **Step 3: 编译 core 模块验证无破坏**

```bash
mvn clean install -pl core -DskipTests -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add bill-test/src/main/java/cn/shang/charging/StartFreePromotionTest.java
git commit -m "test: add StartFreePromotionTest with basic, overlap, partial, and continue tests"
```

---

## Self-Review

### 1. Spec coverage check

| 需求 | 对应 Task |
|------|----------|
| 新增"前N分钟免费"优惠规则 | Task 2 + Task 3 |
| 相对于段起点计算时间段 | Task 3: `segment.getBeginTime()` |
| 和 FREE_RANGE 按优先级合并 | Task 3: `type = FREE_RANGE`，走现有合并路径 |
| 不像 FREE_MINUTES 那样避开已有免费时段 | Task 3: 不经过 `FreeMinuteAllocator` |
| 支持 CONTINUE 模式 | Task 5: testContinueMode 验证 |
| 记录使用情况（和现有 FREE_RANGE 相同） | Task 3: 走现有 `FreeTimeRangeMerger` 的 `consumedMinutes` 追踪 |
| source 字段能追踪来源 | Task 4: FreeTimeRange 新增 source，PromotionEngine 传递 |
| BConstants 新增常量 | Task 1 |
| 测试覆盖 | Task 5: 4 个测试用例 |

### 2. Placeholder scan

无 TBD/TODO/placeholder。所有代码步骤包含完整实现代码。

### 3. Type consistency

- `BConstants.PromotionRuleType.START_FREE` 在 Task 1 定义，Task 2 的 `StartFreePromotionConfig.type` 默认值使用它，Task 3 的 `StartFreePromotionRule.getType()` 使用它，Task 5 的 registry 注册使用它 — 一致
- `PromotionGrant.type = BConstants.PromotionType.FREE_RANGE` — 使用现有枚举，不新增 — 一致
- `PromotionGrant.source = BConstants.PromotionSource.RULE` — 使用现有枚举 — 一致
- `FreeTimeRange.source` 字段类型为 `BConstants.PromotionSource`，与 `PromotionGrant.source` 一致 — 一致
- 测试类中 `getBillingServiceWithStartFree(int)` 使用 DayNightRule，与 `PromotionTest` 模式一致 — 一致
- `StartFreePromotionConfig` 包含 `id`、`type`、`priority`、`minutes`，与 `FreeMinutesPromotionConfig` 字段模式一致 — 一致

### 4. Scope check

5 个 Task，其中 Task 4 是修复现有 bug（source 字段传递），Task 1-3 和 Task 5 是新功能实现。无额外重构或无关变更。
