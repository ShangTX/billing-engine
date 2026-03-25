# 气泡型免费时间段实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现气泡型免费时间段特性，该类型免费时段会延长计费周期边界

**Architecture:** 在 FreeTimeRange 增加 rangeType 字段区分普通型和气泡型；在规则计算的 calculateContinuous 方法中，根据使用的气泡型时段延长 cycleBoundary

**Tech Stack:** Java 21, Lombok, Maven

**Design Doc:** `docs/superpowers/specs/2026-03-24-bubble-free-time-range-design.md`

---

## 文件结构

| 文件 | 改动类型 | 职责 |
|------|---------|------|
| `core/.../pojo/FreeTimeRangeType.java` | 新增 | 枚举：NORMAL / BUBBLE |
| `core/.../pojo/FreeTimeRange.java` | 修改 | 增加 rangeType 字段 |
| `core/.../promotion/PromotionEngine.java` | 修改 | buildPromotionCarryOver 保留 rangeType |
| `core/.../rules/AbstractTimeBasedRule.java` | 修改 | 新增 calculateBubbleExtension 方法 |
| `core/.../rules/daynight/DayNightRule.java` | 修改 | calculateContinuous 集成气泡延长 |
| `core/.../rules/relativetime/RelativeTimeRule.java` | 修改 | calculateContinuous 集成气泡延长 |
| `core/.../rules/compositetime/CompositeTimeRule.java` | 修改 | calculateContinuous 集成气泡延长 |
| `bill-test/.../BubbleFreeTimeRangeTest.java` | 新增 | 测试用例 |

---

### Task 1: 新增 FreeTimeRangeType 枚举

**Files:**
- Create: `core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRangeType.java`

- [ ] **Step 1: 创建枚举文件**

```java
package cn.shang.charging.promotion.pojo;

/**
 * 免费时间段类型
 */
public enum FreeTimeRangeType {
    /**
     * 普通免费时间段
     * 不影响周期边界，仅标记时间免费
     */
    NORMAL,

    /**
     * 气泡型免费时间段
     * 延长计费周期边界，后续相对时间段边界整体后移
     */
    BUBBLE
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRangeType.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 FreeTimeRangeType 枚举"
```

---

### Task 2: FreeTimeRange 增加 rangeType 字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRange.java`

- [ ] **Step 1: 添加 rangeType 字段**

在 `FreeTimeRange.java` 中：

1. 添加导入：
```java
// 在文件顶部 import 区域添加
// 无需额外导入，FreeTimeRangeType 在同包
```

2. 添加字段（在第 28 行 `promotionType` 后）：
```java
    private BConstants.PromotionType promotionType;

    /**
     * 免费时间段类型：NORMAL（普通）/ BUBBLE（气泡型，延长周期边界）
     * 默认为 NORMAL
     */
    private FreeTimeRangeType rangeType;
```

3. 修改 `copy()` 方法（第 60-69 行），添加 rangeType 复制：
```java
    public FreeTimeRange copy() {
        FreeTimeRange copy = new FreeTimeRange()
                .setId(id)
                .setBeginTime(beginTime)
                .setEndTime(endTime)
                .setPriority(priority)
                .setPromotionType(promotionType)
                .setRangeType(rangeType);
        copy.data = this.data;
        return copy;
    }
```

4. 修改 `copyWithNewId()` 方法（第 71-77 行），添加 rangeType 复制：
```java
    public FreeTimeRange copyWithNewId() {
        return new FreeTimeRange()
                .setBeginTime(this.beginTime)
                .setEndTime(this.endTime)
                .setPriority(this.priority)
                .setPromotionType(this.promotionType)
                .setRangeType(this.rangeType);
    }
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRange.java
git commit -m "[claude-code|glm-5|superpowers] feat: FreeTimeRange 增加 rangeType 字段"
```

---

