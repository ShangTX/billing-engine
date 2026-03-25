# 优惠等效金额计算实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现查询时间与计算时间分离、优惠等效金额计算两个功能

**Architecture:**
- core 模块拆分 BillingService，新增 SegmentContext 和 PromotionAggregateUtil
- billing-api 模块新增 BillingResultViewer、PromotionEquivalentCalculator
- queryTime 功能从 core 移至 billing-api

**Tech Stack:** Java 17, Lombok, Maven, JUnit 5

---

## File Structure

### core 模块

| 文件 | 类型 | 职责 |
|------|------|------|
| `SegmentContext.java` | 新增 | 分段计算上下文，持有 BillingContext 和 PromotionAggregate |
| `PromotionAggregateUtil.java` | 新增 | 优惠聚合工具类，提供 exclude 方法 |
| `BillingService.java` | 修改 | 新增 prepareContexts/calculateWithContexts 方法 |
| `ResultAssembler.java` | 修改 | 删除 filterByQueryTime，移除 queryTime 处理 |

### billing-api 模块

| 文件 | 类型 | 职责 |
|------|------|------|
| `CalculationWithQueryResult.java` | 新增 | 双结果返回类 |
| `BillingResultViewer.java` | 新增 | 结果视图处理器，按 queryTime 截取 |
| `PromotionEquivalentCalculator.java` | 新增 | 等效金额计算器，消去法实现 |
| `BillingTemplate.java` | 修改 | 新增 calculateWithQuery/calculatePromotionEquivalents 方法 |

### bill-test 模块

| 文件 | 类型 | 职责 |
|------|------|------|
| `BillingResultViewerTest.java` | 新增 | 测试 queryTime 截取逻辑 |
| `PromotionEquivalentCalculatorTest.java` | 新增 | 测试等效金额计算 |

---

## Task 1: 新增 SegmentContext (core)

**Files:**
- Create: `core/src/main/java/cn/shang/charging/billing/pojo/SegmentContext.java`

- [ ] **Step 1: 创建 SegmentContext 类**

```java
package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分段计算上下文
 * 包含计算所需的所有信息，可独立计算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentContext {
    /**
     * 分段ID
     */
    private String segmentId;

    /**
     * 计费上下文
     */
    private BillingContext billingContext;

    /**
     * 优惠聚合结果
     */
    private PromotionAggregate promotionAggregate;
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/SegmentContext.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 SegmentContext 分段计算上下文"
```

---

## Task 2: 新增 PromotionAggregateUtil (core)

**Files:**
- Create: `core/src/main/java/cn/shang/charging/promotion/PromotionAggregateUtil.java`

- [ ] **Step 1: 创建 PromotionAggregateUtil 类**

