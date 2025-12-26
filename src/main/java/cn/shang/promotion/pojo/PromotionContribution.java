package cn.shang.promotion.pojo;

import cn.shang.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可计算的优惠
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PromotionContribution {

    Long id; // id

    BConstants.PromotionType type; // 优惠类型

    BConstants.PromotionSource source; // 优惠来源

}
