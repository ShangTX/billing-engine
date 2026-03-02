package cn.shang.charging.promotion.rules;

import cn.shang.charging.billing.pojo.PromotionRuleConfig;

import java.util.HashMap;
import java.util.Map;

public class PromotionRuleRegistry {

    private final Map<String, PromotionRule<?>> rules = new HashMap<>();

    public <C extends PromotionRuleConfig> void register(
            String type,
            PromotionRule<C> rule) {
        rules.put(type, rule);
    }

    public PromotionRule<?> get(String type) {
        return rules.get(type);
    }

}