### Task 3: PromotionEngine.buildPromotionCarryOver 保留 rangeType

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java:228-242`

- [ ] **Step 1: 修改 buildPromotionCarryOver 方法**

找到 `buildPromotionCarryOver` 方法中构建 `usedFreeRanges` 的代码块（约 228-242 行），修改为保留 rangeType：

```java
        // 记录部分使用的免费时段（在当前窗口内实际生效的部分）
        List<FreeTimeRange> usedFreeRanges = new ArrayList<>();
        for (FreeTimeRange range : finalFreeRanges) {
            // 记录所有在当前计算窗口内生效的免费时段
            if (range.getPromotionType() == BConstants.PromotionType.FREE_RANGE) {
                // 只要免费时段在计算窗口内（endTime <= calculationEndTime），就记录
                if (!range.getEndTime().isAfter(calculationEndTime)) {
                    usedFreeRanges.add(FreeTimeRange.builder()
                            .id(range.getId())
                            .beginTime(range.getBeginTime())
                            .endTime(range.getEndTime())
                            .promotionType(range.getPromotionType())
                            .rangeType(range.getRangeType()) // 保留类型信息
                            .build());
                }
            }
        }
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java
git commit -m "[claude-code|glm-5|superpowers] fix: PromotionEngine 保留 freeTimeRange 的 rangeType"
```

---

### Task 4: AbstractTimeBasedRule 新增气泡延长计算方法

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`

- [ ] **Step 1: 添加 calculateBubbleExtension 方法**

在 `AbstractTimeBasedRule.java` 中，在 `getCycleMinutes()` 方法后（约 50 行）添加：

```java
    /**
     * 计算气泡型免费时段的总延长时长（分钟）
     * @param freeTimeRanges 免费时段列表
     * @param calcBegin 计算窗口起点
     * @param calcEnd 计算窗口终点
     * @return 气泡延长总分钟数
     */
    protected int calculateBubbleExtension(List<FreeTimeRange> freeTimeRanges,
                                           LocalDateTime calcBegin,
                                           LocalDateTime calcEnd) {
        if (freeTimeRanges == null || freeTimeRanges.isEmpty()) {
            return 0;
        }

        int totalExtension = 0;
        for (FreeTimeRange range : freeTimeRanges) {
            // 只处理气泡型免费时段
            if (range.getRangeType() == FreeTimeRangeType.BUBBLE) {
                // 计算该气泡在计算窗口内的实际使用部分
                LocalDateTime effectiveBegin = range.getBeginTime().isBefore(calcBegin)
                        ? calcBegin : range.getBeginTime();
                LocalDateTime effectiveEnd = range.getEndTime().isAfter(calcEnd)
                        ? calcEnd : range.getEndTime();

                // 只有在窗口内有交集才计算
                if (effectiveBegin.isBefore(effectiveEnd)) {
                    totalExtension += (int) Duration.between(effectiveBegin, effectiveEnd).toMinutes();
                }
            }
        }
        return totalExtension;
    }
```

- [ ] **Step 2: 添加导入**

在文件顶部添加：
```java
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: AbstractTimeBasedRule 新增 calculateBubbleExtension 方法"
```

---

### Task 5: DayNightRule.calculateContinuous 集成气泡延长

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 在 calculateContinuous 方法中计算气泡延长**

找到 `calculateContinuous` 方法中更新 `state.setCycleBoundary` 的位置（约 900 行和 912 行、922 行），需要：

1. 在计算完所有计费单元后、设置 cycleBoundary 之前，计算气泡延长

在 `// 如果未使用简化，使用原有逻辑` 代码块末尾，修改 cycleBoundary 设置：

找到约 896-901 行：
```java
            // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
            if (!cycles.isEmpty()) {
                state.setCycleAccumulated(lastCycleAccumulated);
                state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24));
            }
```

修改为：
```java
            // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
            if (!cycles.isEmpty()) {
                state.setCycleAccumulated(lastCycleAccumulated);
                state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                // 计算气泡延长
                int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
            }
```

2. 同样修改简化计算模式下的 cycleBoundary 设置（约 911-912 行和 921-922 行）

