package cn.shang.charging.promotion.rules.ranges;

import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Builder
@Data
public class FreeTimeRangePromotionConfig extends PromotionRuleConfig {
}
