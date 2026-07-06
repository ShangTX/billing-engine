package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Accessors(chain = true)
public class FreeMinutes {

    private String id;

    private Integer minutes;

    // 优先级
    private Integer priority;

    // 优惠来源（TODO-20260706-003，从 PromotionGrant 透传）
    private BConstants.PromotionSource source;

}
