package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 按相对时间段计费规则门面。
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 计费单元在时间段边界会被截断，不足一个单元长度的部分收全额
 * 4. 每个周期独立封顶，超出时从最后一个单元开始削减
 * 5. 免费时段完全覆盖计费单元才免费
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>CONTINUOUS → {@link RelativeTimeContinuousStrategy}（边界驱动切断）</li>
 * </ul>
 * 一个 {@code relativeTime} type 注册本门面。时长模式待阶段 4 接入。
 * <p>
 * TODO-20260706-002 阶段2a：CONTINUOUS 逻辑下沉到 RelativeTimeContinuousStrategy，门面回归纯分派。
 */
public class RelativeTimeRule implements BillingRule<RelativeTimeConfig> {

    private final RelativeTimeContinuousStrategy continuousStrategy = new RelativeTimeContinuousStrategy();

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        return continuousStrategy.calculate(context, config, promotionAggregate);
    }
}
