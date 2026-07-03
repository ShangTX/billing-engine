package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * `dayNight` 规则在时长计费类（PERIOD/GLOBAL）下的策略实现。
 * <p>
 * 承载时长计费的产出逻辑（{@link DurationSegment}）和封顶数学（周期内 / 全局 × 周期数），
 * 与 CONTINUOUS/UNIT_BASED 策略独立。PERIOD/GLOBAL 方法级分离（同切分模型，仅封顶数学不同）。
 * 复用 {@link BoundaryDrivenLoop} 公共调度层，不继承 {@code AbstractTimeBasedRule}。
 * <p>
 * 时长产出基础设施（{@link PeriodResolver}/{@link DurationResult}/buildDurationSegments*）
 * 从 {@code AbstractTimeBasedRule} 移来，因为只时长策略使用，不应让 CONTINUOUS 规则背着。
 */
final class DayNightDurationStrategy {

    private static final int MINUTES_PER_CYCLE = 1440;
    private static final String RULE_TYPE = "dayNight";

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();

    /**
     * 时长计费入口（从 {@code DayNightRule.calculateDurationMode} 移来）。
     */
    BillingSegmentResult calculate(BillingContext context,
                                   DayNightConfig config,
                                   PromotionAggregate promotionAggregate,
                                   BConstants.DurationMode durationMode) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();

        // 日夜时段边界 provider（PERIOD/GLOBAL 共用）
        BoundaryProvider dayNightBoundary = (current, end) -> {
            List<LocalDateTime> result = new ArrayList<>();
            LocalDateTime day = current.toLocalDate().atStartOfDay();
            // 检查今天和明天两天的 dayBegin/dayEnd，覆盖 current 到 end 的范围
            for (int d = 0; d <= 1; d++) {
                LocalDateTime dayBegin = day.plusMinutes(config.getDayBeginMinute());
                LocalDateTime dayEnd = day.plusMinutes(config.getDayEndMinute());
                if (dayBegin.isAfter(current) && !dayBegin.isAfter(end)) result.add(dayBegin);
                if (dayEnd.isAfter(current) && !dayEnd.isAfter(end)) result.add(dayEnd);
                day = day.plusDays(1);
            }
            return result;
        };