```java
package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.PromotionCarryOver;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠聚合工具类
 */
public class PromotionAggregateUtil {

    private PromotionAggregateUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 从聚合结果中排除指定优惠
     *
     * @param original    原始聚合结果
     * @param excludedIds 要排除的优惠ID
     * @return 新的聚合结果
     */
    public static PromotionAggregate exclude(PromotionAggregate original, Set<String> excludedIds) {
        if (original == null || excludedIds == null || excludedIds.isEmpty()) {
            return original;
        }

        // 1. 过滤免费时间段
        List<FreeTimeRange> filteredRanges = original.getFreeTimeRanges() == null
            ? List.of()
            : original.getFreeTimeRanges().stream()
                .filter(r -> r.getId() != null && !excludedIds.contains(r.getId()))
                .toList();

        // 2. 过滤使用记录
        List<PromotionUsage> filteredUsages = original.getUsages() == null
            ? List.of()
            : original.getUsages().stream()
                .filter(u -> u.getPromotionId() != null && !excludedIds.contains(u.getPromotionId()))
                .toList();

        // 3. 重算总免费分钟数
        // 从过滤后的 usages 中累加 grantedMinutes
        // 注意：等效金额计算在完整计费后进行，此时 usages 已生成
        long filteredFreeMinutes = filteredUsages.stream()
            .mapToLong(PromotionUsage::getGrantedMinutes)
            .sum();

        // 4. 处理 promotionCarryOver（排除已排除优惠的结转状态）
        PromotionCarryOver filteredCarryOver = filterCarryOver(original.getPromotionCarryOver(), excludedIds);

        return PromotionAggregate.builder()
            .freeTimeRanges(filteredRanges)
            .freeMinutes(filteredFreeMinutes)
            .usages(filteredUsages)
            .promotionCarryOver(filteredCarryOver)
            .build();
    }

    /**
     * 过滤优惠结转状态
     */
    private static PromotionCarryOver filterCarryOver(PromotionCarryOver carryOver, Set<String> excludedIds) {
        if (carryOver == null) {
            return null;
        }

        // 过滤剩余分钟数
        Map<String, Integer> filteredRemainingMinutes = null;
        if (carryOver.getRemainingMinutes() != null) {
            filteredRemainingMinutes = carryOver.getRemainingMinutes().entrySet().stream()
                .filter(e -> !excludedIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        // 过滤已使用的免费时段
        List<FreeTimeRange> filteredUsedRanges = null;
        if (carryOver.getUsedFreeRanges() != null) {
            filteredUsedRanges = carryOver.getUsedFreeRanges().stream()
                .filter(r -> r.getId() == null || !excludedIds.contains(r.getId()))
                .toList();
        }

        if ((filteredRemainingMinutes == null || filteredRemainingMinutes.isEmpty())
            && (filteredUsedRanges == null || filteredUsedRanges.isEmpty())) {
            return null;
        }

        return PromotionCarryOver.builder()
            .remainingMinutes(filteredRemainingMinutes)
            .usedFreeRanges(filteredUsedRanges)
            .build();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/PromotionAggregateUtil.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 PromotionAggregateUtil 优惠聚合工具类"
```

---

## Task 3: 修改 BillingService 新增拆分方法 (core)

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/BillingService.java`

- [ ] **Step 1: 新增 prepareContexts 方法**

在 `BillingService` 类中新增以下方法（在 `calculate` 方法之后）：

```java
/**
 * 准备分段上下文
 * 执行分段构建、规则解析、优惠聚合
 *
 * 注意：目前仅支持 FROM_SCRATCH 模式
 *
 * @param request 计费请求
 * @return 分段上下文列表
 */
public List<SegmentContext> prepareContexts(BillingRequest request) {
    List<SegmentContext> contexts = new ArrayList<>();

    List<BillingSegment> segments = segmentBuilder.buildSegments(request);

    for (BillingSegment segment : segments) {
        CalculationWindow window = CalculationWindowFactory.create(
            request.getBeginTime(),
            segment,
            request.getSegmentCalculationMode()
        );

        RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(
            segment.getSchemeId(),
            window.getCalculationBegin(),
            window.getCalculationEnd());

        List<PromotionRuleConfig> promotionRules = billingConfigResolver.resolvePromotionRules(
            segment.getSchemeId(),
            window.getCalculationBegin(),
            window.getCalculationEnd());

        BConstants.BillingMode billingMode = billingConfigResolver.resolveBillingMode(segment.getSchemeId());

        BillingContext billingContext = BillingContext.builder()
            .id(request.getId())
            .beginTime(request.getBeginTime())
            .endTime(request.getEndTime())
            .segment(segment)
            .window(window)
            .chargingRule(chargingRule)
            .promotionRules(promotionRules)
            .externalPromotions(request.getExternalPromotions())
            .billingMode(billingMode)
            .continueMode(BConstants.ContinueMode.FROM_SCRATCH)
            .billingConfigResolver(billingConfigResolver)
            .build();

        PromotionAggregate promotionAggregate = promotionEngine.evaluate(billingContext);

        contexts.add(SegmentContext.builder()
            .segmentId(segment.getId())
            .billingContext(billingContext)
            .promotionAggregate(promotionAggregate)
            .build());
    }

    return contexts;
}

/**
 * 用分段上下文计算
 * 只执行计费计算和结果汇总
 *
 * @param contexts 分段上下文列表
 * @param request  原始请求（用于 assemble）
 * @return 计费结果
 */
