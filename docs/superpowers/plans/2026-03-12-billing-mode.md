# 计费模式功能实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现计费模式功能，支持 CONTINUOUS 和 UNIT_BASED 两种模式，规则可声明支持的模式并在计算时校验。

**Architecture:** 在 BConstants 中重命名 BillingMode 为 ContinueMode，新增 BillingMode 枚举；将 RuleResolver 重命名为 BillingConfigResolver 并新增 resolveBillingMode 方法；BillingRule 接口新增 supportedModes 方法；BillingCalculator 中校验模式支持。

**Tech Stack:** Java 17+, Lombok, Maven

---

## Chunk 1: 枚举变更

### Task 1: 修改 BConstants 枚举

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`

- [ ] **Step 1: 重命名 BillingMode 为 ContinueMode**

将原 `BillingMode` 枚举重命名为 `ContinueMode`：

```java
package cn.shang.charging.billing.pojo;

/**
 * 计费常量
 */
public class BConstants {

    /**
     * 继续模式（是否从上次结果继续计算）
     */
    public enum ContinueMode {
        FROM_SCRATCH, // 从开始时间计算
        CONTINUE      // 从上一次的结果继续计算
    }

    /**
     * 计费模式（计费单位如何划分）
     */
    public enum BillingMode {
        CONTINUOUS, // 连续时间计费模式
        UNIT_BASED  // 计费单位模式
    }

    /**
     * 优惠模式
     */
    public enum PromotionType {
        AMOUNT, // 金额
        DISCOUNT, // 折扣
        FREE_RANGE, // 免费时间段
        FREE_MINUTES, // 免费分钟数
    }

    /**
     * 优惠来源
     */
    public enum PromotionSource {
        RULE, // 规则
        COUPON // 优惠券
    }

    /**
     * 分段计算方式
     */
    public enum SegmentCalculationMode {
        SINGLE, // 仅单个分段
        SEGMENT_LOCAL,     // 分段独立起算
        GLOBAL_ORIGIN      // 全局起算 + 分段截取
    }

    /**
     * 计费规则类型
     */
    public static class ChargeRuleType {
        public static String DAY_NIGHT = "dayNight"; // 日夜分时段计费
        public static String TIMES = "times"; // 按次数
        public static String NATURAL_TIME = "naturalTime"; // 按自然时间段计费
        public static String RELATIVE_TIME = "relativeTime"; // 按相对时间段计费
        public static String NR_TIME_MIX = "nrTimeMix"; // 按自然时间、相对时间混合时间段计费
    }

    public static class PromotionRuleType {
        public static String FREE_MINUTES = "freeMinutes"; // 免费分钟数
        public static String FREE_TIME_RANGE = "freeTimeRange"; // 免费时间段
    }

}
```

- [ ] **Step 2: 更新 BillingContext 中的引用和注释**

修改 `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`：

```java
package cn.shang.charging.billing.pojo;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingContext {
    // 计费id
    private String id;

    // 开始结束时间
    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    /**
     * 继续模式：是否从上次结果继续计算
     */
    private BConstants.ContinueMode continueMode;

    /**
     * 计费模式：计费单位如何划分
     */
    private BConstants.BillingMode billingMode;

    /**
     * 分段
     */
    private BillingSegment segment;

    /**
     * 计算窗口
     */
    private CalculationWindow window;

    /**
     * 外部优惠
     */
    private List<PromotionGrant> externalPromotions;

    /**
     * 已计算进度（仅继续模式使用）
     */
    private BillingProgress progress;

    /**
     * 优惠规则
     */
    private List<PromotionRuleConfig> promotionRules;

    /**
     * 计费规则
     */
    private RuleConfig chargingRule;

}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java
git commit -m "refactor: 重命名 BillingMode 为 ContinueMode，新增计费模式枚举 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 2: 接口变更

### Task 2: 重命名 RuleResolver 为 BillingConfigResolver

**Files:**
- Rename: `core/src/main/java/cn/shang/charging/billing/RuleResolver.java` → `BillingConfigResolver.java`

- [ ] **Step 1: 创建新接口文件**

创建 `core/src/main/java/cn/shang/charging/billing/BillingConfigResolver.java`：

