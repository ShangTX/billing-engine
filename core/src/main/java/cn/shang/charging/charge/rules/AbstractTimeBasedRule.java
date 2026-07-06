package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间计费规则抽象基类（CONTINUOUS 策略基类）。
 * <p>
 * 承载 CONTINUOUS 策略族共享的基础设施：
 * <ol>
 *   <li>边界驱动循环委托（{@link #runBoundaryDrivenLoop} → {@link BoundaryDrivenLoop}）</li>
 *   <li>FREE_MINUTES 时段化（{@link #materializeFreeMinutes}）</li>
 *   <li>不足单元计费（{@link #computeIncompleteCharge} / {@link #isIncompleteFree}）</li>
 *   <li>简化单元构建与识别（{@link #buildSimplifiedUnit} / {@link #isSimplifiedUnit} / {@link #extractSimplifiedUnitMeta}）</li>
 *   <li>{@link RuleState} 单次计算周期跟踪状态</li>
 * </ol>
 * 旧切段模型（TimeFragment / splitTimeAxis / organizeByCycle / CycleFragments / findCyclesWithPromotion）
 * 已随阶段 3b 全局空隙改造删除。
 */
public abstract class AbstractTimeBasedRule<C extends RuleConfig> implements BillingRule<C> {

    protected static final int MINUTES_PER_CYCLE = 1440; // 24小时

    // ==================== 子类必须实现 ====================

    /**
     * 周期长度（分钟），子类可覆盖
     */
    protected int getCycleMinutes() {
        return MINUTES_PER_CYCLE;
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

    protected SimplifiedUnitMeta extractSimplifiedUnitMeta(BillingUnit unit) {
        return SimplifiedUnitMeta.from(unit);
    }

    // ==================== 共同 RuleState 结构（单次计算周期跟踪） ====================

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

    /**
     * 初始化状态
     */
    protected RuleState initializeState(LocalDateTime calcBegin) {
        return RuleState.builder()
                .cycleIndex(0)
                .cycleAccumulated(BigDecimal.ZERO)
                .cycleBoundary(calcBegin.plusMinutes(getCycleMinutes()))
                .build();
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

    // ==================== FREE_MINUTES 时段化（策略侧，TODO-20260702-004） ====================

    /**
     * 策略侧 FREE_MINUTES 时段化工具实例（无状态，共享）。
     */
    public static final FreeMinuteAllocator FREE_MINUTE_ALLOCATOR = new FreeMinuteAllocator();

    /**
     * 把 PromotionAggregate 中的未时段化 FREE_MINUTES 时段化为时间段，与 FREE_RANGE 合并，
     * 返回最终免费段 + FREE_MINUTES usage。
     * <p>
     * CONTINUOUS 策略（DayNight/RelativeTime/NaturalTime/CompositeTime）在 {@code calculate} 入口
     * 调用本方法获得 finalFreeRanges，替换旧路径直接读 {@code aggregate.getFreeTimeRanges()} 的行为
     * （后者现在只含 FREE_RANGE）。FREE_MINUTES 时段化已从 PromotionEngine 下放到此（TODO-20260702-004）。
     * <p>
     * 无 FREE_MINUTES 时返回 {@code aggregate.freeTimeRanges}（FREE_RANGE）+ 空 usages，不产生副作用。
     *
     * @param promotionAggregate 优惠聚合（中间形式）
     * @param window              计算窗口
     * @return finalFreeRanges（FREE_RANGE + 时段化 FREE_MINUTES，已合并）+ FREE_MINUTES usages
     */
    public static FreeMinuteAllocationResult materializeFreeMinutes(
            PromotionAggregate promotionAggregate, CalculationWindow window) {
        List<FreeMinutes> freeMinutesList = promotionAggregate != null
                ? promotionAggregate.getFreeMinutesList() : null;
        List<FreeTimeRange> freeRangeOnly = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();
        if (freeMinutesList == null || freeMinutesList.isEmpty()) {
            return new FreeMinuteAllocationResult()
                    .setFinalFreeRanges(freeRangeOnly)
                    .setPromotionUsages(List.of());
        }
        return FREE_MINUTE_ALLOCATOR.allocateAndMerge(freeMinutesList, freeRangeOnly, window);
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
