package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 等效金额计算规格（TODO-20260706-003）。
 * <p>
 * 控制是否计算以及计算哪些优惠的等效金额。{@code null} 字段表示不限（全部）。
 * {@link BillingRequest#getEquivalentAmountSpec()} 为 {@code null} 时不计算等效金额（默认）。
 *
 * <ul>
 *   <li>{@code promotionIds == null}：不限优惠 id（所有优惠参与）</li>
 *   <li>{@code promotionIds != null}：仅指定 id 的优惠参与</li>
 *   <li>{@code types == null}：不限优惠类型</li>
 *   <li>{@code types != null}：仅指定类型的优惠参与（如 FREE_RANGE / FREE_MINUTES）</li>
 * </ul>
 * 两个维度取交集。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquivalentAmountSpec {

    /** 指定优惠 id（null=不限） */
    private Set<String> promotionIds;

    /** 指定优惠类型（null=不限） */
    private Set<BConstants.PromotionType> types;
}