```java
package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费配置解析器
 */
public interface BillingConfigResolver {

    /**
     * 获取计费模式
     * @param schemeId 方案id
     * @return 计费模式
     */
    BConstants.BillingMode resolveBillingMode(String schemeId);

    /**
     * 获取计费规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的计费规则配置
     */
    RuleConfig resolveChargingRule(String schemeId,
                                   LocalDateTime segmentStart,
                                   LocalDateTime segmentEnd);


    /**
     * 获取优惠规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的优惠规则配置
     */
    List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                    LocalDateTime segmentStart,
                                                    LocalDateTime segmentEnd);
}
```

- [ ] **Step 2: 删除旧接口文件**

```bash
rm core/src/main/java/cn/shang/charging/billing/RuleResolver.java
```

- [ ] **Step 3: 更新 BillingService 引用**

修改 `core/src/main/java/cn/shang/charging/billing/BillingService.java`：

将 `RuleResolver` 改为 `BillingConfigResolver`：

```java
package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.settlement.ResultAssembler;

import java.util.ArrayList;
import java.util.List;

public class BillingService {

    private final SegmentBuilder segmentBuilder;
    private final BillingConfigResolver billingConfigResolver;
    private final PromotionEngine promotionEngine;
    private final BillingCalculator billingCalculator;
    private final ResultAssembler resultAssembler;

    public BillingService(
            SegmentBuilder segmentBuilder,
            BillingConfigResolver billingConfigResolver,
            PromotionEngine promotionEngine,
            BillingCalculator billingCalculator,
            ResultAssembler resultAssembler) {
        this.segmentBuilder = segmentBuilder;
        this.billingConfigResolver = billingConfigResolver;
        this.promotionEngine = promotionEngine;
        this.billingCalculator = billingCalculator;
        this.resultAssembler = resultAssembler;
    }

    /**
     * 计费计算
     *
     * @param request 计费参数
     */
    public BillingResult calculate(BillingRequest request) {

        // 1. 构建方案分段（只负责方案切换）
        List<BillingSegment> segments = segmentBuilder.buildSegments(request);

        // 各分段计费结果
        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        // 2. 逐段计算
        for (BillingSegment segment : segments) {

            // 2.1 构建计算窗口（支持两种分段模式）
            CalculationWindow window = CalculationWindowFactory.create(
                    request.getBeginTime(),
                    segment,
                    request.getSegmentCalculationMode()
            );

            // 2.2 解析规则快照（方案已确定）
            RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(
                    segment.getSchemeId(),
                    window.getCalculationBegin(),
                    window.getCalculationEnd());

            // 解析优惠规则
            List<PromotionRuleConfig> promotionRules =
                    billingConfigResolver.resolvePromotionRules(
                            segment.getSchemeId(),
                            window.getCalculationBegin(),
                            window.getCalculationEnd());

            // 解析计费模式
            BConstants.BillingMode billingMode = billingConfigResolver.resolveBillingMode(segment.getSchemeId());

            // 2.3 构建 BillingContext（只读）
            BillingContext context = BillingContext.builder()
                    .id(request.getId())
                    .beginTime(request.getBeginTime())
                    .endTime(request.getEndTime())
                    .segment(segment)
                    .window(window)
                    .chargingRule(chargingRule)
                    .promotionRules(promotionRules)
                    .externalPromotions(request.getExternalPromotions())
                    .billingMode(billingMode)
                    .build();

            // 2.4 执行优惠聚合
            PromotionAggregate promotionAggregate = promotionEngine.evaluate(context);

            // 2.5 执行计费
            BillingSegmentResult segmentResult = billingCalculator.calculate(context, promotionAggregate);

            segmentResults.add(segmentResult);
        }
        // 3. 汇总结果（金额、满减、封顶等）
        return resultAssembler.assemble(
                request,
                segmentResults
        );
    }

}
```

- [ ] **Step 4: 更新 PromotionEngine 引用**

修改 `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`：

将 `RuleResolver` 改为 `BillingConfigResolver`：

