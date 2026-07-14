package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DURATION_GLOBAL 模式通用策略（层 2）：全局时长计费。
 * <p>
 * 规则族差异通过 {@link RuleSemantics} 注入，通用产出逻辑由 {@link DurationSupport} 承载。
 * 与 {@link DurationPeriodStrategy} 共享切分模型，仅封顶数学不同：
 * 无周期边界切断（segment 跨周期合并），周期封顶 = cap × 周期数（全局倍乘）。
 * 复用 {@link BoundaryDrivenLoop} 公共调度层，不继承 {@code AbstractTimeBasedRule}。
 * <p>
 * FREE_MINUTES 时段化（TODO-20260706-001）：与 PERIOD 同路径，免费段独立，DurationSegment 同质。
 * <p>
 * SMART_FREE_MINUTES（TODO-20260706-002 阶段5）：本策略独享消费。用 {@link RuleSemantics#priceAt}
 * + {@link RuleSemantics#periodBoundaryProvider} 把窗口切成同价时段，按单价降序消费
 * SMART_FREE_MINUTES（从时段起点切），产出免费段与普通免费段（FREE_RANGE + FREE_MINUTES 时段化）
 * 合并参与边界驱动。优先高价分配用规则私有价格语义，不泄漏到聚合层。
 * <p>
 * TODO-20260706-002 阶段4：从 DayNightDurationStrategy 拆出通用 GLOBAL 策略。
 */
public final class DurationGlobalStrategy {

    private DurationGlobalStrategy() {
    }

    /**
     * GLOBAL 模式计费入口。
     * <p>
     * 边界来源：时段边界 + 免费段起止 + calcEnd（不含周期边界，segment 跨周期合并）。
     * 段构造：免费段判定 + {@link RuleSemantics#priceAt}。
     * 产出：DurationSegment 列表 + FREE_RANGE/FREE_MINUTES/SMART_FREE_MINUTES PromotionUsage。
     */
    public static <C extends RuleConfig> BillingSegmentResult calculate(
            RuleSemantics<C> semantics,
            BillingContext context,
            C config,
            PromotionAggregate promotionAggregate) {

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();
        LocalDateTime cycleOrigin = semantics.cycleOrigin(context);
        BigDecimal cycleCap = semantics.cycleCap(config);

        // FREE_MINUTES 时段化（TODO-20260706-001：PERIOD/GLOBAL 统一，免费段独立，DurationSegment 同质）
        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> regularFreeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // SMART_FREE_MINUTES 优先高价分配（TODO-20260706-002 阶段5）
        SmartFreeMinutesAllocation smartAllocation = allocateSmartFreeMinutes(
                semantics, config, cycleOrigin, promotionAggregate, calcBegin, calcEnd, regularFreeRanges);

        // 合并常规免费段 + SMART 免费段，统一参与边界驱动
        List<FreeTimeRange> freeTimeRanges = smartAllocation.mergedFreeRanges;

        // 边界来源：时段边界 + 免费段起止 + calcEnd（无周期边界，segment 跨周期合并）
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(semantics.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查免费时段
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), range.getRangeType(), null);
                }
            }
            // 计算单元单价
            BigDecimal unitPrice = semantics.priceAt(current, next, config, cycleOrigin);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice, false, null, null);
        });

        // 转换为 DurationSegment（时段封顶 × 周期数 + 周期封顶 × 周期数）
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        DurationSupport.DurationResult durationResult =
                DurationSupport.buildGlobalMode(segments, totalMinutes, cycleCap, semantics, config, cycleOrigin);

        // 产出 FREE_RANGE 的 PromotionUsage（equivalentAmount 由 PromotionEquivalentCalculator 消去法按需回填）
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                regularFreeRanges, calcBegin, calcEnd);
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        // FREE_MINUTES usage：PERIOD/GLOBAL 统一来自时段化
        List<PromotionUsage> freeMinutesUsages = materialized != null && materialized.getPromotionUsages() != null
                ? materialized.getPromotionUsages() : List.of();
        allUsages.addAll(freeMinutesUsages);
        // SMART_FREE_MINUTES usage：来自优先高价分配
        allUsages.addAll(smartAllocation.promotionUsages);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(durationResult.chargedAmount)
                .billingUnits(List.of())  // 时长模式不产出 BillingUnit
                .durationSegments(durationResult.segments)
                .calculationMode(BConstants.CalculationMode.DURATION_GLOBAL)
                .cycleCapApplied(durationResult.cycleCapApplied)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    // ==================== SMART_FREE_MINUTES 优先高价分配（TODO-20260706-002 阶段5） ====================

    /**
     * SMART_FREE_MINUTES 分配结果：合并后的免费段 + SMART usage。
     */
    private static final class SmartFreeMinutesAllocation {
        /** 常规免费段 + SMART 免费段合并后的最终免费段（已排序，参与边界驱动）。 */
        final List<FreeTimeRange> mergedFreeRanges;
        /** SMART_FREE_MINUTES 的 PromotionUsage（含 granted/used minutes、usedFrom/usedTo）。 */
        final List<PromotionUsage> promotionUsages;

        SmartFreeMinutesAllocation(List<FreeTimeRange> mergedFreeRanges, List<PromotionUsage> promotionUsages) {
            this.mergedFreeRanges = mergedFreeRanges;
            this.promotionUsages = promotionUsages;
        }
    }

    /**
     * 同价子窗口：[begin, end) 区间内单价一致，用于按单价降序消费 SMART_FREE_MINUTES。
     */
    private static final class PriceSubWindow {
        final LocalDateTime begin;
        final LocalDateTime end;
        final BigDecimal price;

        PriceSubWindow(LocalDateTime begin, LocalDateTime end, BigDecimal price) {
            this.begin = begin;
            this.end = end;
            this.price = price;
        }

        long durationMinutes() {
            return Duration.between(begin, end).toMinutes();
        }
    }

    /**
     * SMART_FREE_MINUTES 优先高价分配。
     * <p>
     * 步骤（spec 5.5）：
     * <ol>
     *   <li>用 periodBoundaryProvider + priceAt 把窗口切成同价子窗口</li>
     *   <li>按单价降序排序子窗口（价格相同按时间顺序，稳定）</li>
     *   <li>从高价子窗口消费 SMART_FREE_MINUTES，跳过已被常规免费段（FREE_RANGE + FREE_MINUTES 时段化）
     *       占用的部分，从子窗口起点切（实际从子窗口内首个未占用点切）</li>
     *   <li>SMART 免费段与常规免费段合并，参与边界驱动</li>
     * </ol>
     * 同时存在多种 SMART_FREE_MINUTES 时，按 priority 排序（数字小优先），各自分配，跳过已占用时段
     * （含常规免费段 + 已分配的 SMART 段）。
     *
     * @param regularFreeRanges 常规免费段（FREE_RANGE + 时段化 FREE_MINUTES，已合并）
     */
    private static <C extends RuleConfig> SmartFreeMinutesAllocation allocateSmartFreeMinutes(
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin,
            PromotionAggregate promotionAggregate,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            List<FreeTimeRange> regularFreeRanges) {

        List<FreeMinutes> smartList = promotionAggregate != null && promotionAggregate.getSmartFreeMinutesList() != null
                ? promotionAggregate.getSmartFreeMinutesList() : List.of();
        if (smartList.isEmpty()) {
            return new SmartFreeMinutesAllocation(regularFreeRanges, List.of());
        }

        // 1. 切同价子窗口（仅用 periodBoundaryProvider + calcEnd，不含免费段边界）
        List<PriceSubWindow> subWindows = buildPriceSubWindows(semantics, config, cycleOrigin, calcBegin, calcEnd);

        // 2. 按单价降序排序（价格相同保持时间顺序，稳定排序）
        List<PriceSubWindow> sortedByPriceDesc = new ArrayList<>(subWindows);
        sortedByPriceDesc.sort(Comparator
                .comparing((PriceSubWindow w) -> w.price, Comparator.reverseOrder())
                .thenComparing(w -> w.begin));

        // 3. 占用集合：常规免费段（已合并排序）+ 已分配的 SMART 段（逐个加入）
        List<FreeTimeRange> occupied = new ArrayList<>(regularFreeRanges);
        List<FreeTimeRange> smartRanges = new ArrayList<>();
        List<PromotionUsage> smartUsages = new ArrayList<>();

        // SMART_FREE_MINUTES 按 priority 排序（数字小优先），各自分配
        List<FreeMinutes> sortedSmart = new ArrayList<>(smartList);
        sortedSmart.sort(Comparator.comparing(FreeMinutes::getPriority,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (FreeMinutes smart : sortedSmart) {
            int granted = smart.getMinutes() != null ? smart.getMinutes() : 0;
            if (granted <= 0) {
                continue;
            }
            int remaining = granted;
            LocalDateTime usedFrom = null;
            LocalDateTime usedTo = null;
            long usedMinutes = 0;

            // 按价格降序遍历子窗口，消费 SMART 分钟
            for (PriceSubWindow sw : sortedByPriceDesc) {
                if (remaining <= 0) break;
                // 计算子窗口内的未占用区间
                List<LocalDateTime[]> freeGaps = computeFreeGaps(sw.begin, sw.end, occupied);
                for (LocalDateTime[] gap : freeGaps) {
                    if (remaining <= 0) break;
                    long gapMinutes = Duration.between(gap[0], gap[1]).toMinutes();
                    if (gapMinutes <= 0) continue;
                    int consume = (int) Math.min(remaining, gapMinutes);
                    LocalDateTime segBegin = gap[0];
                    LocalDateTime segEnd = gap[0].plusMinutes(consume);
                    FreeTimeRange smartRange = new FreeTimeRange()
                            .setId(smart.getId())
                            .setBeginTime(segBegin)
                            .setEndTime(segEnd)
                            .setPriority(smart.getPriority() != null ? smart.getPriority() : 0)
                            .setSource(smart.getSource())
                            .setPromotionType(BConstants.PromotionType.SMART_FREE_MINUTES);
                    smartRanges.add(smartRange);
                    occupied.add(smartRange);
                    if (usedFrom == null) {
                        usedFrom = segBegin;
                    }
                    usedTo = segEnd;
                    usedMinutes += consume;
                    remaining -= consume;
                }
            }

            smartUsages.add(PromotionUsage.builder()
                    .promotionId(smart.getId())
                    .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                    .source(smart.getSource())
                    .grantedMinutes(granted)
                    .usedMinutes(usedMinutes)
                    .usedFrom(usedFrom)
                    .usedTo(usedTo)
                    .build());
        }

        // 4. 合并常规免费段 + SMART 免费段（用 FreeTimeRangeMerger 处理优先级覆盖，再合并相邻）
        List<FreeTimeRange> allRanges = new ArrayList<>(regularFreeRanges);
        allRanges.addAll(smartRanges);
        List<FreeTimeRange> merged = new FreeTimeRangeMerger().merge(
                allRanges, calcBegin, calcEnd).getMergedRanges();

        return new SmartFreeMinutesAllocation(merged, smartUsages);
    }

    /**
     * 用 periodBoundaryProvider + calcEnd 把 [calcBegin, calcEnd] 切成同价子窗口。
     * 每个子窗口内 priceAt 返回相同单价。
     */
    private static <C extends RuleConfig> List<PriceSubWindow> buildPriceSubWindows(
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd) {
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(semantics.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        List<HomogeneousSegment> segs = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers,
                (current, next) -> new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO, false, null, null));
        List<PriceSubWindow> subWindows = new ArrayList<>();
        for (HomogeneousSegment seg : segs) {
            BigDecimal price = semantics.priceAt(seg.getBeginTime(), seg.getEndTime(), config, cycleOrigin);
            subWindows.add(new PriceSubWindow(seg.getBeginTime(), seg.getEndTime(), price));
        }
        return subWindows;
    }

    /**
     * 计算 [windowBegin, windowEnd) 内未被 occupied 段覆盖的区间（未占用间隙）。
     * 返回的区间已排序、互斥，且严格在 [windowBegin, windowEnd) 内。
     */
    private static List<LocalDateTime[]> computeFreeGaps(
            LocalDateTime windowBegin,
            LocalDateTime windowEnd,
            List<FreeTimeRange> occupied) {
        List<LocalDateTime[]> gaps = new ArrayList<>();
        // 收集并排序占用段在 [windowBegin, windowEnd] 内的部分
        List<LocalDateTime[]> occ = new ArrayList<>();
        for (FreeTimeRange r : occupied) {
            LocalDateTime b = r.getBeginTime().isBefore(windowBegin) ? windowBegin : r.getBeginTime();
            LocalDateTime e = r.getEndTime().isAfter(windowEnd) ? windowEnd : r.getEndTime();
            if (b.isBefore(e)) {
                occ.add(new LocalDateTime[]{b, e});
            }
        }
        occ.sort(Comparator.comparing(a -> a[0]));
        LocalDateTime cursor = windowBegin;
        for (LocalDateTime[] range : occ) {
            if (cursor.isBefore(range[0])) {
                gaps.add(new LocalDateTime[]{cursor, range[0]});
            }
            if (cursor.isBefore(range[1])) {
                cursor = range[1];
            }
        }
        if (cursor.isBefore(windowEnd)) {
            gaps.add(new LocalDateTime[]{cursor, windowEnd});
        }
        return gaps;
    }
}
