package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.util.JacksonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingTestCaseGeneratorTest {

    @Test
    void generatesRequestedDayNightCasesWithJsonOutput() {
        TestGenerationRequest request = TestGenerationRequest.builder()
                .chargeRuleType(BConstants.ChargeRuleType.DAY_NIGHT)
                .features(Set.of(
                        TestFeature.CONTINUE,
                        TestFeature.MULTI_PROMOTION,
                        TestFeature.BUBBLE_FREE_RANGE,
                        TestFeature.DAY_NIGHT_CROSS_PERIOD_UNIT,
                        TestFeature.QUERY_TIME
                ))
                .count(3)
                .seed(20260430L)
                .build();

        List<GeneratedBillingCase> cases = new BillingTestCaseGenerator().generate(request);
        String json = JacksonUtils.toJsonString(cases);

        assertEquals(3, cases.size());
        assertFalse(json.isBlank());
        assertTrue(json.contains("DAY_NIGHT_CROSS_PERIOD_UNIT"));

        for (GeneratedBillingCase generatedCase : cases) {
            assertEquals(BConstants.ChargeRuleType.DAY_NIGHT, generatedCase.getChargeRuleType());
            assertTrue(generatedCase.getFeatures().contains(TestFeature.BUBBLE_FREE_RANGE));
            assertNotNull(generatedCase.getRequest());
            assertNotNull(generatedCase.getRuleConfig());
            assertNotNull(generatedCase.getResult());
            assertFalse(generatedCase.getResult().getUnits().isEmpty());
            assertFalse(generatedCase.getQuerySummaries().isEmpty());
            assertFalse(generatedCase.getContinueSteps().isEmpty());
        }
    }
}
