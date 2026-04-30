package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.util.JacksonUtils;

import java.util.List;
import java.util.Set;

public class BillingTestCaseGeneratorRunner {

    public static void main(String[] args) {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .chargeRuleType(BConstants.ChargeRuleType.DAY_NIGHT)
                .features(Set.of(
                        TestFeature.CONTINUE,
                        TestFeature.MULTI_PROMOTION,
                        TestFeature.BUBBLE_FREE_RANGE,
                        TestFeature.DAY_NIGHT_CROSS_PERIOD_UNIT,
                        TestFeature.QUERY_TIME
                ))
                .count(5)
                .seed(20260430L)
                .build();

        List<GeneratedBillingCase> cases = new BillingTestCaseGenerator().generate(request);
        System.out.println(JacksonUtils.toJsonString(cases));
    }
}
