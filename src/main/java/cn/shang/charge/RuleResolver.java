package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;

import java.time.LocalDateTime;

/**
 * 规则Resolver
 */
public interface RuleResolver {

    /**
     * 根据分段信息获取计费规则
     * @param schemeId
     * @param segmentBegin
     * @param segmentEnd
     * @return
     */
    BillingContext.RuleSnapshot resolve(String schemeId,
                                        LocalDateTime segmentBegin,
                                        LocalDateTime segmentEnd);

}
