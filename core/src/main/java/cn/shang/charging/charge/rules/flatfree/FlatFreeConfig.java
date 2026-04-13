package cn.shang.charging.charge.rules.flatfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class FlatFreeConfig implements RuleConfig {

    String id;

    @Builder.Default
    String type = BConstants.ChargeRuleType.FLAT_FREE;
}