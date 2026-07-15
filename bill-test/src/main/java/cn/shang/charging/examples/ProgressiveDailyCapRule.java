package cn.shang.charging.examples;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 非线性自然日累计封顶示例规则。
 *
 * <p>这个示例来自实际业务中的一种高度定制计费方式：每小时 5 元，按自然日切周期，
 * 但“第 N 天累计总封顶”不是线性乘法，而是：</p>
 *
 * <pre>
 * 第 1 天累计总封顶：35 元
 * 第 2 天累计总封顶：45 元
 * 第 3 天累计总封顶：60 元
 * 第 4 天累计总封顶：80 元
 * 第 5 天及以后：每天继续增加 20 元
 * </pre>
 *
 * <p>换成周期视角，就是每个自然日的“增量封顶”不同：</p>
 *
 * <pre>
 * 第 1 个自然日：35 元
 * 第 2 个自然日：10 元
 * 第 3 个自然日：15 元
 * 第 4 个自然日起：20 元/天
 * </pre>
 *
 * <p>为了便于从既有实现平滑迁移，配置中使用数组字段保存这些增量封顶：
 * {@code [35, 10, 15, 20]}。数组下标从 0 开始，但业务语义从“第 1 个自然日”开始；
 * 当计费天数超过数组长度时，复用数组最后一项作为后续自然日的增量封顶。</p>
 *
 * <p>这类规则无法用普通“每周期固定 cap × 周期数”的模型表达，适合直接实现
 * {@link BillingRule}。本示例仍复用 {@link BoundaryDrivenLoop}，但故意不加入单元边界：
 * 计费按分钟比例计算，只按自然日边界、免费段边界和 calcEnd 切分。</p>
 */
public class ProgressiveDailyCapRule implements BillingRule<ProgressiveDailyCapRule.Config> {

    /** 自定义规则类型。 */
    public static final String RULE_TYPE = "progressiveDailyCap";