        // 边界来源
        List<BoundaryProvider> providers = new ArrayList<>();
        if (durationMode == BConstants.DurationMode.PERIOD) {
            // PERIOD 模式：周期边界 + 日夜边界 + 免费段 + calcEnd
            providers.add(BoundaryProviders.cycleEnd(calcBegin, MINUTES_PER_CYCLE));
        }
        // GLOBAL 模式：不含周期边界，segment 跨周期合并
        providers.add(dayNightBoundary);
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查免费时段
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), null, null);
                }
            }
            // 计算日夜单价
            BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, next, config);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice, false, null, null, null);
        });

        // period 解析器：仅提供 day/night 标签，无 period 级封顶
        PeriodResolver periodResolver = new PeriodResolver() {
            @Override
            public int getPeriodIndex(LocalDateTime time) {
                int minute = time.getHour() * 60 + time.getMinute();
                int dayBegin = config.getDayBeginMinute();
                int dayEnd = config.getDayEndMinute();
                boolean inDay;
                if (dayBegin < dayEnd) {
                    inDay = minute >= dayBegin && minute < dayEnd;
                } else {
                    inDay = minute >= dayBegin || minute < dayEnd;
                }
                return inDay ? 0 : 1;
            }

            @Override
            public String getPeriodLabel(LocalDateTime time) {
                return getPeriodIndex(time) == 0 ? "day" : "night";
            }
        };

        // 转换为 DurationSegment
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        DurationResult durationResult;
        if (durationMode == BConstants.DurationMode.PERIOD) {
            durationResult = buildDurationSegmentsPeriodMode(segments, unitMinutes, maxCharge, periodResolver, config);
        } else {
            durationResult = buildDurationSegmentsGlobalMode(segments, unitMinutes, totalMinutes, maxCharge, periodResolver, config);
        }

        // 周期状态输出（时长模式不参与 CONTINUE，状态仅为格式一致）
        Map<String, Object> dayNightState = new HashMap<>();
        dayNightState.put("cycleIndex", 0);
        dayNightState.put("cycleAccumulated", BigDecimal.ZERO);
        dayNightState.put("cycleBoundary", calcBegin.plusMinutes(MINUTES_PER_CYCLE));
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(RULE_TYPE, dayNightState);

        // 产出 FREE_RANGE 的 PromotionUsage（equivalentAmount 从 DurationSegment.originalAmount 聚合）
        final List<DurationSegment> finalSegments = durationResult.segments;
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd,
                rangeId -> finalSegments.stream()
                        .filter(ds -> rangeId.equals(ds.freePromotionId()))
                        .map(DurationSegment::originalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        if (promotionAggregate != null && promotionAggregate.getUsages() != null) {
            allUsages.addAll(promotionAggregate.getUsages());
        }

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(durationResult.chargedAmount)
                .billingUnits(List.of())  // 时长模式不产出 BillingUnit
                .durationSegments(durationResult.segments)
                .durationMode(durationMode)
                .cycleCapApplied(durationResult.cycleCapApplied)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(calcBegin)
                .feeEffectiveEnd(calcEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    private void validateConfig(DayNightConfig config) {
        if (config.getMaxChargeOneDay() == null || config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }

    // ==================== 时长计费产出（从 AbstractTimeBasedRule 移来） ====================

    /**
     * 时段解析器：为时长模式提供 period 标识、标签和封顶信息。
     */
    interface PeriodResolver {
        /** 该时间点所属 period 的稳定标识（同一 period 类型跨周期返回相同值）。 */
        int getPeriodIndex(LocalDateTime time);

        /** 该 period 的人类可读标签（如 "day"/"night"/"period-1"）。 */
        String getPeriodLabel(LocalDateTime time);

        /** 该 period 的封顶金额（null 表示无封顶）。 */
        default BigDecimal getPeriodCap(LocalDateTime time) {
            return null;
        }
    }

    /**
     * 时长模式产出结果：DurationSegment 列表 + 周期封顶金额 + 周期封顶后实收。
     */
    static final class DurationResult {
        public final List<DurationSegment> segments;
        /** 周期封顶金额（null=无封顶或未配置），DurationSegment 不落盘周期封顶。 */
        public final BigDecimal cycleCapApplied;
        /** 周期封顶后的实收（= 各段 chargedAmount 之和与周期封顶取 min）。 */
        public final BigDecimal chargedAmount;

        DurationResult(List<DurationSegment> segments, BigDecimal cycleCapApplied, BigDecimal chargedAmount) {
            this.segments = segments;
            this.cycleCapApplied = cycleCapApplied;
            this.chargedAmount = chargedAmount;
        }
    }

    /**
     * 计算单个同质段的应收金额（时段封顶前）。免费段返回 0。
     */
    private static BigDecimal segmentCharge(HomogeneousSegment seg, int unitMinutes) {
        if (seg.isFree()) return BigDecimal.ZERO;
        BigDecimal price = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;
        if (unitMinutes <= 0) return BigDecimal.ZERO;
        return price.multiply(BigDecimal.valueOf(seg.durationMinutes()))
                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
    }

    /**
     * 计算单个同质段的按规则原价（封顶前，免费段也用规则单价算）。
     * 用于 {@link DurationSegment#originalAmount()}（等效优惠金额聚合）。
     */
    private BigDecimal segmentOriginalCharge(HomogeneousSegment seg, int unitMinutes, DayNightConfig config) {
        if (unitMinutes <= 0) return BigDecimal.ZERO;
        BigDecimal price = priceResolver.determineUnitPriceForContinuous(seg.getBeginTime(), seg.getEndTime(), config);
        return price.multiply(BigDecimal.valueOf(seg.durationMinutes()))
                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
    }

    /**
     * PERIOD 模式：周期内按时长计费。
     * <p>
     * - 时段封顶：周期内同 period 累计达 period.maxCharge，该 period 后续段 chargedAmount 削减（落盘）<br>
     * - 周期封顶：每周期内所有段 chargedAmount 之和达 maxChargeOneCycle，该周期实收 = min(cap, 之和)（不落盘到段）<br>
     * - 跨周期：period 累计每周期重置
     */
    private DurationResult buildDurationSegmentsPeriodMode(
            List<HomogeneousSegment> segments,
            int unitMinutes,
            BigDecimal cycleCap,
            PeriodResolver periodResolver,
            DayNightConfig config) {

        List<DurationSegment> result = new ArrayList<>();
        if (segments.isEmpty()) {
            return new DurationResult(result, cycleCap, BigDecimal.ZERO);
        }

        int cycleMinutes = MINUTES_PER_CYCLE;
        BigDecimal totalCharged = BigDecimal.ZERO;

        // 周期跟踪
        LocalDateTime cycleStart = segments.get(0).getBeginTime();
        BigDecimal cycleAccumulated = BigDecimal.ZERO;

        // period 跟踪（周期内）
        int currentPeriodIndex = -1;
        BigDecimal periodAccumulated = BigDecimal.ZERO;
        BigDecimal periodCap = null;
        String periodLabel = null;

        for (HomogeneousSegment seg : segments) {
            // 周期切换检测：段起点越过当前周期终点
            while (seg.getBeginTime().compareTo(cycleStart.plusMinutes(cycleMinutes)) >= 0) {
                // 结算刚结束的周期：min(cycleCap, cycleAccumulated)
                totalCharged = totalCharged.add(applyCycleCap(cycleCap, cycleAccumulated));
                cycleStart = cycleStart.plusMinutes(cycleMinutes);
                cycleAccumulated = BigDecimal.ZERO;
                // 周期切换后 period 累计重置
                currentPeriodIndex = -1;
                periodAccumulated = BigDecimal.ZERO;
                periodCap = null;
            }

            int segMinutes = seg.durationMinutes();
            BigDecimal charged = segmentCharge(seg, unitMinutes);

            // period 识别（周期内切换）
            if (periodResolver != null) {
                int newPeriodIndex = periodResolver.getPeriodIndex(seg.getBeginTime());
                if (newPeriodIndex != currentPeriodIndex) {
                    currentPeriodIndex = newPeriodIndex;
                    periodAccumulated = BigDecimal.ZERO;
                    periodCap = periodResolver.getPeriodCap(seg.getBeginTime());
                    periodLabel = periodResolver.getPeriodLabel(seg.getBeginTime());
                }
            }

            // 时段封顶（周期内累计，落盘到 chargedAmount）
            if (periodCap != null && !seg.isFree()) {
                BigDecimal newAccum = periodAccumulated.add(charged);
                if (newAccum.compareTo(periodCap) > 0) {
                    charged = periodCap.subtract(periodAccumulated);
                    if (charged.signum() < 0) charged = BigDecimal.ZERO;
                    periodAccumulated = periodCap;
                } else {
                    periodAccumulated = newAccum;
                }
            }

            cycleAccumulated = cycleAccumulated.add(charged);

            result.add(new DurationSegment(
                    seg.getBeginTime(),
                    seg.getEndTime(),
                    periodLabel,
                    seg.isFree() ? 0 : segMinutes,
                    seg.getUnitPrice(),
                    charged,
                    periodCap,
                    seg.isFree() ? seg.getFreePromotionId() : null,
                    segmentOriginalCharge(seg, unitMinutes, config)
            ));
        }

        // 结算最后一个周期
        totalCharged = totalCharged.add(applyCycleCap(cycleCap, cycleAccumulated));

        return new DurationResult(result, cycleCap, totalCharged);
    }

    /**
     * 应用周期封顶：返回 min(cycleCap, accumulated)。cycleCap 为 null 时直接返回 accumulated。
     */
    private static BigDecimal applyCycleCap(BigDecimal cycleCap, BigDecimal accumulated) {
        if (cycleCap == null || accumulated.compareTo(cycleCap) <= 0) {
            return accumulated;
        }
        return cycleCap;
    }

    /**
     * GLOBAL 模式：全局按时长计费。
     * <p>
     * - 时段封顶：同 period 类型全局累计达 period.maxCharge × 周期数，该 period 后续段削减（落盘）<br>
     * - 周期封顶：所有段 chargedAmount 之和达 maxChargeOneCycle × 周期数，实收 = min(cap×周期数, 总额)（不落盘到段）<br>
     * - 周期数 = ceil(总分钟数 / 周期分钟数)
     */
    private DurationResult buildDurationSegmentsGlobalMode(
            List<HomogeneousSegment> segments,
            int unitMinutes,
            long totalMinutes,
            BigDecimal cycleCap,
            PeriodResolver periodResolver,
            DayNightConfig config) {

        List<DurationSegment> result = new ArrayList<>();
        if (segments.isEmpty()) {
            return new DurationResult(result, cycleCap, BigDecimal.ZERO);
        }

        int cycleMinutes = MINUTES_PER_CYCLE;
        int cycleCount = (int) Math.ceil((double) totalMinutes / cycleMinutes);
        if (cycleCount < 1) cycleCount = 1;

        // 第一遍：计算每段原始应收，并按 period 类型累计
        BigDecimal[] rawCharges = new BigDecimal[segments.size()];
        Map<Integer, BigDecimal> periodRawTotal = new HashMap<>();   // 各 period 原始总额
        Map<Integer, BigDecimal> periodCapMap = new HashMap<>();     // 各 period 封顶
        Map<Integer, String> periodLabelMap = new HashMap<>();       // 各 period 标签

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = segmentCharge(seg, unitMinutes);
            rawCharges[i] = charged;

            if (periodResolver != null && !seg.isFree()) {
                int idx = periodResolver.getPeriodIndex(seg.getBeginTime());
                periodRawTotal.merge(idx, charged, BigDecimal::add);
                periodCapMap.putIfAbsent(idx, periodResolver.getPeriodCap(seg.getBeginTime()));
                periodLabelMap.putIfAbsent(idx, periodResolver.getPeriodLabel(seg.getBeginTime()));
            }
        }

        // 第二遍：应用时段封顶（period.maxCharge × 周期数），按时间顺序累计削减
        Map<Integer, BigDecimal> periodApplied = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = rawCharges[i];

            if (periodResolver != null && !seg.isFree()) {
                int idx = periodResolver.getPeriodIndex(seg.getBeginTime());
                BigDecimal cap = periodCapMap.get(idx);
                if (cap != null) {
                    BigDecimal capMultiplied = cap.multiply(BigDecimal.valueOf(cycleCount));
                    BigDecimal before = periodApplied.getOrDefault(idx, BigDecimal.ZERO);
                    BigDecimal after = before.add(charged);
                    if (after.compareTo(capMultiplied) > 0) {
                        charged = capMultiplied.subtract(before);
                        if (charged.signum() < 0) charged = BigDecimal.ZERO;
                        periodApplied.put(idx, capMultiplied);
                    } else {
                        periodApplied.put(idx, after);
                    }
                }
            }
            rawCharges[i] = charged;
        }

        // 第三遍：构建 DurationSegment，求总额
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = rawCharges[i];
            int segMinutes = seg.durationMinutes();
            totalAmount = totalAmount.add(charged);

            String label = null;
            BigDecimal pCap = null;
            if (periodResolver != null) {
                int idx = periodResolver.getPeriodIndex(seg.getBeginTime());
                label = periodLabelMap.get(idx);
                pCap = periodCapMap.get(idx);
            }

            result.add(new DurationSegment(
                    seg.getBeginTime(),
                    seg.getEndTime(),
                    label,
                    seg.isFree() ? 0 : segMinutes,
                    seg.getUnitPrice(),
                    charged,
                    pCap,
                    seg.isFree() ? seg.getFreePromotionId() : null,
                    segmentOriginalCharge(seg, unitMinutes, config)
            ));
        }

        // 周期封顶（不落盘到段）
        BigDecimal cycleCapApplied = cycleCap != null
                ? cycleCap.multiply(BigDecimal.valueOf(cycleCount)) : null;
        BigDecimal finalAmount = (cycleCapApplied != null && totalAmount.compareTo(cycleCapApplied) > 0)
                ? cycleCapApplied : totalAmount;

        return new DurationResult(result, cycleCapApplied, finalAmount);
    }
}
