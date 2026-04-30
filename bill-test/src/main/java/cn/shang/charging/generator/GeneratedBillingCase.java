package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.wrapper.QuerySummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedBillingCase {

    private String caseId;
    private String chargeRuleType;
    private Set<TestFeature> features;
    private BillingRequest request;
    private RuleConfig ruleConfig;
    private List<PromotionRuleConfig> promotionConfigs;
    private List<PromotionGrant> externalPromotions;
    private BillingResult result;
    private List<QuerySummary> querySummaries;
    private List<GeneratedContinueStep> continueSteps;
}
