package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.IncompleteUnitChargeSpec;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.flatfree.FlatFreeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    @Test
    void ruleConfig_canReadIncompleteUnitChargeSpecByDefault() {
        RuleConfig config = new SpecBackedRuleConfig();

        assertEquals(BConstants.IncompleteUnitChargeMode.THRESHOLD_RATIO, config.getIncompleteUnitChargeMode());
        assertEquals(20, config.getThresholdMinutes());
        assertEquals(0, new BigDecimal("0.60").compareTo(config.getThresholdRatio()));
    }

    @Test
    void builtInRuleConfigs_preferSpecOverLegacyFields() {
        IncompleteUnitChargeSpec spec = IncompleteUnitChargeSpec.builder()
                .mode(BConstants.IncompleteUnitChargeMode.THRESHOLD_MINUTES)
                .thresholdMinutes(30)
                .thresholdRatio(new BigDecimal("0.75"))
                .build();
        BigDecimal legacyRatio = new BigDecimal("0.25");

        assertIncompleteSpec(DayNightConfig.builder()
                .incompleteUnitChargeSpec(spec)
                .incompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .thresholdMinutes(5)
                .thresholdRatio(legacyRatio)
                .build());
        assertIncompleteSpec(RelativeTimeConfig.builder()
                .incompleteUnitChargeSpec(spec)
                .incompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .thresholdMinutes(5)
                .thresholdRatio(legacyRatio)
                .build());
        assertIncompleteSpec(NaturalTimeConfig.builder()
                .incompleteUnitChargeSpec(spec)
                .incompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .thresholdMinutes(5)
                .thresholdRatio(legacyRatio)
                .build());
        assertIncompleteSpec(CompositeTimeConfig.builder()
                .incompleteUnitChargeSpec(spec)
                .incompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .thresholdMinutes(5)
                .thresholdRatio(legacyRatio)
                .build());
        assertIncompleteSpec(FlatFreeConfig.builder()
                .incompleteUnitChargeSpec(spec)
                .build());
    }

    @Test
    void builtInRuleConfigs_keepLegacyFieldsWhenSpecIsAbsent() {
        RuleConfig config = RelativeTimeConfig.builder()
                .incompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.THRESHOLD_RATIO)
                .thresholdMinutes(10)
                .thresholdRatio(new BigDecimal("0.40"))
                .build();

        assertEquals(BConstants.IncompleteUnitChargeMode.THRESHOLD_RATIO, config.getIncompleteUnitChargeMode());
        assertEquals(10, config.getThresholdMinutes());
        assertEquals(0, new BigDecimal("0.40").compareTo(config.getThresholdRatio()));
    }

    private static void assertIncompleteSpec(RuleConfig config) {
        assertEquals(BConstants.IncompleteUnitChargeMode.THRESHOLD_MINUTES, config.getIncompleteUnitChargeMode());
        assertEquals(30, config.getThresholdMinutes());
        assertEquals(0, new BigDecimal("0.75").compareTo(config.getThresholdRatio()));
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

    private static final class SpecBackedRuleConfig implements RuleConfig {
        private final IncompleteUnitChargeSpec incompleteUnitChargeSpec = IncompleteUnitChargeSpec.builder()
                .mode(BConstants.IncompleteUnitChargeMode.THRESHOLD_RATIO)
                .thresholdMinutes(20)
                .thresholdRatio(new BigDecimal("0.60"))
                .build();

        @Override
        public String getId() {
            return "spec-backed";
        }

        @Override
        public String getType() {
            return "spec-backed";
        }

        @Override
        public IncompleteUnitChargeSpec getIncompleteUnitChargeSpec() {
            return incompleteUnitChargeSpec;
        }
    }
}
