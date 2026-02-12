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

    RuleConfig resolveChargingRule(String schemeId,
                                   LocalDateTime segmentStart,
                                   LocalDateTime segmentEnd);

    List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                    LocalDateTime segmentStart,
                                                    LocalDateTime segmentEnd);
}