public BillingResult calculateWithContexts(List<SegmentContext> contexts, BillingRequest request) {
    List<BillingSegmentResult> segmentResults = new ArrayList<>();

    for (SegmentContext ctx : contexts) {
        BillingSegmentResult segmentResult = billingCalculator.calculate(
            ctx.getBillingContext(),
            ctx.getPromotionAggregate()
        );
        segmentResults.add(segmentResult);
    }

    return resultAssembler.assemble(request, segmentResults);
}
```

- [ ] **Step 2: 添加 import 语句**

在文件顶部添加：

```java
import cn.shang.charging.billing.pojo.SegmentContext;
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/BillingService.java
git commit -m "[claude-code|glm-5|superpowers] feat: BillingService 新增 prepareContexts/calculateWithContexts 方法"
```

---

## Task 4: 修改 ResultAssembler 删除 filterByQueryTime (core)

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`

- [ ] **Step 1: 删除 filterByQueryTime 方法**

删除 `filterByQueryTime` 方法（约 79-104 行）

- [ ] **Step 2: 修改 assemble 方法**

修改 `assemble` 方法的返回语句：

```java
// 修改前（约 56-67 行）
BillingResult result = BillingResult.builder()
    .units(allUnits)
    .promotionUsages(allUsages)
    .finalAmount(totalAmount)
    .effectiveFrom(effectiveFrom)
    .effectiveTo(effectiveTo)
    .calculationEndTime(calculationEndTime)
    .carryOver(carryOver)
    .build();

// 根据 queryTime 过滤（如果指定了 queryTime）
return filterByQueryTime(result, request.getQueryTime());

// 修改后
return BillingResult.builder()
    .units(allUnits)
    .promotionUsages(allUsages)
    .finalAmount(totalAmount)
    .effectiveFrom(effectiveFrom)
    .effectiveTo(effectiveTo)
    .calculationEndTime(calculationEndTime)
    .carryOver(carryOver)
    .build();
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行现有测试确保无回归**

Run: `mvn test -pl core -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
git commit -m "[claude-code|glm-5|superpowers] refactor: 移除 ResultAssembler.filterByQueryTime，queryTime 功能移至 billing-api"
```

---

## Task 5: 新增 CalculationWithQueryResult (billing-api)

**Files:**
- Create: `billing-api/src/main/java/cn/shang/charging/wrapper/CalculationWithQueryResult.java`

- [ ] **Step 1: 创建 CalculationWithQueryResult 类**

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计算结果与查询结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculationWithQueryResult {
    /**
     * 完整计算结果
     * - 用于 CONTINUE 进度存储
     * - 用于费用稳定窗口判断
     */
    private BillingResult calculationResult;

    /**
     * 查询时间点的结果
     * - 用于展示给用户
     */
    private BillingResult queryResult;
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl billing-api -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper/CalculationWithQueryResult.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 CalculationWithQueryResult 双结果返回类"
```

---

## Task 6: 新增 BillingResultViewer (billing-api)

