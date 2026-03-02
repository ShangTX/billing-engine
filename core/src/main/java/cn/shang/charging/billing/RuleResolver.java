package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.PromotionRuleSnapshot;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.RuleSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则resolve
 */
public interface RuleResolver {

    /**
     * 获取计费规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的计费规则配置
     */
    RuleConfig resolveChargingRule(String schemeId,
                                   LocalDateTime segmentStart,
                                   LocalDateTime segmentEnd);


    /**
     * 获取优惠规则配置
     * @param schemeId 方案id
     * @param segmentStart 计费分段开始时间
     * @param segmentEnd 计费分段结束时间
     * @return 当前分段的优惠规则配置
     */
    List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                    LocalDateTime segmentStart,
                                                    LocalDateTime segmentEnd);
}
