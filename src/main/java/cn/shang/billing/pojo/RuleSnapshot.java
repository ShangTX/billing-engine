package cn.shang.billing.pojo;

import lombok.Data;

/**
 * 计费规则
 */
@Data
public abstract class RuleSnapshot {
    Long id;
    Integer type;
}