**Files:**
- Create: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`

- [ ] **Step 1: 创建 BillingResultViewer 类**

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费结果视图处理器
 * 负责按查询时间截取结果
 */
public class BillingResultViewer {

    /**
     * 返回指定时间点的视图
     *
     * @param result    完整计算结果
     * @param queryTime 查询时间点
     * @return 截取后的结果
     */
    public BillingResult viewAtTime(BillingResult result, LocalDateTime queryTime) {
        if (queryTime == null || result == null) {
            return result;
        }

        // 1. 截取计费单元
        List<BillingUnit> filteredUnits = filterUnits(result.getUnits(), queryTime);

        // 2. 截取优惠使用情况
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

        // 3. 重算金额
        BigDecimal filteredAmount = calculateAmount(filteredUnits);

        // 4. 计算查询结果的有效时间窗口
        LocalDateTime effectiveFrom = calculateEffectiveFrom(filteredUnits);
        LocalDateTime effectiveTo = calculateEffectiveTo(filteredUnits);

        // 5. 计算查询结果的 calculationEndTime
        // 取 queryTime 和原始 calculationEndTime 的较小值
        LocalDateTime queryCalcEndTime = result.getCalculationEndTime();
        if (queryCalcEndTime == null || queryTime.isBefore(queryCalcEndTime)) {
            queryCalcEndTime = queryTime;
        }

        // 6. 构建结果（保留原始 carryOver，用于 CONTINUE）
        return BillingResult.builder()
            .units(filteredUnits)
            .promotionUsages(filteredUsages)
            .finalAmount(filteredAmount)
            .effectiveFrom(effectiveFrom)
            .effectiveTo(effectiveTo)
            .carryOver(result.getCarryOver())
            .calculationEndTime(queryCalcEndTime)
            .build();
    }

    /**
     * 过滤计费单元
     * 保留 endTime <= queryTime 的单元
     */
    private List<BillingUnit> filterUnits(List<BillingUnit> units, LocalDateTime queryTime) {
        if (units == null) {
            return List.of();
        }
        return units.stream()
            .filter(unit -> unit.getEndTime() != null && !unit.getEndTime().isAfter(queryTime))
            .toList();
    }

    /**
     * 过滤并截取优惠使用情况
     */
    private List<PromotionUsage> filterUsages(List<PromotionUsage> usages, LocalDateTime queryTime) {
        if (usages == null) {
            return List.of();
        }
        return usages.stream()
            .map(usage -> truncateUsage(usage, queryTime))
            .filter(usage -> usage != null && usage.getUsedMinutes() > 0)
            .toList();
    }

    /**
     * 截取单个优惠使用记录
     */
    private PromotionUsage truncateUsage(PromotionUsage usage, LocalDateTime queryTime) {
        if (usage == null || usage.getUsedFrom() == null) {
            return null;
        }

        // 如果使用结束时间超过 queryTime，截取
        if (usage.getUsedTo() != null && usage.getUsedTo().isAfter(queryTime)) {
            int truncatedMinutes = (int) Duration.between(usage.getUsedFrom(), queryTime).toMinutes();

            return PromotionUsage.builder()
                .promotionId(usage.getPromotionId())
                .type(usage.getType())
                .grantedMinutes(usage.getGrantedMinutes())
                .usedMinutes(truncatedMinutes)
                .usedFrom(usage.getUsedFrom())
                .usedTo(queryTime)
                .build();
        }
        return usage;
    }

    private BigDecimal calculateAmount(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return units.stream()
            .map(unit -> unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(0).getBeginTime();
    }

    private LocalDateTime calculateEffectiveTo(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(units.size() - 1).getEndTime();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl billing-api -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 BillingResultViewer 结果视图处理器"
```

---

## Task 7: 新增 PromotionEquivalentCalculator (billing-api)

**Files:**
- Create: `billing-api/src/main/java/cn/shang/charging/wrapper/PromotionEquivalentCalculator.java`

- [ ] **Step 1: 创建 PromotionEquivalentCalculator 类**

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.SegmentContext;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优惠等效金额计算器
 * 使用消去法精确计算每个优惠的等效金额
 */
public class PromotionEquivalentCalculator {

    private final BillingService billingService;

    public PromotionEquivalentCalculator(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * 计算各优惠的等效金额
     *
     * @param request 计费请求
     * @return 优惠ID → 等效金额
     */
    public Map<String, BigDecimal> calculate(BillingRequest request) {
        Map<String, BigDecimal> equivalents = new LinkedHashMap<>();

        // 1. 准备分段上下文（只执行一次）
        List<SegmentContext> contexts = billingService.prepareContexts(request);

        // 2. 计算全优惠基准结果
        BillingResult baseline = billingService.calculateWithContexts(contexts, request);
        BigDecimal baselineAmount = baseline.getFinalAmount() != null
            ? baseline.getFinalAmount()
            : BigDecimal.ZERO;

        // 3. 提取所有优惠时间段，按开始时间排序
        List<FreeTimeRange> sortedRanges = extractAndSortRanges(baseline);

        // 如果没有优惠，直接返回空 Map
        if (sortedRanges.isEmpty()) {
            return equivalents;
        }

        // 4. 依次消去优惠
        Set<String> excludedIds = new HashSet<>();
        BigDecimal previousAmount = baselineAmount;

        for (FreeTimeRange range : sortedRanges) {
            excludedIds.add(range.getId());

            // 克隆并排除优惠
            List<SegmentContext> modifiedContexts = cloneAndExclude(contexts, excludedIds);

            // 计算
            BillingResult result = billingService.calculateWithContexts(modifiedContexts, request);
            BigDecimal currentAmount = result.getFinalAmount() != null
                ? result.getFinalAmount()
                : BigDecimal.ZERO;

            // 等效金额 = 新费用 - 旧费用
            BigDecimal equivalent = currentAmount.subtract(previousAmount);
            if (equivalent.compareTo(BigDecimal.ZERO) < 0) {
                equivalent = BigDecimal.ZERO;
            }

            equivalents.put(range.getId(), equivalent);
            previousAmount = currentAmount;
        }

        return equivalents;
    }

    /**
     * 提取所有优惠时间段并按开始时间排序
     * 包括 FREE_RANGE 和 FREE_MINUTES 转换后的时间段
     */
    private List<FreeTimeRange> extractAndSortRanges(BillingResult result) {
        if (result.getPromotionUsages() == null) {
            return List.of();
        }

        return result.getPromotionUsages().stream()
            .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE
                      || u.getType() == BConstants.PromotionType.FREE_MINUTES)
            .filter(u -> u.getUsedFrom() != null && u.getUsedTo() != null)
            .map(u -> FreeTimeRange.builder()
                .id(u.getPromotionId())
                .beginTime(u.getUsedFrom())
                .endTime(u.getUsedTo())
                .promotionType(u.getType())
                .build())
            .sorted(Comparator.comparing(FreeTimeRange::getBeginTime))
            .toList();
    }

