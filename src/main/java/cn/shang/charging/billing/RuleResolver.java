package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.PromotionRuleSnapshot;
import cn.shang.charging.billing.pojo.RuleSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则resolve
 */
public interface RuleResolver {

    RuleSnapshot resolveChargingRule(String schemeId,
                         LocalDateTime segmentStart,
                         LocalDateTime segmentEnd);

    List<PromotionRuleSnapshot> resolvePromotionRules(String schemeId,
                                                      LocalDateTime segmentStart,
                                                      LocalDateTime segmentEnd);
}
