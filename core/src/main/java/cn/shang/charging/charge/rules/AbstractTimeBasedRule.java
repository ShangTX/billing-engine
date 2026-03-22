package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
     * 子类实现：是否有复杂特性（时间段封顶等）
     */
    protected abstract boolean hasComplexFeatures(C config);

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