    /**
     * 克隆分段上下文并排除指定优惠
     */
    private List<SegmentContext> cloneAndExclude(List<SegmentContext> contexts, Set<String> excludedIds) {
        return contexts.stream()
            .map(ctx -> SegmentContext.builder()
                .segmentId(ctx.getSegmentId())
                .billingContext(ctx.getBillingContext())
                .promotionAggregate(PromotionAggregateUtil.exclude(ctx.getPromotionAggregate(), excludedIds))
                .build())
            .toList();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl billing-api -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper/PromotionEquivalentCalculator.java
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 PromotionEquivalentCalculator 等效金额计算器"
```

---

## Task 8: 修改 BillingTemplate 新增方法 (billing-api)

**Files:**
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`

- [ ] **Step 1: 读取当前 BillingTemplate 内容**

- [ ] **Step 2: 添加新字段和构造函数修改**

在类中添加新字段：

```java
private final BillingResultViewer resultViewer;
private final PromotionEquivalentCalculator promotionEquivalentCalculator;
```

修改构造函数：

```java
public BillingTemplate(BillingService billingService,
                       BillingConfigResolver configResolver) {
    this.billingService = billingService;
    this.configResolver = configResolver;
    this.savingsAnalyzer = new PromotionSavingsAnalyzer();
    this.resultViewer = new BillingResultViewer();
    this.promotionEquivalentCalculator = new PromotionEquivalentCalculator(billingService);
}
```

- [ ] **Step 3: 添加新方法**

在类末尾添加：

```java
/**
 * 计算并返回两种结果
 *
 * @param request   计费请求
 * @param queryTime 查询时间点
 * @return 计算结果和查询结果
 */
public CalculationWithQueryResult calculateWithQuery(BillingRequest request, LocalDateTime queryTime) {
    BillingResult calculationResult = billingService.calculate(request);
    BillingResult queryResult = resultViewer.viewAtTime(calculationResult, queryTime);
    return new CalculationWithQueryResult(calculationResult, queryResult);
}

/**
 * 计算优惠等效金额
 *
 * @param request 计费请求
 * @return 优惠ID → 等效金额
 */
public Map<String, BigDecimal> calculatePromotionEquivalents(BillingRequest request) {
    return promotionEquivalentCalculator.calculate(request);
}
```

- [ ] **Step 4: 添加 import 语句**

```java
import cn.shang.charging.billing.pojo.BillingRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -pl billing-api -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java
git commit -m "[claude-code|glm-5|superpowers] feat: BillingTemplate 新增 calculateWithQuery/calculatePromotionEquivalents 方法"
```

---

## Task 9: 新增 BillingResultViewerTest (bill-test)

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/BillingResultViewerTest.java`

- [ ] **Step 1: 创建测试类**

```java
package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.wrapper.BillingResultViewer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillingResultViewerTest {

    private final BillingResultViewer viewer = new BillingResultViewer();

    @Test
    void testViewAtTime_nullQueryTime_returnsOriginal() {
        BillingResult result = BillingResult.builder()
            .finalAmount(BigDecimal.TEN)
            .build();

        BillingResult view = viewer.viewAtTime(result, null);

        assertSame(result, view);
    }

    @Test
    void testViewAtTime_nullResult_returnsNull() {
        BillingResult view = viewer.viewAtTime(null, LocalDateTime.now());
        assertNull(view);
    }

    @Test
    void testViewAtTime_filtersUnits() {
        LocalDateTime t8 = LocalDateTime.of(2024, 1, 1, 8, 0);
        LocalDateTime t9 = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime t10 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t11 = LocalDateTime.of(2024, 1, 1, 11, 0);

        BillingUnit unit1 = BillingUnit.builder()
            .beginTime(t8).endTime(t9).chargedAmount(BigDecimal.ONE).build();
        BillingUnit unit2 = BillingUnit.builder()
            .beginTime(t9).endTime(t10).chargedAmount(BigDecimal.ONE).build();
        BillingUnit unit3 = BillingUnit.builder()
            .beginTime(t10).endTime(t11).chargedAmount(BigDecimal.ONE).build();

        BillingResult result = BillingResult.builder()
            .units(List.of(unit1, unit2, unit3))
            .finalAmount(BigDecimal.valueOf(3))
            .build();

        // queryTime = 10:00，保留 endTime <= 10:00 的单元
        BillingResult view = viewer.viewAtTime(result, t10);

        assertEquals(2, view.getUnits().size());
        assertEquals(BigDecimal.valueOf(2), view.getFinalAmount());
    }

    @Test
    void testViewAtTime_truncatesPromotionUsage() {
        LocalDateTime t8 = LocalDateTime.of(2024, 1, 1, 8, 0);
        LocalDateTime t9 = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime t10 = LocalDateTime.of(2024, 1, 1, 10, 0);

        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(t8)
            .usedTo(t10)
            .usedMinutes(120)
            .build();

        BillingResult result = BillingResult.builder()
            .units(List.of())
            .promotionUsages(List.of(usage))
            .finalAmount(BigDecimal.ZERO)
            .build();

        // queryTime = 9:00，截取 usage
        BillingResult view = viewer.viewAtTime(result, t9);

        assertEquals(1, view.getPromotionUsages().size());
        PromotionUsage truncated = view.getPromotionUsages().get(0);
        assertEquals(t9, truncated.getUsedTo());
        assertEquals(60, truncated.getUsedMinutes());
    }

    @Test
    void testViewAtTime_preservesCarryOver() {
        BillingResult result = BillingResult.builder()
            .units(List.of())
            .finalAmount(BigDecimal.ZERO)
            .calculationEndTime(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        BillingResult view = viewer.viewAtTime(result, LocalDateTime.of(2024, 1, 1, 9, 0));

        // carryOver 应该保留
        assertNull(view.getCarryOver()); // 原始为 null，所以也为 null
        // calculationEndTime 应该是 queryTime
        assertEquals(LocalDateTime.of(2024, 1, 1, 9, 0), view.getCalculationEndTime());
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl bill-test -Dtest=BillingResultViewerTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: 提交**

```bash
git add bill-test/src/main/java/cn/shang/charging/BillingResultViewerTest.java
git commit -m "[claude-code|glm-5|superpowers] test: 新增 BillingResultViewerTest 测试类"
```

---

## Task 10: 新增 PromotionEquivalentCalculatorTest (bill-test)

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/PromotionEquivalentCalculatorTest.java`

- [ ] **Step 1: 创建测试类**

```java
package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.PromotionEquivalentCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionEquivalentCalculatorTest {

    private BillingService billingService;
    private PromotionEquivalentCalculator calculator;

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        calculator = new PromotionEquivalentCalculator(billingService);
    }

    @Test
    void testCalculate_noPromotions_returnsEmptyMap() {
        BillingRequest request = BillingRequest.builder().build();
        BillingResult result = BillingResult.builder()
            .finalAmount(BigDecimal.ZERO)
            .promotionUsages(List.of())
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of());
        when(billingService.calculateWithContexts(any(), eq(request))).thenReturn(result);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertTrue(equivalents.isEmpty());
    }

    @Test
    void testCalculate_singlePromotion() {
        BillingRequest request = BillingRequest.builder().build();

        // 模拟一个优惠
        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 9, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        BillingResult withPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of(usage))
            .build();

        BillingResult withoutPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(withPromo)  // 第一次调用（全优惠）
            .thenReturn(withoutPromo);  // 第二次调用（去掉优惠）

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertEquals(1, equivalents.size());
        assertEquals(BigDecimal.valueOf(10), equivalents.get("promo1"));
    }

    @Test
    void testCalculate_multiplePromotions_sortedByBeginTime() {
        BillingRequest request = BillingRequest.builder().build();

        // 两个优惠，按开始时间排序
        PromotionUsage usage1 = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 8, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 9, 0))
            .build();

        PromotionUsage usage2 = PromotionUsage.builder()
            .promotionId("promo2")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 10, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 11, 0))
            .build();

        // R0: 全优惠 = 10
        // R1: 去掉 promo1 = 15
        // R2: 去掉 promo1, promo2 = 20
        BillingResult r0 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of(usage1, usage2))
            .build();

        BillingResult r1 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(15))
            .promotionUsages(List.of(usage2))
            .build();

        BillingResult r2 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(r0)
            .thenReturn(r1)
            .thenReturn(r2);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertEquals(2, equivalents.size());
        // promo1: R1 - R0 = 15 - 10 = 5
        assertEquals(BigDecimal.valueOf(5), equivalents.get("promo1"));
        // promo2: R2 - R1 = 20 - 15 = 5
        assertEquals(BigDecimal.valueOf(5), equivalents.get("promo2"));
    }

    @Test
    void testCalculate_negativeEquivalent_clampedToZero() {
        BillingRequest request = BillingRequest.builder().build();

        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 9, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        // 异常情况：去掉优惠后费用反而降低（不应该发生）
        BillingResult withPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of(usage))
            .build();

        BillingResult withoutPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(withPromo)
            .thenReturn(withoutPromo);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        // 负数应该被钳制为 0
        assertEquals(BigDecimal.ZERO, equivalents.get("promo1"));
    }
}
```

- [ ] **Step 2: 添加 mockito 依赖（如果需要）**

检查 `bill-test/pom.xml` 是否有 mockito 依赖，如果没有则添加。

- [ ] **Step 3: 运行测试**

Run: `mvn test -pl bill-test -Dtest=PromotionEquivalentCalculatorTest -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: 提交**