找到：
```java
                    state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24));
```

在这两处之前添加气泡延长计算：
```java
                    int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                    state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: DayNightRule 集成气泡型免费时段延长"
```

---

### Task 6: RelativeTimeRule.calculateContinuous 集成气泡延长

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 找到 cycleBoundary 设置位置并修改**

在 `calculateContinuous` 方法中找到 `state.setCycleBoundary` 的调用位置（约 1031 行和 1048 行、1053 行），添加气泡延长计算：

```java
int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_CYCLE).plusMinutes(bubbleExtension));
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: RelativeTimeRule 集成气泡型免费时段延长"
```

---

### Task 7: CompositeTimeRule.calculateContinuous 集成气泡延长

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 找到 cycleBoundary 设置位置并修改**

在 `calculateContinuous` 方法中找到 `state.setCycleBoundary` 的调用位置（约 628 行和 644 行、649 行），添加气泡延长计算：

```java
int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_DAY).plusMinutes(bubbleExtension));
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] feat: CompositeTimeRule 集成气泡型免费时段延长"
```

---

### Task 8: 编写测试用例

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/BubbleFreeTimeRangeTest.java`

- [ ] **Step 1: 创建测试文件**

```java
package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 气泡型免费时间段测试
 */
public class BubbleFreeTimeRangeTest {

    private static BillingService billingService;

    public static void main(String[] args) {
        System.out.println("========== 气泡型免费时间段测试 ==========\n");

        initBillingService();

        // 测试1: 单气泡延长周期边界
        testSingleBubbleExtension();

        // 测试2: 跨计算段气泡累积延长
        testCrossCalculationBubbleExtension();

        // 测试3: 气泡型与普通型混合
        testMixedBubbleAndNormal();

        // 测试4: 多气泡场景
        testMultipleBubbles();

        System.out.println("\n========== 所有测试完成 ==========");
    }

