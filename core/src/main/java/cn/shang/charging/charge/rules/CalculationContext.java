package cn.shang.charging.charge.rules;

import lombok.Builder;
import lombok.Data;

/**
 * 计算上下文 - 集中管理跳过判断
 * <p>
 * 用于简单优先原则：高级特性通过配置化跳过隔离，简单场景零判断开销
 */
@Data
@Builder
public class CalculationContext {
    /** 是否为 CONTINUE 模式 */
    private boolean hasContinueMode;

    /** 是否有优惠 */
    private boolean hasPromotion;

    /** 是否有多种优惠类型 */
    private boolean hasMultiplePromotionTypes;

    /** 是否有特殊配置（时间段封顶等） */
    private boolean hasComplexFeatures;

    // ==================== 跳过判断方法 ====================

    /** 是否跳过状态恢复 */
    public boolean shouldSkipStateRestore() {
        return !hasContinueMode;
    }

    /** 是否跳过优惠处理 */
    public boolean shouldSkipPromotionHandling() {
        return !hasPromotion;
    }

    /** 是否为简单优惠（单一类型） */
    public boolean isSimplePromotion() {
        return hasPromotion && !hasMultiplePromotionTypes;
    }

    /** 是否简化状态输出 */
    public boolean shouldSimplifyStateOutput() {
        return !hasContinueMode;
    }
}