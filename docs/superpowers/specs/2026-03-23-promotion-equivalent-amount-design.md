# 优惠等效金额计算设计

## 概述

本文档设计两个相关功能：
1. **查询时间与计算时间分离**：支持计算更长时段，但只返回指定时间点的结果
2. **优惠等效金额计算**：通过消去法计算每个优惠的等效金额

---

## 功能一：查询时间与计算时间分离

### 背景问题

当前 `ResultAssembler.filterByQueryTime` 在 core 模块中，但这个功能本质上是"视图层"逻辑，不应该放在核心计费引擎中。

### 设计决策

**将 queryTime 截取功能移到 billing-api 模块**。

| 层次 | 职责 |
|------|------|
| core | 计算完整结果，输出一致的计费单元和优惠使用情况 |
| billing-api | 提供视图功能，截取指定时间点的结果 |

### 边界语义说明

**计费单元过滤规则**：
```
unit.endTime <= queryTime 的单元被保留
```
即：结束时间等于 queryTime 的单元**包含**在结果中。

**示例**：
```
queryTime = 09:00
单元1: 08:00-09:00 → 包含（endTime == queryTime）
单元2: 09:00-10:00 → 不包含（endTime > queryTime）
```

### 新增类

#### CalculationWithQueryResult

```java
package cn.shang.charging.wrapper;

/**
 * 计算结果与查询结果
 */
@Data
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

#### BillingResultViewer

```java
package cn.shang.charging.wrapper;

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
        if (queryTime == null) {
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
            .carryOver(result.getCarryOver())          // 保留原始 CONTINUE 进度
            .calculationEndTime(queryCalcEndTime)      // 查询位置的计算终点
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

#### BillingTemplate 新增方法

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
```

### core 模块改动

#### ResultAssembler 改动

删除 `filterByQueryTime` 方法，修改 `assemble()` 方法：

```java
// 修改前
public BillingResult assemble(BillingRequest request, List<BillingSegmentResult> segmentResultList) {
    // ... 构建结果 ...
    // 根据 queryTime 过滤（如果指定了 queryTime）
    return filterByQueryTime(result, request.getQueryTime());
}

// 修改后
public BillingResult assemble(BillingRequest request, List<BillingSegmentResult> segmentResultList) {
    // ... 构建结果 ...
    // 不再过滤，直接返回完整结果
    // queryTime 过滤逻辑移到 billing-api 的 BillingResultViewer
    return result;
}
```

**影响**：core 模块不再处理 queryTime，始终返回完整计算结果。queryTime 功能由 billing-api 模块的 `BillingResultViewer` 提供。

---

## 功能二：优惠等效金额计算

### 设计目标

通过"消去法"精确计算每个优惠的等效金额：
- 按优惠时间段的开始时间从早到晚依次消去
- 计算每个优惠单独贡献的金额

### 支持的优惠类型

| 类型 | 支持 | 说明 |
|------|------|------|
| FREE_RANGE | ✅ | 免费时间段 |
| FREE_MINUTES | ✅ | 免费分钟数（已转换为等效时间段）|

**处理方式**：FREE_MINUTES 在优惠聚合阶段已转换为等效的 `FreeTimeRange`，按开始时间排序后与 FREE_RANGE 统一处理。

### 核心算法

```
假设优惠按开始时间排序：A, B, C

Step 0: 计算全优惠结果 R0
Step 1: 去掉优惠A，计算结果 R1
Step 2: 去掉优惠B，计算结果 R2
Step 3: 去掉优惠C，计算结果 R3

等效金额计算：
- 优惠A = R1.totalAmount - R0.totalAmount
- 优惠B = R2.totalAmount - R1.totalAmount
- 优惠C = R3.totalAmount - R2.totalAmount
```

### BillingService 拆分

#### SegmentContext（新增）

```java
package cn.shang.charging.billing.pojo;

/**
 * 分段计算上下文
 * 包含计算所需的所有信息，可独立计算
 */
@Data
@Builder
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

#### BillingService 新增方法

```java
/**
 * 准备分段上下文
 * 执行分段构建、规则解析、优惠聚合
 *
 * @param request 计费请求
 * @return 分段上下文列表
 */
public List<SegmentContext> prepareContexts(BillingRequest request) {
    List<SegmentContext> contexts = new ArrayList<>();

    // 复用现有 calculate() 中的逻辑
    List<BillingSegment> segments = segmentBuilder.buildSegments(request);

    for (BillingSegment segment : segments) {
        CalculationWindow window = CalculationWindowFactory.create(...);
        RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(...);
        List<PromotionRuleConfig> promotionRules = billingConfigResolver.resolvePromotionRules(...);
        BConstants.BillingMode billingMode = billingConfigResolver.resolveBillingMode(...);

        BillingContext billingContext = BillingContext.builder()
            .segment(segment)
            .window(window)
            .chargingRule(chargingRule)
            .promotionRules(promotionRules)
            .externalPromotions(request.getExternalPromotions())
            .billingMode(billingMode)
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

**注意**：原有 `calculate()` 方法保持不变，内部调用拆分后的方法。

**限制说明**：`prepareContexts()` 目前仅支持 FROM_SCRATCH 模式。CONTINUE 模式涉及进度恢复、规则状态恢复等复杂逻辑，后续版本再支持。调用等效金额计算时应确保使用 FROM_SCRATCH 模式。

#### PromotionAggregateUtil（新增）

```java
package cn.shang.charging.promotion;

/**
 * 优惠聚合工具类
 */
public class PromotionAggregateUtil {

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
            .mapToLong(u -> u.getGrantedMinutes() != null ? u.getGrantedMinutes() : 0)
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

### 等效金额计算实现

#### PromotionEquivalentCalculator

```java
package cn.shang.charging.wrapper;

/**
 * 优惠等效金额计算器
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

        // 3. 提取所有优惠时间段（FREE_RANGE + FREE_MINUTES 转换后的），按开始时间排序
        List<FreeTimeRange> sortedRanges = extractAndSortRanges(baseline);

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
            // 注意：这可能是负数（理论上不应该发生，但做保护）
            BigDecimal equivalent = currentAmount.subtract(previousAmount);
            if (equivalent.compareTo(BigDecimal.ZERO) < 0) {
                equivalent = BigDecimal.ZERO;  // 保护：等效金额不应为负
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

#### BillingTemplate 新增方法

```java
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

### 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| 无优惠 | 返回空 Map |
| 优惠未被使用（等效金额=0）| 返回 Map 包含该优惠，值为 BigDecimal.ZERO |
| 优惠重叠 | 按聚合后的实际使用时间段计算，可能合并为一个 ID |
| 负等效金额 | 钳制为 BigDecimal.ZERO（理论上不应发生）|
| 多分段不同优惠 | 按优惠 ID 统一聚合计算 |

### 与现有 PromotionSavingsAnalyzer 的关系

| 类 | 方法 | 用途 |
|---|------|------|
| PromotionSavingsAnalyzer | 从 BillingUnit 推算 | 快速估算，适用于简单场景 |
| PromotionEquivalentCalculator | 消去法精确计算 | 精确结果，适用于复杂场景 |

**建议**：保留两者，调用方根据需求选择。等效金额计算功能应优先使用 `PromotionEquivalentCalculator`。

---

## 优化方案记录（未来方向）

### CONTINUE 模式优化

当优惠数量较多时（>3个），可以考虑使用 CONTINUE 模式优化：

**核心思路**：按优惠时间边界分段，利用 CONTINUE 模式渐进计算，减少重复计算量。

```
假设计费时间 08:00-18:00，优惠 A(09:00-10:00), B(11:00-12:00)

Step 1: 计算 08:00-10:00 无优惠 → R1
Step 2: CONTINUE 10:00-12:00
        ├── 无优惠分支 → R2
        └── 有优惠B分支 → RR2
Step 3: CONTINUE 12:00-18:00
        ├── 从 R2 继续 → R2R3
        ├── 从 R2 有优惠C → R2RR3
        └── 从 RR2 有优惠C → RR2RR3

等效金额：
- 优惠A = (R1 + RR2RR3) - 全优惠结果
- 优惠B = (R1 + R2RR3) - 全优惠结果 - 优惠A
```

**优势**：计算量从 O(N×T) 降为 O(N×T/N)

**适用场景**：优惠数量 > 3 个时收益明显

---

## 改动清单

### core 模块

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `SegmentContext.java` | 新增 | 分段上下文 |
| `PromotionAggregateUtil.java` | 新增 | 优惠聚合工具类 |
| `BillingService.java` | 修改 | 新增 prepareContexts/calculateWithContexts 方法 |
| `ResultAssembler.java` | 修改 | 删除 filterByQueryTime 方法 |

### billing-api 模块

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `CalculationWithQueryResult.java` | 新增 | 双结果返回类 |
| `BillingResultViewer.java` | 新增 | 结果视图处理器 |
| `PromotionEquivalentCalculator.java` | 新增 | 等效金额计算器 |
| `BillingTemplate.java` | 修改 | 新增 calculateWithQuery/calculatePromotionEquivalents 方法 |

---

## 使用示例

### 场景1：实时显示当前费用

```java
// 计算到 10:00，但只返回 09:00 的结果
BillingRequest request = BillingRequest.builder()
    .beginTime(LocalDateTime.of(2024, 1, 1, 8, 0))
    .endTime(LocalDateTime.of(2024, 1, 1, 10, 0))
    .build();

CalculationWithQueryResult result = billingTemplate.calculateWithQuery(
    request,
    LocalDateTime.of(2024, 1, 1, 9, 0)
);

// 展示
System.out.println("当前费用: " + result.getQueryResult().getFinalAmount());

// 存储 CONTINUE 进度
save(result.getCalculationResult().getCarryOver());
```

### 场景2：分析优惠等效金额

```java
BillingRequest request = BillingRequest.builder()
    .beginTime(LocalDateTime.of(2024, 1, 1, 8, 0))
    .endTime(LocalDateTime.of(2024, 1, 1, 18, 0))
    .externalPromotions(List.of(
        createPromotion("A", "09:00", "10:00"),
        createPromotion("B", "11:00", "12:00")
    ))
    .schemeId("default")
    .build();

Map<String, BigDecimal> equivalents = billingTemplate.calculatePromotionEquivalents(request);

System.out.println("优惠A节省: " + equivalents.get("A"));
System.out.println("优惠B节省: " + equivalents.get("B"));
```

### 场景3：计算等效金额（使用 BillingRequest）

```java
Map<String, BigDecimal> equivalents = billingTemplate.calculatePromotionEquivalents(request);

System.out.println("优惠A节省: " + equivalents.get("A"));
System.out.println("优惠B节省: " + equivalents.get("B"));
```