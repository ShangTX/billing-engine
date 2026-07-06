package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 混合时间计费规则门面。
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按相对时间段划分，每个时间段可有不同的单元长度
 * 3. 每个时间段内按自然时段配置不同的价格
 * 4. 支持时间段独立封顶和周期封顶
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>CONTINUOUS → {@link CompositeTimeContinuousStrategy}（边界驱动切断 + 气泡抽出 + periodCap）</li>
 * </ul>
 * 一个 {@code compositeTime} type 注册本门面。时长模式待阶段 4 接入。
 * <p>
 * TODO-20260706-002 阶段2c：CONTINUOUS 逻辑下沉到 CompositeTimeContinuousStrategy，门面回归纯分派。
 */
public class CompositeTimeRule implements BillingRule<CompositeTimeConfig> {

    private final CompositeTimeContinuousStrategy continuousStrategy = new CompositeTimeContinuousStrategy();

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        return continuousStrategy.calculate(context, ruleConfig, promotionAggregate);
    }
}
