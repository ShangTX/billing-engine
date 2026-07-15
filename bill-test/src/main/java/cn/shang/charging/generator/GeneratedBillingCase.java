package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 单个生成出的计费样本。
 * <p>
 * 该对象保留输入、配置、计费结果和查询摘要，方便使用者直接查看 JSON 并人工判断结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedBillingCase {

    /**
     * 样本 ID。
     */
    private String caseId;

    /**
     * 计费规则类型。
     */
    private String chargeRuleType;

    /**
     * 本样本覆盖的功能点集合。
     */
    private Set<TestFeature> features;

    /**
     * 最终用于计算的计费请求。
     */
    private BillingRequest request;

    /**
     * 计费规则配置快照。
     */
    private RuleConfig ruleConfig;

    /**
     * 规则型优惠配置快照。
     */
    private List<PromotionRuleConfig> promotionConfigs;

    /**
     * 外部优惠输入快照。
     */
    private List<PromotionGrant> externalPromotions;

    /**
     * 完整计费结果。
     */
    private BillingResult result;

    /**
     * 根据 QUERY_TIME 功能点生成的查询摘要。
     */

    /**
     * CONTINUE 功能点下的分步计算结果。
     */
}
