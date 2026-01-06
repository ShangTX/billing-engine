package cn.shang.charging.billing.pojo;

import lombok.Data;

/**
 * 计费规则
 */
@Data
public abstract class RuleSnapshot {
    String id;
    String type;
}
