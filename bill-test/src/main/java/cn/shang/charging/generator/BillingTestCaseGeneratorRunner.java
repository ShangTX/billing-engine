package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.util.JacksonUtils;

import java.util.List;
import java.util.Set;

/**
 * 测试结果生成器的手动运行入口。
 * <p>
 * 使用者可以直接修改 main 方法里的功能点、生成数量和 seed，然后运行本类输出 JSON。
 */
public class BillingTestCaseGeneratorRunner {

    /**
     * 生成一组日夜计费样本并输出 JSON。
     */
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
