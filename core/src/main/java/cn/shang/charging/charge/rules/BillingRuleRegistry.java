package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.charge.rules.daynight.DayNightRule;

import java.util.HashMap;
import java.util.Map;

/**
 * 计费规则
 */
public class BillingRuleRegistry {

    Map<String, BillingRule> ruleMap;

    public BillingRuleRegistry() {
        init();
    }

    /**
     * 初始化基本数据
     */
    private void init() {
        ruleMap = new HashMap<>();
        // 日夜分段计费
        ruleMap.put(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
    }

    public BillingRule get(String ruleType) {
        return ruleMap.get(ruleType);
    }

    /**
     * 注册计费规则
     * @param ruleType 类型
     * @param rule 规则
     */
    public void register(String ruleType, BillingRule rule) {
        ruleMap.put(ruleType, rule);
    }

}
