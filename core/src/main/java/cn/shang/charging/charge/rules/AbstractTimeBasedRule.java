package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.ProportionalValueSpec;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.util.TypeConversionUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间计费规则抽象基类
 * <p>
 * 提取公共逻辑：
 * 1. RuleState 状态结构
 * 2. restoreState/toMap 状态序列化
 * 3. buildCarryOverState 结转输出
 * 4. CalculationContext 跳过判断
 */
public abstract class AbstractTimeBasedRule<C extends RuleConfig> implements BillingRule<C> {

    protected static final int MINUTES_PER_CYCLE = 1440; // 24小时

    // ==================== 子类必须实现 ====================

    /**
     * 规则类型标识（用于 ruleState Map 的 key）
     */
    protected abstract String getRuleType();

    /**
     * 周期长度（分钟），子类可覆盖
     */
    protected int getCycleMinutes() {
        return MINUTES_PER_CYCLE;
    }

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

    /**
     * 子类实现：是否有复杂特性（时间段封顶等）
     */
    protected abstract boolean hasComplexFeatures(C config);

    // ==================== 简化计算框架 ====================

    /**
     * 子类实现：是否支持简化计算
     */
    protected abstract boolean isSimplifiedSupported(C config);

    /**
     * 子类实现：获取周期封顶金额
     * 用于简化计算时确定单周期金额
     */
    protected abstract BigDecimal getCycleCapAmount(C config);

