package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.DurationSupport;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSupport;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DayNight DURATION_GLOBAL: aggregate chargeable minutes by homogeneous day/night price bucket,
 * then charge each bucket once and apply a tail-aware daily cap.
 */
final class DayNightDurationGlobalStrategy {

    private static final DayNightSemantics SEMANTICS = new DayNightSemantics();

    private DayNightDurationGlobalStrategy() {
    }

    static BillingSegmentResult calculate(
            BillingContext context,
            DayNightConfig config,
            PromotionAggregate promotionAggregate) {

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();
        LocalDateTime cycleOrigin = SEMANTICS.cycleOrigin(context);

        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> regularFreeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        SmartFreeMinutesAllocation smartAllocation = allocateSmartFreeMinutes(
                config, cycleOrigin, promotionAggregate, calcBegin, calcEnd, regularFreeRanges);

        List<FreeTimeRange> freeTimeRanges = RuleSupport.filterActiveFreeRanges(
                smartAllocation.mergedFreeRanges, context.getEndTime());
        List<HomogeneousSegment> segments = buildSegments(config, cycleOrigin, calcBegin, calcEnd, freeTimeRanges);

        DurationSupport.DurationResult durationResult =
                buildAggregatedGlobalResult(segments, config, cycleOrigin, calcBegin, calcEnd);

        List<FreeTimeRange> activeRegularFreeRanges = RuleSupport.filterActiveFreeRanges(
                regularFreeRanges, context.getEndTime());
        List<PromotionUsage> allUsages = new ArrayList<>(PromotionAggregateUtil.buildFreeRangeUsages(
                activeRegularFreeRanges, calcBegin, calcEnd));
        List<PromotionUsage> freeMinutesUsages = materialized.getPromotionUsages() != null
                ? materialized.getPromotionUsages() : List.of();
        allUsages.addAll(RuleSupport.filterActivePromotionUsages(
                freeMinutesUsages, regularFreeRanges, activeRegularFreeRanges));
        allUsages.addAll(RuleSupport.filterActivePromotionUsages(
                smartAllocation.promotionUsages, smartAllocation.mergedFreeRanges, freeTimeRanges));

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(durationResult.chargedAmount)
                .billingUnits(List.of())
                .durationSegments(durationResult.segments)
                .calculationMode(BConstants.CalculationMode.DURATION_GLOBAL)
                .cycleCapApplied(durationResult.cycleCapApplied)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    private static List<HomogeneousSegment> buildSegments(
            DayNightConfig config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            List<FreeTimeRange> freeTimeRanges) {
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(SEMANTICS.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        return BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), range.getRangeType(), null);
                }
            }
            BigDecimal unitPrice = SEMANTICS.priceAt(current, next, config, cycleOrigin);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice, false, null, null);
        });
    }

    private static DurationSupport.DurationResult buildAggregatedGlobalResult(
            List<HomogeneousSegment> segments,
            DayNightConfig config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd) {
        Map<BucketKey, BucketAccumulator> totalBuckets = new LinkedHashMap<>();
        for (HomogeneousSegment seg : segments) {
            if (!seg.isFree()) {
                bucket(totalBuckets, seg, config, cycleOrigin).minutes += seg.durationMinutes();
            }
        }

        BigDecimal totalAmount = chargeBuckets(totalBuckets, config);
        BigDecimal capLimit = computeTailAwareCapLimit(segments, config, cycleOrigin, calcBegin, calcEnd);
        BigDecimal finalAmount = capLimit != null && totalAmount.compareTo(capLimit) > 0 ? capLimit : totalAmount;

        List<DurationSegment> durationSegments = new ArrayList<>();
        for (BucketAccumulator bucket : totalBuckets.values()) {
            if (bucket.minutes <= 0) {
                continue;
            }
            BigDecimal amount = chargeMinutes(bucket.unitPrice, bucket.minutes, config);
            durationSegments.add(new DurationSegment(
                    null,
                    null,
                    bucket.periodLabel,
                    bucket.minutes,
                    bucket.unitPrice,
                    amount,
                    null,
                    null,
                    amount));
        }
        return new DurationSupport.DurationResult(durationSegments, capLimit, finalAmount);
    }

    private static BigDecimal computeTailAwareCapLimit(
            List<HomogeneousSegment> segments,
            DayNightConfig config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd) {
        BigDecimal cycleCap = SEMANTICS.cycleCap(config);
        if (cycleCap == null || cycleCap.signum() <= 0) {
            return null;
        }

        long realMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        int effectiveMinutes = Math.max((int) realMinutes - DurationSupport.sumBubbleDuration(segments), 0);
        if (effectiveMinutes <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        int cycleMinutes = SEMANTICS.cycleMinutes();
        int fullCycles = effectiveMinutes / cycleMinutes;
        int tailMinutes = effectiveMinutes % cycleMinutes;
        BigDecimal fullCycleCap = cycleCap.multiply(BigDecimal.valueOf(fullCycles));

        if (tailMinutes == 0) {
            return fullCycleCap.setScale(2, RoundingMode.HALF_UP);
        }

        Map<BucketKey, BucketAccumulator> tailBuckets = collectTailChargeableBuckets(
                segments, config, cycleOrigin, fullCycles * cycleMinutes, effectiveMinutes);
        BigDecimal tailCharge = chargeBuckets(tailBuckets, config);
        BigDecimal cappedTail = tailCharge.compareTo(cycleCap) > 0 ? cycleCap : tailCharge;
        return fullCycleCap.add(cappedTail).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<BucketKey, BucketAccumulator> collectTailChargeableBuckets(
            List<HomogeneousSegment> segments,
            DayNightConfig config,
            LocalDateTime cycleOrigin,
            int tailStartOffset,
            int effectiveMinutes) {
        Map<BucketKey, BucketAccumulator> tailBuckets = new LinkedHashMap<>();
        int effectiveCursor = 0;
        for (HomogeneousSegment seg : segments) {
            int segmentMinutes = seg.durationMinutes();
            if (seg.isBubble()) {
                continue;
            }

            int segmentEffectiveStart = effectiveCursor;
            int segmentEffectiveEnd = effectiveCursor + segmentMinutes;
            int overlapStart = Math.max(segmentEffectiveStart, tailStartOffset);
            int overlapEnd = Math.min(segmentEffectiveEnd, effectiveMinutes);
            int overlapMinutes = Math.max(overlapEnd - overlapStart, 0);
            if (overlapMinutes > 0 && !seg.isFree()) {
                bucket(tailBuckets, seg, config, cycleOrigin).minutes += overlapMinutes;
            }
            effectiveCursor = segmentEffectiveEnd;
        }
        return tailBuckets;
    }

    private static BucketAccumulator bucket(
            Map<BucketKey, BucketAccumulator> buckets,
            HomogeneousSegment seg,
            DayNightConfig config,
            LocalDateTime cycleOrigin) {
        String periodLabel = SEMANTICS.periodLabel(seg.getBeginTime(), config, cycleOrigin);
        BucketKey key = new BucketKey(periodLabel, seg.getUnitPrice());
        return buckets.computeIfAbsent(key, ignored -> new BucketAccumulator(periodLabel, seg.getUnitPrice()));
    }

    private static BigDecimal chargeBuckets(Map<BucketKey, BucketAccumulator> buckets, DayNightConfig config) {
        BigDecimal total = BigDecimal.ZERO;
        for (BucketAccumulator bucket : buckets.values()) {
            total = total.add(chargeMinutes(bucket.unitPrice, bucket.minutes, config));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal chargeMinutes(BigDecimal unitPrice, int minutes, DayNightConfig config) {
        if (minutes <= 0 || unitPrice == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        int unitMinutes = config.getUnitMinutes();
        int fullUnits = minutes / unitMinutes;
        int remainder = minutes % unitMinutes;
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(fullUnits));
        if (remainder > 0) {
            amount = amount.add(ContinuousStrategy.computeIncompleteCharge(
                    unitPrice,
                    remainder,
                    unitMinutes,
                    SEMANTICS.incompleteMode(config),
                    SEMANTICS.thresholdMinutes(config),
                    SEMANTICS.thresholdRatio(config)));
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record BucketKey(String periodLabel, BigDecimal unitPrice) {
    }

    private static final class BucketAccumulator {
        final String periodLabel;
        final BigDecimal unitPrice;
        int minutes;

        BucketAccumulator(String periodLabel, BigDecimal unitPrice) {
            this.periodLabel = periodLabel;
            this.unitPrice = unitPrice;
        }
    }

    private static final class SmartFreeMinutesAllocation {
        final List<FreeTimeRange> mergedFreeRanges;
        final List<PromotionUsage> promotionUsages;

        SmartFreeMinutesAllocation(List<FreeTimeRange> mergedFreeRanges, List<PromotionUsage> promotionUsages) {
            this.mergedFreeRanges = mergedFreeRanges;
            this.promotionUsages = promotionUsages;
        }
    }

    private static final class PriceSubWindow {
        final LocalDateTime begin;
        final LocalDateTime end;
        final BigDecimal price;

        PriceSubWindow(LocalDateTime begin, LocalDateTime end, BigDecimal price) {
            this.begin = begin;
            this.end = end;
            this.price = price;
        }
    }

    private static SmartFreeMinutesAllocation allocateSmartFreeMinutes(
            DayNightConfig config,
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

        List<PriceSubWindow> subWindows = buildPriceSubWindows(config, cycleOrigin, calcBegin, calcEnd);
        List<PriceSubWindow> sortedByPriceDesc = new ArrayList<>(subWindows);
        sortedByPriceDesc.sort(Comparator
                .comparing((PriceSubWindow w) -> w.price, Comparator.reverseOrder())
                .thenComparing(w -> w.begin));

        List<FreeTimeRange> occupied = new ArrayList<>(regularFreeRanges);
        List<FreeTimeRange> smartRanges = new ArrayList<>();
        List<PromotionUsage> smartUsages = new ArrayList<>();

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

            for (PriceSubWindow sw : sortedByPriceDesc) {
                if (remaining <= 0) {
                    break;
                }
                for (LocalDateTime[] gap : computeFreeGaps(sw.begin, sw.end, occupied)) {
                    if (remaining <= 0) {
                        break;
                    }
                    long gapMinutes = Duration.between(gap[0], gap[1]).toMinutes();
                    if (gapMinutes <= 0) {
                        continue;
                    }
                    int consume = (int) Math.min(remaining, gapMinutes);
                    LocalDateTime segBegin = gap[0];
                    LocalDateTime segEnd = gap[0].plusMinutes(consume);
                    FreeTimeRange smartRange = new FreeTimeRange()
                            .setId(smart.getId())
                            .setBeginTime(segBegin)
                            .setEndTime(segEnd)
                            .setPriority(smart.getPriority() != null ? smart.getPriority() : 0)
                            .setSource(smart.getSource())
                            .setActivationMode(smart.getActivationMode())
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

        List<FreeTimeRange> allRanges = new ArrayList<>(regularFreeRanges);
        allRanges.addAll(smartRanges);
        List<FreeTimeRange> merged = new FreeTimeRangeMerger().merge(
                allRanges, calcBegin, calcEnd).getMergedRanges();
        return new SmartFreeMinutesAllocation(merged, smartUsages);
    }

    private static List<PriceSubWindow> buildPriceSubWindows(
            DayNightConfig config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd) {
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(SEMANTICS.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        List<HomogeneousSegment> segs = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers,
                (current, next) -> new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO, false, null, null));
        List<PriceSubWindow> subWindows = new ArrayList<>();
        for (HomogeneousSegment seg : segs) {
            BigDecimal price = SEMANTICS.priceAt(seg.getBeginTime(), seg.getEndTime(), config, cycleOrigin);
            subWindows.add(new PriceSubWindow(seg.getBeginTime(), seg.getEndTime(), price));
        }
        return subWindows;
    }

    private static List<LocalDateTime[]> computeFreeGaps(
            LocalDateTime windowBegin,
            LocalDateTime windowEnd,
            List<FreeTimeRange> occupied) {
        List<LocalDateTime[]> gaps = new ArrayList<>();
        List<LocalDateTime[]> occupiedInWindow = new ArrayList<>();
        for (FreeTimeRange range : occupied) {
            LocalDateTime begin = range.getBeginTime().isBefore(windowBegin) ? windowBegin : range.getBeginTime();
            LocalDateTime end = range.getEndTime().isAfter(windowEnd) ? windowEnd : range.getEndTime();
            if (begin.isBefore(end)) {
                occupiedInWindow.add(new LocalDateTime[]{begin, end});
            }
        }
        occupiedInWindow.sort(Comparator.comparing(range -> range[0]));

        LocalDateTime cursor = windowBegin;
        for (LocalDateTime[] range : occupiedInWindow) {
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
