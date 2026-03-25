package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
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
    protected boolean isSimplificationEnabled(C config, BillingConfigResolver configResolver) {
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
        if (unit.getRuleData() instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
            return Boolean.TRUE.equals(data.get("isSimplified"));
        }
        return false;
    }

    /**
     * 从简化单元恢复 RuleState
     */
    @SuppressWarnings("unchecked")
    protected RuleState restoreStateFromSimplifiedUnit(RuleState state, BillingUnit simplifiedUnit, LocalDateTime calcBegin) {
        if (state == null || simplifiedUnit == null || !isSimplifiedUnit(simplifiedUnit)) {
            return state;
        }

        Map<String, Object> data = (Map<String, Object>) simplifiedUnit.getRuleData();
        int simplifiedCount = (Integer) data.get("simplifiedCycleCount");
        BigDecimal cycleAmount = (BigDecimal) data.get("simplifiedCycleAmount");

        state.setCycleIndex(state.getCycleIndex() + simplifiedCount);
        state.setCycleAccumulated(cycleAmount);
        state.setCycleBoundary(getCycleBoundary(state.getCycleIndex() + 1, calcBegin));

        return state;
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
                    .cycleIndex((Integer) map.getOrDefault("cycleIndex", 0))
                    .cycleAccumulated(map.get("cycleAccumulated") instanceof BigDecimal
                            ? (BigDecimal) map.get("cycleAccumulated")
                            : new BigDecimal(map.getOrDefault("cycleAccumulated", "0").toString()))
                    .cycleBoundary((LocalDateTime) map.get("cycleBoundary"))
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
}