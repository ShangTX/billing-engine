package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.Data;

import java.util.List;

/**
 * 优惠规则快照（抽象基类）。
 * <p>
 * 用于在特定时刻捕获优惠规则状态，具体优惠规则族提供子类实现。
 * {@link #grant} 方法根据上下文生成当前窗口下的优惠授予列表。
 */
@Data
public abstract class PromotionRuleSnapshot {
    /** 规则快照ID */
    String id;
    /** 规则类型 */
    String type;

    /**
     * 根据计费上下文和计算窗口，生成优惠授予列表。
     *
     * @param ctx    计费上下文
     * @param window 计算窗口
     * @return 优惠授予列表
     */
    public abstract List<PromotionGrant> grant(BillingContext ctx, CalculationWindow window);
}