    private static final String CAP_FREE_REASON = "PROGRESSIVE_DAILY_CAP";

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          Config config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);
        assertOnlyFreeRangePromotions(promotionAggregate);

        BConstants.CalculationMode mode = context.getCalculationMode();
        if (mode == null) {
            mode = BConstants.CalculationMode.CONTINUOUS;
        }

        return switch (mode) {
            case CONTINUOUS -> calculateContinuous(context, config, promotionAggregate);
            case DURATION_PERIOD -> calculateDuration(context, config, promotionAggregate,
                    BConstants.CalculationMode.DURATION_PERIOD);
            case DURATION_GLOBAL -> calculateDuration(context, config, promotionAggregate,
                    BConstants.CalculationMode.DURATION_GLOBAL);
            case UNIT_BASED -> throw new IllegalStateException("ProgressiveDailyCapRule does not support UNIT_BASED");
        };
    }

    @Override
    public Class<Config> configClass() {
        return Config.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(
                BConstants.CalculationMode.CONTINUOUS,
                BConstants.CalculationMode.DURATION_PERIOD,
                BConstants.CalculationMode.DURATION_GLOBAL);
    }

    /**
     * CONTINUOUS 模式：产出 BillingUnit。
     *
     * <p>这里的“unit”不是固定一小时单元，而是边界循环切出来的同质片段。
     * 因为用户明确希望去掉单元边界，所以一段可以是几分钟、几小时，金额统一按分钟比例算。</p>
     */
    private BillingSegmentResult calculateContinuous(BillingContext context,
                                                     Config config,
                                                     PromotionAggregate promotionAggregate) {
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        LocalDate originDateTime = context.getBeginTime().toLocalDate();
        List<FreeTimeRange> freeRanges = freeRangesOf(promotionAggregate);

        List<HomogeneousSegment> segments = buildSegments(calcBegin, calcEnd, originDateTime, config, freeRanges);
        List<RatedSlice> cappedSlices = applyProgressiveDailyCap(segments, originDateTime, config);
        List<BillingUnit> units = toBillingUnits(cappedSlices);
        BigDecimal totalAmount = sumUnits(units);
        List<PromotionUsage> usages = PromotionAggregateUtil.buildFreeRangeUsages(freeRanges, calcBegin, calcEnd);

        return baseResult(context, promotionAggregate)
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(units)
                .durationSegments(List.of())
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .cycleCapApplied(cumulativeCap(maxDayIndex(cappedSlices), config))
                .promotionUsages(usages)
                .build();
    }

    /**
     * DURATION_PERIOD / DURATION_GLOBAL 模式：产出 DurationSegment。
     *
     * <p>这两个模式在本规则中的收费数学相同：都必须按自然日序号应用不同的增量封顶。
     * 区别体现在结果模式标记上。这里不复用通用 DurationGlobalStrategy 的“cap × 周期数”
     * 模型，因为本规则的 cap 不是线性倍乘。</p>
     */
    private BillingSegmentResult calculateDuration(BillingContext context,
                                                   Config config,
                                                   PromotionAggregate promotionAggregate,
                                                   BConstants.CalculationMode mode) {
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        LocalDate originDate = context.getBeginTime().toLocalDate();
        List<FreeTimeRange> freeRanges = freeRangesOf(promotionAggregate);

        List<HomogeneousSegment> segments = buildSegments(calcBegin, calcEnd, originDate, config, freeRanges);
        List<RatedSlice> cappedSlices = applyProgressiveDailyCap(segments, originDate, config);
        List<DurationSegment> durationSegments = toDurationSegments(cappedSlices);
        BigDecimal totalAmount = sumDurationSegments(durationSegments);
        List<PromotionUsage> usages = PromotionAggregateUtil.buildFreeRangeUsages(freeRanges, calcBegin, calcEnd);

        return baseResult(context, promotionAggregate)
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(List.of())
                .durationSegments(durationSegments)
                .calculationMode(mode)
                .cycleCapApplied(cumulativeCap(maxDayIndex(cappedSlices), config))
                .promotionUsages(usages)
                .build();
    }

    /**
     * 用边界驱动循环构造同质片段。
     *
     * <p>本示例只放三类边界：</p>
     * <ul>
     *   <li>自然日边界：每天 00:00，用于切周期。</li>
     *   <li>免费段边界：FREE_RANGE 的开始和结束。</li>
     *   <li>计算终点：calcEnd。</li>
     * </ul>
     *
     * <p>注意：这里没有单元边界。每小时 5 元只是价格单位，不代表必须按小时切片。</p>
     */
    private List<HomogeneousSegment> buildSegments(LocalDateTime calcBegin,
                                                   LocalDateTime calcEnd,
                                                   LocalDate originDate,
                                                   Config config,
                                                   List<FreeTimeRange> freeRanges) {
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(naturalDayBoundaryProvider());
        providers.add(BoundaryProviders.freeRangeEdges(freeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        return BoundaryDrivenLoop.run(calcBegin, calcEnd, providers,
                (begin, end) -> buildSegment(begin, end, originDate, config, freeRanges));
    }

    private HomogeneousSegment buildSegment(LocalDateTime begin,
                                            LocalDateTime end,
                                            LocalDate originDate,
                                            Config config,
                                            List<FreeTimeRange> freeRanges) {
        int dayIndex = dayIndex(originDate, begin);
        BigDecimal rawAmount = proportionalAmount(config.getUnitPricePerHour(), begin, end);

        for (FreeTimeRange range : freeRanges) {
            if (covers(range, begin, end)) {
                return new HomogeneousSegment(begin, end, BigDecimal.ZERO, rawAmount,
                        true, range.getId(), range.getRangeType(), dayIndex);
            }
        }

        return new HomogeneousSegment(begin, end, config.getUnitPricePerHour(), rawAmount,
                false, null, dayIndex);
    }

    /**
     * 应用“第 N 个自然日”的增量封顶。
     *
     * <p>同一个自然日可能因为免费段被切成多个片段，因此需要按 dayIndex 维护已收费金额。
     * 对每个收费片段，只能消耗该自然日剩余的封顶预算。</p>
     */
    private List<RatedSlice> applyProgressiveDailyCap(List<HomogeneousSegment> segments,
                                                      LocalDate originDate,
                                                      Config config) {
        List<RatedSlice> result = new ArrayList<>();
        Map<Integer, BigDecimal> chargedByDay = new HashMap<>();

        for (HomogeneousSegment segment : segments) {
            int dayIndex = segment.getRuleData() instanceof Integer value
                    ? value
                    : dayIndex(originDate, segment.getBeginTime());
            BigDecimal rawAmount = scale(segment.getOriginalAmount());
            BigDecimal dayCap = incrementalCap(dayIndex, config);

            if (segment.isFree()) {
                result.add(RatedSlice.freePromotion(segment, dayIndex, dayCap, rawAmount));
                continue;
            }

            BigDecimal chargedBefore = chargedByDay.getOrDefault(dayIndex, BigDecimal.ZERO);
            BigDecimal remainingBudget = dayCap.subtract(chargedBefore);
            if (remainingBudget.signum() <= 0) {
                result.add(RatedSlice.freeByCap(segment, dayIndex, dayCap, rawAmount));
                continue;
            }

            BigDecimal charged = rawAmount.min(remainingBudget).setScale(2, RoundingMode.HALF_UP);
            chargedByDay.put(dayIndex, chargedBefore.add(charged));
            result.add(RatedSlice.charged(segment, dayIndex, dayCap, rawAmount, charged));
        }

        return result;
    }

    private List<BillingUnit> toBillingUnits(List<RatedSlice> slices) {
        List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;

        for (RatedSlice slice : slices) {
            accumulated = accumulated.add(slice.chargedAmount);
            units.add(BillingUnit.builder()
                    .beginTime(slice.segment.getBeginTime())
                    .endTime(slice.segment.getEndTime())
                    .durationMinutes(slice.segment.durationMinutes())
                    .unitPrice(slice.segment.isFree() ? BigDecimal.ZERO.setScale(2) : scale(slice.segment.getUnitPrice()))
                    .originalAmount(slice.originalAmount)
                    .free(slice.free)
                    .freePromotionId(slice.freeReason)
                    .chargedAmount(slice.chargedAmount)
                    .accumulatedAmount(accumulated)
                    .ruleData(slice.ruleData())
                    .compact(false)
                    .count(1)
                    .build());
        }

        return units;
    }

    private List<DurationSegment> toDurationSegments(List<RatedSlice> slices) {
        List<DurationSegment> result = new ArrayList<>();
        for (RatedSlice slice : slices) {
            result.add(new DurationSegment(
                    slice.segment.getBeginTime(),
                    slice.segment.getEndTime(),
                    "natural-day-" + slice.dayIndex,
                    slice.chargedAmount.signum() == 0 ? 0 : slice.segment.durationMinutes(),
                    slice.segment.isFree() ? BigDecimal.ZERO.setScale(2) : scale(slice.segment.getUnitPrice()),
                    slice.chargedAmount,
                    slice.dayCap,
                    slice.free ? slice.freeReason : null,
                    slice.originalAmount));
        }
        return result;
    }

    /**
     * 自然日边界 provider：返回 current 之后最近的 00:00。
     */
    private BoundaryProvider naturalDayBoundaryProvider() {
        return (current, calcEnd) -> {
            LocalDateTime nextMidnight = current.toLocalDate().plusDays(1).atStartOfDay();
            return nextMidnight.isAfter(calcEnd) ? null : nextMidnight;
        };
    }

    private BillingSegmentResult.BillingSegmentResultBuilder baseResult(
            BillingContext context,
            PromotionAggregate promotionAggregate) {
        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .promotionAggregate(promotionAggregate);
    }

    /**
     * 第 N 个自然日的增量封顶。
     */
    private BigDecimal incrementalCap(int dayIndex, Config config) {
        BigDecimal[] dailyIncrementCaps = config.getDailyIncrementCaps();
        int safeDayIndex = Math.max(dayIndex, 1);
        int arrayIndex = Math.min(safeDayIndex, dailyIncrementCaps.length) - 1;
        return scale(dailyIncrementCaps[arrayIndex]);
    }

    /**
     * 前 N 个自然日的累计总封顶。
     */
    private BigDecimal cumulativeCap(int dayCount, Config config) {
        if (dayCount <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= dayCount; i++) {
            total = total.add(incrementalCap(i, config));
        }
        return scale(total);
    }

    private int dayIndex(LocalDate originDate, LocalDateTime time) {
        return (int) ChronoUnit.DAYS.between(originDate, time.toLocalDate()) + 1;
    }

    private BigDecimal proportionalAmount(BigDecimal unitPricePerHour,
                                          LocalDateTime begin,
                                          LocalDateTime end) {
        long minutes = Duration.between(begin, end).toMinutes();
        return unitPricePerHour.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private int maxDayIndex(List<RatedSlice> slices) {
        return slices.stream().mapToInt(slice -> slice.dayIndex).max().orElse(0);
    }

    private BigDecimal sumUnits(List<BillingUnit> units) {
        return units.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumDurationSegments(List<DurationSegment> segments) {
        return segments.stream()
                .map(DurationSegment::chargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean covers(FreeTimeRange range, LocalDateTime begin, LocalDateTime end) {
        return range != null
                && range.getBeginTime() != null
                && range.getEndTime() != null
                && !range.getBeginTime().isAfter(begin)
                && !range.getEndTime().isBefore(end);
    }

    private List<FreeTimeRange> freeRangesOf(PromotionAggregate promotionAggregate) {
        if (promotionAggregate == null || promotionAggregate.getFreeTimeRanges() == null) {
            return List.of();
        }
        return promotionAggregate.getFreeTimeRanges();
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 示例只演示 FREE_RANGE，遇到其他优惠时快速失败，避免调用方误以为已经支持。
     */
    private void assertOnlyFreeRangePromotions(PromotionAggregate promotionAggregate) {
        if (promotionAggregate == null) {
            return;
        }
        if (promotionAggregate.getFreeMinutesList() != null && !promotionAggregate.getFreeMinutesList().isEmpty()) {
            throw new IllegalStateException("ProgressiveDailyCapRule example only supports FREE_RANGE promotions");
        }
        if (promotionAggregate.getSmartFreeMinutesList() != null && !promotionAggregate.getSmartFreeMinutesList().isEmpty()) {
            throw new IllegalStateException("ProgressiveDailyCapRule example only supports FREE_RANGE promotions");
        }
    }

    private void validateConfig(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.getUnitPricePerHour() == null || config.getUnitPricePerHour().signum() < 0) {
            throw new IllegalArgumentException("unitPricePerHour must be non-negative");
        }
        validateDailyIncrementCaps(config.getDailyIncrementCaps());
    }

    private void validateDailyIncrementCaps(BigDecimal[] dailyIncrementCaps) {
        if (dailyIncrementCaps == null || dailyIncrementCaps.length == 0) {
            throw new IllegalArgumentException("dailyIncrementCaps must not be empty");
        }
        for (int i = 0; i < dailyIncrementCaps.length; i++) {
            validatePositiveCap("dailyIncrementCaps[" + i + "]", dailyIncrementCaps[i]);
        }
    }

    private void validatePositiveCap(String fieldName, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * 已应用封顶后的片段。
     */
    private record RatedSlice(
            HomogeneousSegment segment,
            int dayIndex,
            BigDecimal dayCap,
            BigDecimal originalAmount,
            BigDecimal chargedAmount,
            boolean free,
            String freeReason) {

        static RatedSlice freePromotion(HomogeneousSegment segment,
                                        int dayIndex,
                                        BigDecimal dayCap,
                                        BigDecimal originalAmount) {
            return new RatedSlice(segment, dayIndex, dayCap, originalAmount,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), true, segment.getFreePromotionId());
        }

        static RatedSlice freeByCap(HomogeneousSegment segment,
                                    int dayIndex,
                                    BigDecimal dayCap,
                                    BigDecimal originalAmount) {
            return new RatedSlice(segment, dayIndex, dayCap, originalAmount,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), true, CAP_FREE_REASON);
        }

        static RatedSlice charged(HomogeneousSegment segment,
                                  int dayIndex,
                                  BigDecimal dayCap,
                                  BigDecimal originalAmount,
                                  BigDecimal chargedAmount) {
            boolean capReduced = chargedAmount.compareTo(originalAmount) < 0;
            return new RatedSlice(segment, dayIndex, dayCap, originalAmount,
                    chargedAmount, false, capReduced ? CAP_FREE_REASON : null);
        }

        Map<String, Object> ruleData() {
            Map<String, Object> data = new HashMap<>();
            data.put(RuleDataKey.DAY_INDEX.name(), dayIndex);
            data.put(RuleDataKey.DAY_CAP.name(), dayCap);
            data.put(RuleDataKey.ORIGINAL_AMOUNT.name(), originalAmount);
            if (freeReason != null) {
                data.put(RuleDataKey.FREE_REASON.name(), freeReason);
            }
            return data;
        }
    }

    private enum RuleDataKey {
        DAY_INDEX,
        DAY_CAP,
        ORIGINAL_AMOUNT,
        FREE_REASON
    }

    /**
     * 非线性自然日累计封顶规则配置。
     *
     * <p>默认值就是用户给出的业务规则：
     * 每小时 5 元；第 1 天增量封顶 35，第 2 天 10，第 3 天 15，第 4 天起每天 20。</p>
     *
     * <p>这里沿用“数组保存增量”的配置形态，便于从旧实现迁移：
     * {@code dailyIncrementCaps[0]} 表示第 1 个自然日的增量封顶，
     * {@code dailyIncrementCaps[1]} 表示第 2 个自然日的增量封顶，以此类推。
     * 当自然日序号超过数组长度时，复用数组最后一项。</p>
     */
    @Data
    @Accessors(chain = true)
    public static class Config implements RuleConfig {
        private String id;

        /** 每小时单价。 */
        private BigDecimal unitPricePerHour = new BigDecimal("5.00");

        /**
         * 每个自然日的增量封顶数组。
         *
         * <p>默认 {@code [35, 10, 15, 20]} 表示累计总封顶依次为：
         * 第 1 天 35，第 2 天 45，第 3 天 60，第 4 天 80；
         * 第 5 天及以后继续按最后一项 20 元/天累加。</p>
         */
        private BigDecimal[] dailyIncrementCaps = new BigDecimal[] {
                new BigDecimal("35.00"),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                new BigDecimal("20.00")
        };

        @Override
        public String getType() {
            return RULE_TYPE;
        }
    }
}