    static void initBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)   // 08:00
                        .setDayEndMinute(1200)    // 20:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return List.of();
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return 7;
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
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        billingService = new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    /**
     * 测试1: 单气泡延长周期边界
     *
     * 气泡型免费时段：11:00-12:00（60分钟）
     * 计费起点：08:00
     * 预期：周期边界从次日 08:00 延长到次日 09:00
     */
    static void testSingleBubbleExtension() {
        System.out.println("=== 测试1: 单气泡延长周期边界 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 气泡型免费时段 11:00-12:00
        List<PromotionGrant> promotions = new ArrayList<>();
        promotions.add(PromotionGrant.builder()
                .id("bubble-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("气泡型免费时段: 11:00-12:00（60分钟）");
        System.out.println("预期周期边界: 次日 09:00（延长60分钟）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());

        // 检查 cycleBoundary
        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        var cycleBoundary = dayNightState.get("cycleBoundary");
        System.out.println("输出 cycleBoundary: " + cycleBoundary);

        // 验证：周期边界应该是次日 09:00
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 9, 0, 0);
        boolean passed = cycleBoundary.toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试2: 跨计算段气泡累积延长
     *
     * 气泡型免费时段：11:00-13:00（120分钟）
     * 第一次计算：08:00-12:00，使用 11:00-12:00（60分钟）
     * 第二次计算：12:00-18:00，使用 12:00-13:00（60分钟）
     * 预期：第一次延长到次日 09:00，第二次延长到次日 10:00
     */
    static void testCrossCalculationBubbleExtension() {
        System.out.println("=== 测试2: 跨计算段气泡累积延长 ===\n");

        // 第一次计算
        var request1 = new BillingRequest();
        request1.setId("test-bubble-2-1");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");

        List<PromotionGrant> promotions1 = new ArrayList<>();
        promotions1.add(PromotionGrant.builder()
                .id("bubble-120")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request1.setExternalPromotions(promotions1);

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算 (08:00-12:00):");
        System.out.println("  气泡型免费时段: 11:00-13:00");
        System.out.println("  本次使用: 11:00-12:00（60分钟）");

        var ruleState1 = result1.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState1 = (java.util.Map<String, Object>) ruleState1.get("dayNight");
        System.out.println("  输出 cycleBoundary: " + dayNightState1.get("cycleBoundary"));

        // 第二次计算（CONTINUE）
        var request2 = new BillingRequest();
        request2.setId("test-bubble-2-2");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setPreviousCarryOver(result1.getCarryOver());
        request2.setExternalPromotions(promotions1);

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算 (CONTINUE, 12:00-18:00):");
        System.out.println("  本次使用: 12:00-13:00（60分钟）");

        var ruleState2 = result2.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState2 = (java.util.Map<String, Object>) ruleState2.get("dayNight");
        System.out.println("  输出 cycleBoundary: " + dayNightState2.get("cycleBoundary"));

        // 验证：第二次周期边界应该是次日 10:00
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 10, 0, 0);
        boolean passed = dayNightState2.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试3: 气泡型与普通型混合
     */
    static void testMixedBubbleAndNormal() {
        System.out.println("=== 测试3: 气泡型与普通型混合 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-3");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 16, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        List<PromotionGrant> promotions = new ArrayList<>();
        // 普通免费时段：09:00-10:00（不影响周期）
        promotions.add(PromotionGrant.builder()
                .id("normal-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .rangeType(FreeTimeRangeType.NORMAL)
                .build());
        // 气泡型免费时段：13:00-14:00（延长周期60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 16:00");
        System.out.println("普通免费时段: 09:00-10:00（不影响周期）");
        System.out.println("气泡型免费时段: 13:00-14:00（延长周期60分钟）");
        System.out.println();

        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        System.out.println("输出 cycleBoundary: " + dayNightState.get("cycleBoundary"));

        // 验证：周期边界应该是次日 09:00（只有气泡延长60分钟）
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 9, 0, 0);
        boolean passed = dayNightState.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试4: 多气泡场景
     */
    static void testMultipleBubbles() {
        System.out.println("=== 测试4: 多气泡场景 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-4");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        List<PromotionGrant> promotions = new ArrayList<>();
        // 气泡1：10:00-11:00（60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        // 气泡2：14:00-15:00（60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-2")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 15, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 18:00");
        System.out.println("气泡1: 10:00-11:00（60分钟）");
        System.out.println("气泡2: 14:00-15:00（60分钟）");
        System.out.println("预期周期延长: 120分钟");
        System.out.println();

        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        System.out.println("输出 cycleBoundary: " + dayNightState.get("cycleBoundary"));

        // 验证：周期边界应该是次日 10:00（延长120分钟）
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 10, 0, 0);
        boolean passed = dayNightState.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl bill-test -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行测试**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.BubbleFreeTimeRangeTest" -q`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add bill-test/src/main/java/cn/shang/charging/BubbleFreeTimeRangeTest.java
git commit -m "[claude-code|glm-5|superpowers] test: 添加气泡型免费时间段测试用例"
```

---

### Task 9: 更新待实现特性清单

**Files:**
- Modify: `docs/superpowers/specs/2026-03-18-pending-features-overview.md`

- [ ] **Step 1: 更新 P3 状态为已完成**

将 P3 从待实现移到已完成：

```markdown
### P3. 免费时间段（气泡型） ✅
- **状态**: 已完成 (2026-03-24)
- **特性**: 气泡型免费时段延长计费周期，后续边界后移
- **文档**: `docs/superpowers/specs/2026-03-24-bubble-free-time-range-design.md`
```

- [ ] **Step 2: 提交**

```bash
git add docs/superpowers/specs/2026-03-18-pending-features-overview.md
git commit -m "[claude-code|glm-5|superpowers] docs: 更新 P3 气泡型免费时间段为已完成"
```

---

## 验证清单

- [ ] 所有模块编译通过
- [ ] 测试用例运行通过
- [ ] 现有 EdgeCaseTest 测试通过
- [ ] 现有 PromotionTest 测试通过
- [ ] 文档已更新