```java
package cn.shang.charging.promotion;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.*;
import cn.shang.charging.promotion.rules.PromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 优惠计算engine
 */
@AllArgsConstructor
public class PromotionEngine {

    private BillingConfigResolver billingConfigResolver;
    private FreeTimeRangeMerger freeTimeRangeMerger;
    private FreeMinuteAllocator freeMinuteAllocator;
    private PromotionRuleRegistry promotionRuleRegistry;

    public PromotionAggregate evaluate(BillingContext context) {
        // 1️⃣ 确定本次 promotion 计算的时间窗口
        CalculationWindow window = context.getWindow();
        // window 内部已经处理了：
        // - 方案分段
        // - SEGMENT_ORIGIN / GLOBAL_ORIGIN
        List<FreeTimeRange> timeRangePromotions = new ArrayList<>();
        List<FreeMinutes> freeMinutesPromotions = new ArrayList<>();


        // 2.1 来自优惠规则（按方案 + 时间段）
        for (PromotionRuleConfig ruleConfig : context.getPromotionRules()) {
            List<PromotionGrant> grants = grant(context, ruleConfig);
            grants.forEach(grant -> {
                if (grant.getType() == BConstants.PromotionType.FREE_RANGE) {
                    timeRangePromotions.add(convertTimeRangeFromRule(grant));
                }
                if (grant.getType() == BConstants.PromotionType.FREE_MINUTES) {
                    freeMinutesPromotions.add(convertMinutesFromRule(grant));
                }
            });
        }

        // 2️⃣ 来自外部优惠
        for (PromotionGrant externalPromotion : context.getExternalPromotions()) {
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_RANGE) {
                timeRangePromotions.add(convertTimeRangeFromRule(externalPromotion));
            }
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_MINUTES) {
                freeMinutesPromotions.add(convertMinutesFromRule(externalPromotion));
            }
        }

        // 3️⃣ 合并显式免费时间段
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                timeRangePromotions,
                context.getBeginTime(),
                context.getEndTime());
        List<FreeTimeRange> explicitFreeRanges = rangeMergeResult.getMergedRanges();

        // 免费分钟数转为时间段
        FreeMinuteAllocationResult minuteResult =
                freeMinuteAllocator.allocate(
                        freeMinutesPromotions,
                        explicitFreeRanges,
                        window
                );

        // 最终合并
        var finalMergeResult = freeTimeRangeMerger.merge(
                Stream.concat(
                        explicitFreeRanges.stream(),
                        minuteResult.getGeneratedFreeRanges().stream()
                ).toList(),
                window.getCalculationBegin(),
                window.getCalculationEnd()
        );
        List<FreeTimeRange> finalFreeRanges = finalMergeResult.getMergedRanges();

        return PromotionAggregate.builder()
                .freeTimeRanges(finalFreeRanges)
                .usages(List.of())
                .build();
    }

    /**
     * 计算有效优惠
     */
    private List<PromotionGrant> grant(BillingContext context, PromotionRuleConfig ruleConfig) {
        var rule = promotionRuleRegistry.get(ruleConfig.getType());
        if (!rule.getType().equals(ruleConfig.getType())) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        return invokeRule(rule, context, ruleConfig);

    }
    private <C extends PromotionRuleConfig> List<PromotionGrant> invokeRule(
            PromotionRule<C> rule,
            BillingContext context,
            PromotionRuleConfig rawConfig) {

        if (!rule.getConfigClass().isInstance(rawConfig)) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        C config = rule.getConfigClass().cast(rawConfig);
        return rule.grant(context, config);
    }

    /**
     * 将优惠规则中的优惠时间段转为计算的免费时间段
     */
    private FreeTimeRange convertTimeRangeFromRule(PromotionGrant grant) {
        return FreeTimeRange.builder()
                .id(grant.getId())
                .promotionType(grant.getType())
                .beginTime(grant.getBeginTime())
                .endTime(grant.getEndTime())
                .priority(grant.getPriority())
                .build();
    }

    /**
     * 将优惠规则中的免费时间段转化未计算的免费分钟数
     */
    private FreeMinutes convertMinutesFromRule(PromotionGrant grant) {
        return FreeMinutes.builder()
                .id(grant.getId())
                .minutes(grant.getFreeMinutes())
                .priority(grant.getPriority())
                .build();
    }


}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor: 重命名 RuleResolver 为 BillingConfigResolver，新增 resolveBillingMode 方法 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 3: 规则接口变更

### Task 3: BillingRule 接口新增 supportedModes 方法

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`

