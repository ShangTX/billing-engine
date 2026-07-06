package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 多自然时段计费规则门面。
 * <p>
 * 核心逻辑：
 * 1. 24 小时自然周期，按自然时段划分
 * 2. 每个时段有独立价格，统一单元时长
 * 3. 跨时段处理可配置（复用 CrossPeriodMode）
 * 4. 支持每日封顶
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>CONTINUOUS → {@link NaturalTimeContinuousStrategy}（边界驱动切断）</li>
 * </ul>
 * 一个 {@code naturalTime} type 注册本门面。时长模式待阶段 4 接入。
 * <p>
 * TODO-20260706-002 阶段2b：CONTINUOUS 逻辑下沉到 NaturalTimeContinuousStrategy，门面回归纯分派。
 */
public class NaturalTimeRule implements BillingRule<NaturalTimeConfig> {

    private final NaturalTimeContinuousStrategy continuousStrategy = new NaturalTimeContinuousStrategy();

    @Override
    public Class<NaturalTimeConfig> configClass() {
        return NaturalTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          NaturalTimeConfig config,
                                          PromotionAggregate promotionAggregate) {
        return continuousStrategy.calculate(context, config, promotionAggregate);
    }
}