    /**
     * 检查简化计算是否启用
     */
    protected boolean isSimplificationEnabled(C config, BillingConfigResolver configResolver, BillingContext context) {
        if (context != null && Boolean.TRUE.equals(context.getDisableSimplification())) {
            return false;
        }
        // 配置明确禁用
        if (config.getSimplifiedSupported() != null && !config.getSimplifiedSupported()) {
            return false;
        }
        // 阈值为 0 表示禁用
        int threshold = configResolver.getSimplifiedCycleThreshold();
        if (threshold <= 0) {
            return false;
        }
        // 封顶金额必须有效
        BigDecimal capAmount = getCycleCapAmount(config);
        return capAmount != null && capAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 计算周期边界时间
     * @param cycleIndex 周期索引（0-based）
     * @param calcBegin 计算起点
     * @return 该周期的起始时间
     */
    protected LocalDateTime getCycleBoundary(int cycleIndex, LocalDateTime calcBegin) {
        return calcBegin.plusMinutes((long) cycleIndex * getCycleMinutes());
    }

    /**
     * 计算优惠时段覆盖的周期索引集合
     */
    protected Set<Integer> findCyclesWithPromotion(
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            PromotionAggregate promotionAggregate) {

        Set<Integer> cycles = new HashSet<>();

        // 如果有免费分钟数，保守地将所有周期视为有优惠
        if (promotionAggregate != null && promotionAggregate.getFreeMinutes() > 0) {
            // 返回 null 表示所有周期都有优惠（无法用集合表示无限）
            // 调用方需特殊处理
            return null;
        }

        if (promotionAggregate == null || promotionAggregate.getFreeTimeRanges() == null) {
            return cycles;
        }

        List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
        int cycleMinutes = getCycleMinutes();

        for (FreeTimeRange range : freeTimeRanges) {
            // 忽略窗口外的时段
            if (range.getEndTime().isBefore(calcBegin) || range.getBeginTime().isAfter(calcEnd)) {
                continue;
            }

            // 计算优惠时段覆盖的周期范围
            LocalDateTime effectiveBegin = range.getBeginTime().isBefore(calcBegin) ? calcBegin : range.getBeginTime();
            LocalDateTime effectiveEnd = range.getEndTime().isAfter(calcEnd) ? calcEnd : range.getEndTime();

            int startCycle = (int) Duration.between(calcBegin, effectiveBegin).toMinutes() / cycleMinutes;
            int endCycle = (int) Duration.between(calcBegin, effectiveEnd).toMinutes() / cycleMinutes;

            // 如果结束时间正好在周期边界，不包含下一个周期
            long endMinutes = Duration.between(calcBegin, effectiveEnd).toMinutes();
            if (endMinutes % cycleMinutes == 0) {
                endCycle--;
            }

            // 添加所有覆盖的周期索引
            for (int i = startCycle; i <= endCycle; i++) {
                if (i >= 0) {
                    cycles.add(i);
                }
            }
        }

        return cycles;
    }

    /**
     * 构建简化单元
     */
    protected BillingUnit buildSimplifiedUnit(
            int beginCycleIndex,
            int cycleCount,
            BigDecimal cycleCapAmount,
            LocalDateTime calcBegin) {

        LocalDateTime beginTime = getCycleBoundary(beginCycleIndex, calcBegin);
        LocalDateTime endTime = getCycleBoundary(beginCycleIndex + cycleCount, calcBegin);
        BigDecimal totalAmount = cycleCapAmount.multiply(BigDecimal.valueOf(cycleCount));

        // 构建 ruleData
        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("cycleIndex", beginCycleIndex);
        ruleData.put("simplifiedCycleCount", cycleCount);
        ruleData.put("simplifiedCycleAmount", cycleCapAmount);
        ruleData.put("isSimplified", true);

        return BillingUnit.builder()
                .beginTime(beginTime)
                .endTime(endTime)
                .durationMinutes((int) Duration.between(beginTime, endTime).toMinutes())
                .unitPrice(cycleCapAmount)
                .originalAmount(totalAmount)
                .chargedAmount(totalAmount)
                .ruleData(ruleData)
                .build();
    }

    /**
     * 检查 BillingUnit 是否为简化单元
     */
    @SuppressWarnings("unchecked")
    protected boolean isSimplifiedUnit(BillingUnit unit) {
        SimplifiedUnitMeta meta = extractSimplifiedUnitMeta(unit);
        return meta != null && meta.simplified();
    }

    /**
     * 从简化单元恢复 RuleState
     */
    @SuppressWarnings("unchecked")
    protected RuleState restoreStateFromSimplifiedUnit(RuleState state, BillingUnit simplifiedUnit, LocalDateTime calcBegin) {
        if (state == null || simplifiedUnit == null || !isSimplifiedUnit(simplifiedUnit)) {
            return state;
        }

        SimplifiedUnitMeta meta = extractSimplifiedUnitMeta(simplifiedUnit);
        if (meta == null) {
            return state;
        }

        state.setCycleIndex(state.getCycleIndex() + meta.simplifiedCycleCount());
        state.setCycleAccumulated(meta.simplifiedCycleAmount());
        state.setCycleBoundary(getCycleBoundary(state.getCycleIndex() + 1, calcBegin));

        return state;
    }

    protected SimplifiedUnitMeta extractSimplifiedUnitMeta(BillingUnit unit) {
        return SimplifiedUnitMeta.from(unit);
    }

    /**
     * 从 Map 恢复 RuleState（支持简化单元）
     * @param stateMap 状态 Map
     * @param previousResult 上一次计算结果（用于检测简化单元）
     * @param calcBegin 当前计算起点
     */
    @SuppressWarnings("unchecked")
    protected RuleState restoreStateWithSimplification(
            Map<String, Object> stateMap,
            BillingSegmentResult previousResult,
            LocalDateTime calcBegin) {

        RuleState state = restoreState(stateMap);
        if (state == null) {
            return null;
        }

        // 检查上一个结果的最后一个单元是否为简化单元
        if (previousResult != null && previousResult.getBillingUnits() != null
                && !previousResult.getBillingUnits().isEmpty()) {
            BillingUnit lastUnit = previousResult.getBillingUnits().get(
                previousResult.getBillingUnits().size() - 1);
            if (isSimplifiedUnit(lastUnit)) {
                restoreStateFromSimplifiedUnit(state, lastUnit, calcBegin);
            }
        }

        return state;
    }

    // ==================== 共同 RuleState 结构 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleState {
        /** 当前周期索引 */
        private int cycleIndex;
        /** 当前周期累计金额 */
        private BigDecimal cycleAccumulated;
        /** 周期边界时间 */
        private LocalDateTime cycleBoundary;
    }