- [ ] **Step 1: 修改 BillingRule 接口**

```java
package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.Set;

public interface BillingRule<C extends RuleConfig> {

    /**
     * 计算费用
     */
    BillingSegmentResult calculate(BillingContext context,
                                   C ruleConfig,
                                   PromotionAggregate promotionAggregate);

    Class<C> configClass();

    /**
     * 返回规则支持的计费模式
     */
    Set<BConstants.BillingMode> supportedModes();

}
```

- [ ] **Step 2: 编译验证（预期失败）**

Run: `mvn compile -pl core -q`

Expected: 编译失败，DayNightRule 和 RelativeTimeRule 未实现 supportedModes()

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java
git commit -m "feat: BillingRule 接口新增 supportedModes 方法 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 4: 规则实现

### Task 4: DayNightRule 实现 supportedModes

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 添加 import 和 supportedModes 实现**

在文件顶部添加 import：
```java
import cn.shang.charging.billing.pojo.BConstants;
import java.util.EnumSet;
import java.util.Set;
```

在类中添加方法：
```java
@Override
public Set<BConstants.BillingMode> supportedModes() {
    return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "feat: DayNightRule 实现 supportedModes 方法 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

### Task 5: RelativeTimeRule 实现 supportedModes

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 添加 import 和 supportedModes 实现**

在文件顶部添加 import：
```java
import cn.shang.charging.billing.pojo.BConstants;
import java.util.EnumSet;
import java.util.Set;
```

在类中添加方法：
```java
@Override
public Set<BConstants.BillingMode> supportedModes() {
    return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "feat: RelativeTimeRule 实现 supportedModes 方法 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 5: 计算器校验

### Task 6: BillingCalculator 新增模式校验逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/BillingCalculator.java`

- [ ] **Step 1: 修改 BillingCalculator**

```java
package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BillingCalculator {


    private final BillingRuleRegistry ruleRegistry;

    /**
     * 计算
     */
    public BillingSegmentResult calculate(BillingContext context, PromotionAggregate promotionAggregate) {

        var ruleConfig = context.getChargingRule();
        BillingRule<?> billingRule = ruleRegistry.get(ruleConfig.getType());

        if (billingRule == null) {
            throw new RuntimeException("No billing rule found for type: " + ruleConfig.getType());
        }

        // 校验计费模式支持
        if (!billingRule.supportedModes().contains(context.getBillingMode())) {
            throw new IllegalStateException(
                    "Rule " + billingRule.getClass().getSimpleName() +
                    " (type=" + ruleConfig.getType() + ") does not support billing mode: " +
                    context.getBillingMode()
            );
        }

        return calculateInternal(context, billingRule, ruleConfig, promotionAggregate);
    }

    /**
     * 使用规则计算费用
     */
    private <C extends RuleConfig> BillingSegmentResult calculateInternal(
            BillingContext context,
            BillingRule<C> rule,
            RuleConfig rawConfig,
            PromotionAggregate promotionAggregate) {

        if (!rule.configClass().isInstance(rawConfig)) {
            throw new IllegalStateException(
                    "RuleConfig mismatch, rule="
                            + rule.getClass().getSimpleName()
                            + ", config=" + rawConfig.getClass().getSimpleName()
            );
        }

        C config = rule.configClass().cast(rawConfig);
        return rule.calculate(context, config, promotionAggregate);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/BillingCalculator.java
git commit -m "feat: BillingCalculator 新增计费模式校验逻辑 (Claude Code, Model: glm-5, Skill: superpowers:writing-plans)"
```

---

## Chunk 6: 整体验证

### Task 7: 编译和测试验证

- [ ] **Step 1: 完整编译**

Run: `mvn clean compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行现有测试**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.RelativeTimeTest" -q`

Expected: 测试正常运行（可能需要外部实现 BillingConfigResolver）

- [ ] **Step 3: 最终提交（如有未提交的变更）**

```bash
git status
# 如有未提交的变更则提交
```

---

## 后续提醒

功能完成后需要提醒用户：

> **提醒**：当前实现方式为规则内部判断计费模式行为，未来应将可复用的计费模式逻辑抽离到引擎层面统一处理。这是一个渐进式演进方向。