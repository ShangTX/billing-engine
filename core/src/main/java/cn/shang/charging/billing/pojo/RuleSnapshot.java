package cn.shang.charging.billing.pojo;

import lombok.Data;

/**
 * 计费规则快照（抽象基类）。
 * <p>
 * 用于在特定时刻捕获规则状态（快照语义），具体规则族提供子类实现。
 */
@Data
public abstract class RuleSnapshot {
    /** 规则快照ID */
    String id;
    /** 规则类型（对应 BConstants.ChargeRuleType 中的常量） */
    String type;
}
