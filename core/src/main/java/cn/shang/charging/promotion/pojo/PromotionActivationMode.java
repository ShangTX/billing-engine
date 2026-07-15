package cn.shang.charging.promotion.pojo;

/**
 * Controls whether a free promotion participates in charging.
 */
public enum PromotionActivationMode {
    /** Always active. */
    ALWAYS,
    /** Active only when the billing end time falls inside the generated free range. */
    END_WITHIN_RANGE
}
