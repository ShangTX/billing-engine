package cn.shang.charging.billing.pojo;

import lombok.Data;

public interface PromotionRuleConfig {
    String getId();
    String getType();
    Integer getPriority();
}
