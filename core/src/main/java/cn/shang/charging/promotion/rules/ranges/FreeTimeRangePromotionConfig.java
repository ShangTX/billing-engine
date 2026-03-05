package cn.shang.charging.promotion.rules.ranges;

import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Builder
@Data
public class FreeTimeRangePromotionConfig implements PromotionRuleConfig {

    String id;
    String type;
    Integer priority;

}
