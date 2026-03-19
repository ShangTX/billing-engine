package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 混合计费规则配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class CompositeTimeConfig implements RuleConfig {

    private String id;

    @Builder.Default
    private String type = BConstants.ChargeRuleType.COMPOSITE_TIME;

    /** 周期封顶金额（必填） */
    private BigDecimal maxChargeOneCycle;

    /** 不足单元计费模式（默认全额） */
    @Builder.Default
    private InsufficientUnitMode insufficientUnitMode = InsufficientUnitMode.FULL;

    /** 相对时间段列表 */
    private List<CompositePeriod> periods;
}