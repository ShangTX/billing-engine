package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
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
import java.util.stream.Collectors;

/**
 * DURATION_GLOBAL 模式通用策略（层 2）：全局时长计费。
 * <p>
 * 规则族差异通过 {@link RuleSemantics} 注入，通用产出逻辑由 {@link DurationSupport} 承载。
 * 与 {@link DurationPeriodStrategy} 共享边界驱动输入，但输出按同质收费桶汇总；
 * 周期封顶按完整周期与尾周期分别处理。
 * 复用 {@link BoundaryDrivenLoop} 公共调度层，不继承 {@code AbstractTimeBasedRule}。
 * <p>
 * FREE_MINUTES 时段化（TODO-20260706-001）：免费段参与边界驱动与收费分钟扣除；
 * 最终仅输出收费汇总桶，免费信息通过 PromotionUsage 追踪。
 * <p>
 * FREE_MINUTES 在本策略中按每条优惠的 allocationMode 分配。默认 FROM_START 从窗口起点附近填充；
 * CHARGED_TIME / HIGHEST_PRICE 会用 {@link RuleSemantics#priceAt} + {@link RuleSemantics#periodBoundaryProvider}
 * 把窗口切成同价时段，并只填充单价大于 0 的收费时段。高价分配用规则私有价格语义，
 * 不泄漏到聚合层。
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
     * 产出：DurationSegment 列表 + FREE_RANGE/FREE_MINUTES PromotionUsage。
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

        List<FreeTimeRange> explicitFreeRanges = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();

        // FREE_MINUTES 分配：免费段参与边界驱动，GLOBAL 最终只输出收费汇总桶。
        GlobalFreeMinutesAllocation minuteAllocation = allocateFreeMinutes(
                semantics, config, cycleOrigin, promotionAggregate, calcBegin, calcEnd, explicitFreeRanges);

        List<FreeTimeRange> freeTimeRanges = RuleSupport.filterActiveFreeRanges(
                minuteAllocation.mergedFreeRanges, context.getEndTime());

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
                freeTimeRanges, calcBegin, calcEnd);
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        List<PromotionUsage> freeMinutesUsages = minuteAllocation.promotionUsages != null
                ? minuteAllocation.promotionUsages : List.of();
        allUsages.addAll(RuleSupport.filterActivePromotionUsages(
                freeMinutesUsages, minuteAllocation.mergedFreeRanges, freeTimeRanges));

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

    // ==================== FREE_MINUTES 分配 ====================

    /**
     * GLOBAL 免费分钟分配结果：合并后的免费段 + FREE_MINUTES usage。
     */
    private static final class GlobalFreeMinutesAllocation {
        /** FREE_RANGE + FREE_MINUTES 生成段合并后的最终免费段（已排序，参与边界驱动）。 */
        final List<FreeTimeRange> mergedFreeRanges;
        /** FREE_MINUTES 的 PromotionUsage（含 granted/used minutes、usedFrom/usedTo）。 */
        final List<PromotionUsage> promotionUsages;

        GlobalFreeMinutesAllocation(List<FreeTimeRange> mergedFreeRanges, List<PromotionUsage> promotionUsages) {
            this.mergedFreeRanges = mergedFreeRanges;
            this.promotionUsages = promotionUsages;
        }
    }

    /**
     * 同价子窗口：[begin, end) 区间内单价一致，用于价格感知 FREE_MINUTES。
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
     * GLOBAL 模式 FREE_MINUTES 分配。
     * <p>
     * 步骤：
     * <ol>
     *   <li>按 priority 对所有 FREE_MINUTES 排序</li>
     *   <li>FROM_START 从窗口起点顺序消费未占用空隙</li>
     *   <li>CHARGED_TIME / HIGHEST_PRICE 用 periodBoundaryProvider + priceAt 切同价子窗口，
     *       只消费单价大于 0 的未占用空隙</li>
     *   <li>FREE_MINUTES 生成段与 FREE_RANGE 合并，参与边界驱动</li>
     * </ol>
     *
     * @param explicitFreeRanges 显式免费段（FREE_RANGE，已合并）
     */
    private static <C extends RuleConfig> GlobalFreeMinutesAllocation allocateFreeMinutes(
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin,
            PromotionAggregate promotionAggregate,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            List<FreeTimeRange> explicitFreeRanges) {

        List<FreeMinutes> minutesList = promotionAggregate != null && promotionAggregate.getFreeMinutesList() != null
                ? promotionAggregate.getFreeMinutesList() : List.of();
        if (minutesList.isEmpty()) {
            return new GlobalFreeMinutesAllocation(explicitFreeRanges, List.of());
        }

        boolean hasPriceAware = minutesList.stream().anyMatch(minutes -> !RuleSupport.isFromStartAllocation(minutes));
        List<PriceSubWindow> subWindows = hasPriceAware
                ? buildPriceSubWindows(semantics, config, cycleOrigin, calcBegin, calcEnd)
                : List.of();

        // 占用集合：显式 FREE_RANGE + 已分配的 FREE_MINUTES 段（逐个加入）。
        List<FreeTimeRange> occupied = new ArrayList<>(explicitFreeRanges);
        List<FreeTimeRange> generatedMinuteRanges = new ArrayList<>();
        List<PromotionUsage> minuteUsages = new ArrayList<>();

        List<FreeMinutes> sortedMinutes = new ArrayList<>(minutesList);
        sortedMinutes.sort(Comparator.comparing(FreeMinutes::getPriority,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (FreeMinutes minutes : sortedMinutes) {
            int granted = minutes.getMinutes() != null ? minutes.getMinutes() : 0;
            if (granted <= 0) {
                continue;
            }
            int remaining = granted;
            LocalDateTime usedFrom = null;
            LocalDateTime usedTo = null;
            long usedMinutes = 0;

            BConstants.FreeMinutesAllocationMode mode = RuleSupport.freeMinutesAllocationMode(minutes);
            List<PriceSubWindow> orderedSubWindows = allocationWindows(subWindows, calcBegin, calcEnd, mode);
            for (PriceSubWindow sw : orderedSubWindows) {
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
                    FreeTimeRange minuteRange = new FreeTimeRange()
                            .setId(minutes.getId())
                            .setBeginTime(segBegin)
                            .setEndTime(segEnd)
                            .setPriority(minutes.getPriority() != null ? minutes.getPriority() : 0)
                            .setSource(minutes.getSource())
                            .setActivationMode(minutes.getActivationMode())
                            .setPromotionType(BConstants.PromotionType.FREE_MINUTES);
                    generatedMinuteRanges.add(minuteRange);
                    occupied.add(minuteRange);
                    if (usedFrom == null) {
                        usedFrom = segBegin;
                    }
                    usedTo = segEnd;
                    usedMinutes += consume;
                    remaining -= consume;
                }
            }

            minuteUsages.add(PromotionUsage.builder()
                    .promotionId(minutes.getId())
                    .type(BConstants.PromotionType.FREE_MINUTES)
                    .source(minutes.getSource())
                    .grantedMinutes(granted)
                    .usedMinutes(usedMinutes)
                    .usedFrom(usedFrom)
                    .usedTo(usedTo)
                    .build());
        }

        List<FreeTimeRange> allRanges = new ArrayList<>(explicitFreeRanges);
        allRanges.addAll(generatedMinuteRanges);
        List<FreeTimeRange> merged = new FreeTimeRangeMerger().merge(
                allRanges, calcBegin, calcEnd).getMergedRanges();

        return new GlobalFreeMinutesAllocation(merged, minuteUsages);
    }

    private static List<PriceSubWindow> allocationWindows(
            List<PriceSubWindow> subWindows,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            BConstants.FreeMinutesAllocationMode mode) {
        if (mode == BConstants.FreeMinutesAllocationMode.FROM_START) {
            return List.of(new PriceSubWindow(calcBegin, calcEnd, BigDecimal.ZERO));
        }
        List<PriceSubWindow> ordered = subWindows.stream()
                .filter(DurationGlobalStrategy::isChargeable)
                .collect(Collectors.toCollection(ArrayList::new));
        if (mode == BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE) {
            ordered.sort(Comparator
                    .comparing((PriceSubWindow w) -> w.price, Comparator.reverseOrder())
                    .thenComparing(w -> w.begin));
        } else {
            ordered.sort(Comparator.comparing(w -> w.begin));
        }
        return ordered;
    }

    private static boolean isChargeable(PriceSubWindow window) {
        return window.price != null && window.price.signum() > 0;
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