    // ==================== 状态恢复/序列化（共同实现） ====================

    /**
     * 从 Map 恢复 RuleState
     * 支持序列化后的类型转换（LocalDateTime→String, BigDecimal→String/Double）
     */
    @SuppressWarnings("unchecked")
    protected RuleState restoreState(Map<String, Object> stateMap) {
        if (stateMap == null) return null;
        Object state = stateMap.get(getRuleType());
        if (state == null) return null;

        if (state instanceof RuleState) {
            return (RuleState) state;
        }

        if (state instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) state;
            return RuleState.builder()
                    .cycleIndex(TypeConversionUtil.toInteger(map.getOrDefault("cycleIndex", 0)))
                    .cycleAccumulated(TypeConversionUtil.toBigDecimal(map.getOrDefault("cycleAccumulated", "0")))
                    .cycleBoundary(TypeConversionUtil.toLocalDateTime(map.get("cycleBoundary")))
                    .build();
        }
        return null;
    }

    /**
     * 序列化 RuleState 为 Map
     */
    protected Map<String, Object> toMap(RuleState state) {
        Map<String, Object> map = new HashMap<>();
        map.put("cycleIndex", state.getCycleIndex());
        map.put("cycleAccumulated", state.getCycleAccumulated());
        map.put("cycleBoundary", state.getCycleBoundary());
        return map;
    }

    /**
     * 初始化状态（FROM_SCRATCH 模式）
     */
    protected RuleState initializeState(LocalDateTime calcBegin) {
        return RuleState.builder()
                .cycleIndex(0)
                .cycleAccumulated(BigDecimal.ZERO)
                .cycleBoundary(calcBegin.plusMinutes(getCycleMinutes()))
                .build();
    }

    // ==================== buildCarryOverState 共同实现 ====================

    @Override
    public Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
        if (result.getRuleOutputState() == null) {
            return Collections.emptyMap();
        }
        return result.getRuleOutputState();
    }

    // ==================== CalculationContext 构建 ====================

    /**
     * 构建计算上下文（集中跳过判断）
     */
    protected CalculationContext buildCalculationContext(
            BillingContext context,
            PromotionAggregate promotionAggregate,
            C config) {

        boolean hasContinueMode = context.getContinueMode() == BConstants.ContinueMode.CONTINUE;

        boolean hasPromotion = promotionAggregate != null && !promotionAggregate.isEmpty();

        boolean hasMultiplePromotionTypes = hasPromotion && promotionAggregate.hasMultiplePromotionTypes();

        boolean hasComplexFeatures = hasComplexFeatures(config);

        return CalculationContext.builder()
                .hasContinueMode(hasContinueMode)
                .hasPromotion(hasPromotion)
                .hasMultiplePromotionTypes(hasMultiplePromotionTypes)
                .hasComplexFeatures(hasComplexFeatures)
                .build();
    }

    /**
     * 更新状态到输出
     */
    protected Map<String, Object> buildRuleOutputState(RuleState state) {
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(getRuleType(), toMap(state));
        return ruleOutputState;
    }

    // ==================== CONTINUOUS 模式公共时间轴切分基础设施 ====================

    /**
     * 时间片段（按免费时段边界切分后的时间范围）。
     * <p>
     * 子类如需携带额外字段（如 DayNight 的 conditional 信息），可继承本类并覆盖
     * {@link #applyFreeRange(FreeTimeRange)}、{@link #copy(LocalDateTime, LocalDateTime)}
     * 以及 {@link #isConditional()} / {@link #getConditionalUntil()} 钩子。
     */
    protected static class TimeFragment {
        public LocalDateTime beginTime;
        public LocalDateTime endTime;
        public boolean isFree;
        public String freePromotionId;

        public TimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
            this.beginTime = beginTime;
            this.endTime = endTime;
            this.isFree = false;
            this.freePromotionId = null;
        }

        /**
         * 应用免费时段信息到本片段。子类可覆盖以复制额外字段。
         */
        public void applyFreeRange(FreeTimeRange range) {
            this.isFree = true;
            this.freePromotionId = range.getId();
        }

        /**
         * 创建覆盖 [beginTime, endTime] 的副本，继承本片段的免费信息。
         * 用于 {@link #organizeByCycle} 在周期边界切分片段。
         * 子类可覆盖以复制额外字段。
         */
        public TimeFragment copy(LocalDateTime beginTime, LocalDateTime endTime) {
            TimeFragment f = new TimeFragment(beginTime, endTime);
            f.isFree = this.isFree;
            f.freePromotionId = this.freePromotionId;
            return f;
        }

        /** 是否为条件免费片段。默认 false，DayNight 等规则覆盖。 */
        public boolean isConditional() {
            return false;
        }

        /** 条件免费用效截止时间。默认 null。 */
        public LocalDateTime getConditionalUntil() {
            return null;
        }
    }

    /**
     * 周期片段容器：一个 24h 周期内的时间片段列表。
     */
    protected static class CycleFragments {
        public final LocalDateTime cycleStart;
        public final LocalDateTime cycleEnd;
        public final List<TimeFragment> fragments = new ArrayList<>();

        public CycleFragments(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
            this.cycleStart = cycleStart;
            this.cycleEnd = cycleEnd;
        }
    }

    /**
     * 创建时间片段。子类可覆盖以返回携带额外字段的子类型。
     */
    protected TimeFragment createFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        return new TimeFragment(beginTime, endTime);
    }

    /**
     * 按免费时段边界切分时间轴（CONTINUOUS 模式公共实现）。
     * <p>
     * 收集所有落在 [begin, end] 内的免费时段起止点作为切分点，去重排序后逐段生成片段，
     * 并标记每段是否被某个免费时段完全覆盖。
     */
    protected List<TimeFragment> splitTimeAxis(LocalDateTime begin,
                                               LocalDateTime end,
                                               List<FreeTimeRange> freeTimeRanges) {
        List<TimeFragment> fragments = new ArrayList<>();

        List<LocalDateTime> cutPoints = new ArrayList<>();
        cutPoints.add(begin);

        if (freeTimeRanges != null) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getBeginTime().isAfter(end) || range.getEndTime().isBefore(begin)) {
                    continue;
                }
                if (range.getBeginTime().isAfter(begin) && range.getBeginTime().isBefore(end)) {
                    cutPoints.add(range.getBeginTime());
                }
                if (range.getEndTime().isAfter(begin) && range.getEndTime().isBefore(end)) {
                    cutPoints.add(range.getEndTime());
                }
            }
        }

        cutPoints.add(end);
        cutPoints = cutPoints.stream().distinct().sorted().toList();

        for (int i = 0; i < cutPoints.size() - 1; i++) {
            LocalDateTime fragBegin = cutPoints.get(i);
            LocalDateTime fragEnd = cutPoints.get(i + 1);

            TimeFragment fragment = createFragment(fragBegin, fragEnd);

            if (freeTimeRanges != null) {
                for (FreeTimeRange range : freeTimeRanges) {
                    if (!range.getBeginTime().isAfter(fragBegin) && !range.getEndTime().isBefore(fragEnd)) {
                        fragment.applyFreeRange(range);
                        break;
                    }
                }
            }

            fragments.add(fragment);
        }

        return fragments;
    }

    /**
     * 按周期（24h）组织片段（CONTINUOUS 模式公共实现）。
     * <p>
     * 以 cycleOriginBegin 为周期起点，将跨越周期边界的片段切分到对应周期。
     */
    protected List<CycleFragments> organizeByCycle(LocalDateTime calcBegin,
                                                   LocalDateTime calcEnd,
                                                   List<TimeFragment> fragments,
                                                   LocalDateTime cycleOriginBegin) {
        List<CycleFragments> cycles = new ArrayList<>();

        int cycleMinutes = getCycleMinutes();
        LocalDateTime cycleStart = cycleOriginBegin;
        LocalDateTime cycleEnd = cycleOriginBegin.plusMinutes(cycleMinutes);

        // 找到包含 calcBegin 的周期
        while (cycleEnd.isBefore(calcBegin) || cycleEnd.equals(calcBegin)) {
            cycleStart = cycleEnd;
            cycleEnd = cycleStart.plusMinutes(cycleMinutes);
        }

        CycleFragments currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

        for (TimeFragment fragment : fragments) {
            while (fragment.endTime.isAfter(currentCycle.cycleEnd)) {
                TimeFragment beforeBoundary = fragment.copy(fragment.beginTime, currentCycle.cycleEnd);

                currentCycle.fragments.add(beforeBoundary);
                cycles.add(currentCycle);

                cycleStart = currentCycle.cycleEnd;
                cycleEnd = cycleStart.plusMinutes(cycleMinutes);
                currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

                fragment.beginTime = currentCycle.cycleStart;
            }

            currentCycle.fragments.add(fragment);
        }

        if (!currentCycle.fragments.isEmpty()) {
            cycles.add(currentCycle);
        }

        return cycles;
    }

    // ==================== 边界驱动循环（委托工具） ====================

    /**
     * 边界驱动的公共循环入口（委托 {@link BoundaryDrivenLoop}）。
     * <p>
     * 保留为 protected 便于 CONTINUOUS 策略子类调用；逻辑已提取为独立工具，
     * 供 CONTINUOUS 策略和时长策略共享，不通过继承传递。
     */
    protected List<HomogeneousSegment> runBoundaryDrivenLoop(
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            List<BoundaryProvider> providers,
            BoundaryDrivenLoop.SegmentBuilder segmentBuilder) {
        return BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, segmentBuilder);
    }

    // ==================== 不足单元计费（公共工具） ====================

    /**
     * 按不足单元计费模式计算截断单元的实际收费金额。
     * <p>
     * 仅用于 isTruncated=true 的单元（segMinutes &lt; unitMinutes）。
     * <ul>
     *   <li>FULL_CHARGE：unitPrice（不足也收全额）</li>
     *   <li>PROPORTIONAL：unitPrice × segMinutes / unitMinutes</li>
     *   <li>FREE：0</li>
     *   <li>THRESHOLD_MINUTES：segMinutes ≥ thresholdMinutes ? unitPrice : 0</li>
     *   <li>THRESHOLD_RATIO：ratio = segMinutes/unitMinutes ≥ thresholdRatio ? unitPrice × ratio : 0</li>
     * </ul>
     *
     * @param unitPrice        完整单元单价
     * @param segMinutes       截断单元实际时长
     * @param unitMinutes      完整单元时长
     * @param mode             不足单元计费模式（null 视为 FULL_CHARGE）
     * @param thresholdMinutes THRESHOLD_MINUTES 阈值（null 视为 0）
     * @param thresholdRatio   THRESHOLD_RATIO 阈值（null 视为 0，即总是按比例）
     * @return 截断单元实际收费金额（scale=2, HALF_UP）
     */
    public static BigDecimal computeIncompleteCharge(BigDecimal unitPrice,
                                                         int segMinutes,
                                                         int unitMinutes,
                                                         BConstants.IncompleteUnitChargeMode mode,
                                                         Integer thresholdMinutes,
                                                         BigDecimal thresholdRatio) {
        if (unitPrice == null) unitPrice = BigDecimal.ZERO;
        if (mode == null) mode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;
        if (segMinutes >= unitMinutes || unitMinutes <= 0) {
            return unitPrice.setScale(2, RoundingMode.HALF_UP);
        }

        switch (mode) {
            case FULL_CHARGE:
                return unitPrice.setScale(2, RoundingMode.HALF_UP);
            case PROPORTIONAL:
                return unitPrice.multiply(BigDecimal.valueOf(segMinutes))
                        .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
            case FREE:
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            case THRESHOLD_MINUTES: {
                int threshold = thresholdMinutes != null ? thresholdMinutes : 0;
                return segMinutes >= threshold
                        ? unitPrice.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            case THRESHOLD_RATIO: {
                BigDecimal ratio = BigDecimal.valueOf(segMinutes)
                        .divide(BigDecimal.valueOf(unitMinutes), 6, RoundingMode.HALF_UP);
                BigDecimal threshold = thresholdRatio != null ? thresholdRatio : BigDecimal.ZERO;
                if (ratio.compareTo(threshold) >= 0) {
                    // 达到阈值：按比例收（非全额）
                    return unitPrice.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                }
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            default:
                return unitPrice.setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * 按不足单元计费模式构造截断单元的 valueSpec。
     * <p>
     * 与 {@link #computeIncompleteCharge} 配套，保证查询时点投影与最终金额语义一致：
     * <ul>
     *   <li>FULL_CHARGE / THRESHOLD_MINUTES 达阈值：FixedValueSpec(unitPrice)（单元内固定全额）</li>
     *   <li>PROPORTIONAL / THRESHOLD_RATIO 达阈值：ProportionalValueSpec（按分钟线性）</li>
     *   <li>FREE / THRESHOLD 未达阈值：FixedValueSpec(ZERO)</li>
     * </ul>
     */
    public static UnitValueSpec computeIncompleteValueSpec(BigDecimal unitPrice,
                                                               int segMinutes,
                                                               int unitMinutes,
                                                               BConstants.IncompleteUnitChargeMode mode,
                                                               Integer thresholdMinutes,
                                                               BigDecimal thresholdRatio) {
        if (unitPrice == null) unitPrice = BigDecimal.ZERO;
        if (mode == null) mode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;
        if (segMinutes >= unitMinutes || unitMinutes <= 0) {
            return new FixedValueSpec(unitPrice);
        }

        switch (mode) {
            case FULL_CHARGE:
                return new FixedValueSpec(unitPrice);
            case PROPORTIONAL:
                return new ProportionalValueSpec(unitPrice, unitMinutes);
            case FREE:
                return new FixedValueSpec(BigDecimal.ZERO);
            case THRESHOLD_MINUTES: {
                int threshold = thresholdMinutes != null ? thresholdMinutes : 0;
                return segMinutes >= threshold
                        ? new FixedValueSpec(unitPrice)
                        : new FixedValueSpec(BigDecimal.ZERO);
            }
            case THRESHOLD_RATIO: {
                BigDecimal ratio = BigDecimal.valueOf(segMinutes)
                        .divide(BigDecimal.valueOf(unitMinutes), 6, RoundingMode.HALF_UP);
                BigDecimal threshold = thresholdRatio != null ? thresholdRatio : BigDecimal.ZERO;
                return ratio.compareTo(threshold) >= 0
                        ? new ProportionalValueSpec(unitPrice, unitMinutes)
                        : new FixedValueSpec(BigDecimal.ZERO);
            }
            default:
                return new FixedValueSpec(unitPrice);
        }
    }

    /**
     * 判定不足单元在该模式下是否免费（用于设置 free/freePromotionId）。
     */
    public static boolean isIncompleteFree(int segMinutes,
                                              int unitMinutes,
                                              BConstants.IncompleteUnitChargeMode mode,
                                              Integer thresholdMinutes,
                                              BigDecimal thresholdRatio) {
        if (mode == null) return false;
        if (segMinutes >= unitMinutes || unitMinutes <= 0) return false;
        switch (mode) {
            case FREE:
                return true;
            case THRESHOLD_MINUTES:
                return segMinutes < (thresholdMinutes != null ? thresholdMinutes : 0);
            case THRESHOLD_RATIO: {
                BigDecimal ratio = BigDecimal.valueOf(segMinutes)
                        .divide(BigDecimal.valueOf(unitMinutes), 6, RoundingMode.HALF_UP);
                BigDecimal threshold = thresholdRatio != null ? thresholdRatio : BigDecimal.ZERO;
                return ratio.compareTo(threshold) < 0;
            }
            default:
                return false;
        }
    }

}
