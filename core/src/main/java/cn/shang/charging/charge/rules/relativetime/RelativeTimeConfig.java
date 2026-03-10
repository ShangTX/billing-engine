package cn.shang.charging.charge.rules.relativetime;

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
 * 按相对时间段计费配置
 * <p>
 * 核心逻辑：
 * 1. 以计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 每个周期独立封顶
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class RelativeTimeConfig implements RuleConfig {

    private String id;

    @Builder.Default
    private String type = BConstants.ChargeRuleType.RELATIVE_TIME;

    /**
     * 时间段列表
     * 必须满足：
     * - 按 beginMinute 升序排列
     * - 相邻时间段首尾相连
     * - 首时间段 beginMinute = 0
     * - 末时间段 endMinute = 1440
     */
    private List<RelativeTimePeriod> periods;

    /**
     * 每周期封顶金额
     */
    private BigDecimal maxChargeOneCycle;
}