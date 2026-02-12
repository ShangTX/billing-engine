package cn.shang.charging.promotion.rules.minutes;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.List;

@Builder
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class FreeMinutesConfig extends PromotionRuleConfig {



    // 不需要
}