```bash
git add bill-test/src/main/java/cn/shang/charging/PromotionEquivalentCalculatorTest.java
git commit -m "[claude-code|glm-5|superpowers] test: 新增 PromotionEquivalentCalculatorTest 测试类"
```

---

## Task 11: 集成测试验证

**Files:**
- Test: 运行所有测试

- [ ] **Step 1: 运行所有 core 模块测试**

Run: `mvn test -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行所有 billing-api 模块测试**

Run: `mvn test -pl billing-api -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行所有 bill-test 模块测试**

Run: `mvn test -pl bill-test -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行全量构建**

Run: `mvn clean install -q`
Expected: BUILD SUCCESS

---

## Task 12: 文档更新

**Files:**
- Modify: `CLAUDE.md` (已更新)
- Modify: `MEMORY.md`

- [ ] **Step 1: 更新 MEMORY.md 记录完成状态**

- [ ] **Step 2: 提交最终状态**

```bash
git add MEMORY.md
git commit -m "[claude-code|glm-5|superpowers] docs: 更新项目进度记录"
```

---

## 执行顺序总结

```
Task 1  → SegmentContext (core)
Task 2  → PromotionAggregateUtil (core)
Task 3  → BillingService 修改 (core)
Task 4  → ResultAssembler 修改 (core)
Task 5  → CalculationWithQueryResult (billing-api)
Task 6  → BillingResultViewer (billing-api)
Task 7  → PromotionEquivalentCalculator (billing-api)
Task 8  → BillingTemplate 修改 (billing-api)
Task 9  → BillingResultViewerTest (bill-test)
Task 10 → PromotionEquivalentCalculatorTest (bill-test)
Task 11 → 集成测试验证
Task 12 → 文档更新
```

**依赖关系**：
- Task 3 依赖 Task 1, Task 2
- Task 7 依赖 Task 3
- Task 8 依赖 Task 5, Task 6, Task 7
- Task 9, Task 10 可以并行
- Task 11 依赖所有前置任务