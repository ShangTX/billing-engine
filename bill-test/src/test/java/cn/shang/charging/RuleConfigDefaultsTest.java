package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleConfigDefaultsTest {

    @Test
    void ruleConfig_providesDefaultIncompleteUnitOptions() {
        RuleConfig config = new MinimalRuleConfig();

        assertEquals(BConstants.IncompleteUnitChargeMode.FULL_CHARGE, config.getIncompleteUnitChargeMode());
        assertNull(config.getThresholdMinutes());
        assertNull(config.getThresholdRatio());
    }

    private static final class MinimalRuleConfig implements RuleConfig {
        @Override
        public String getId() {
            return "minimal";
        }

        @Override
        public String getType() {
            return "minimal";
        }
    }
}
