package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 计费配置解析器
 */
public interface BillingConfigResolver {

    /**
     * 获取计费模式
     * @param schemeId 方案id
     * @return 计费模式
     */
    default BConstants.BillingMode resolveBillingMode(String schemeId) {
        return resolveBillingMode(schemeId, Collections.emptyMap());
    }

    /**
     * 获取计费模式（带上下文参数）
     * @param schemeId 方案id
     * @param context 上下文参数
     * @return 计费模式
     */
    BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context);

    /**
     * 获取计费规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的计费规则配置
     */
    default RuleConfig resolveChargingRule(String schemeId,
                                           LocalDateTime segmentStart,
                                           LocalDateTime segmentEnd) {
        return resolveChargingRule(schemeId, segmentStart, segmentEnd, Collections.emptyMap());
    }

    /**
     * 获取计费规则配置（带上下文参数）
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @param context 上下文参数
     * @return 当前分段的计费规则配置
     */
    RuleConfig resolveChargingRule(String schemeId,
                                   LocalDateTime segmentStart,
                                   LocalDateTime segmentEnd,
                                   Map<String, Object> context);

    /**
     * 获取优惠规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的优惠规则配置
     */
    default List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                            LocalDateTime segmentStart,
                                                            LocalDateTime segmentEnd) {
        return resolvePromotionRules(schemeId, segmentStart, segmentEnd, Collections.emptyMap());
    }

    /**
     * 获取优惠规则配置（带上下文参数）
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @param context 上下文参数
     * @return 当前分段的优惠规则配置
     */
    List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                    LocalDateTime segmentStart,
                                                    LocalDateTime segmentEnd,
                                                    Map<String, Object> context);

    /**
     * 获取简化计算的周期阈值
     * @return 连续无优惠周期数超过此值时启用简化，0 表示禁用
     */
    default int getSimplifiedCycleThreshold() {
        return 0; // 默认禁用
    }
}