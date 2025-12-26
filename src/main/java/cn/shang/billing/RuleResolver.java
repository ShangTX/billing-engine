package cn.shang.billing;

import cn.shang.billing.pojo.RuleSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则resolve
 */
public interface RuleResolver {

    RuleSnapshot resolveChargingRule(String schemeId,
                         LocalDateTime segmentStart,
                         LocalDateTime segmentEnd);

    List<RuleSnapshot> resolvePromotionRules(String schemeId,
                                            LocalDateTime segmentStart,
                                            LocalDateTime segmentEnd);
}
