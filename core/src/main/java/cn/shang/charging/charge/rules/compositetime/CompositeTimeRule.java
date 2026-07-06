package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.DurationGlobalStrategy;
import cn.shang.charging.charge.rules.DurationPeriodStrategy;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
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
 *   <li>DURATION_PERIOD → {@link DurationPeriodStrategy}（通用时长 PERIOD 策略，periodCap 由 CompositeTimeSemantics 注入）</li>
 *   <li>DURATION_GLOBAL → {@link DurationGlobalStrategy}（通用时长 GLOBAL 策略）</li>
 * </ul>
 * 一个 {@code compositeTime} type 注册本门面。
 * <p>
 * TODO-20260706-002 阶段2c：CONTINUOUS 逻辑下沉到 CompositeTimeContinuousStrategy，门面回归纯分派。
 * TODO-20260706-002 阶段4：声明支持时长模式，规则族语义由 {@link CompositeTimeSemantics} 注入。
 */
public class CompositeTimeRule implements BillingRule<CompositeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final CompositeTimeContinuousStrategy continuousStrategy = new CompositeTimeContinuousStrategy();
    private final CompositeTimeSemantics compositeTimeSemantics = new CompositeTimeSemantics();

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS,
                BConstants.CalculationMode.DURATION_PERIOD, BConstants.CalculationMode.DURATION_GLOBAL);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        BConstants.CalculationMode mode = context.getCalculationMode();
        if (mode == null) mode = BConstants.CalculationMode.CONTINUOUS;
        return switch (mode) {
            case DURATION_PERIOD -> {
                validateConfig(ruleConfig);
                yield DurationPeriodStrategy.calculate(compositeTimeSemantics, context, ruleConfig, promotionAggregate);
            }
            case DURATION_GLOBAL -> {
                validateConfig(ruleConfig);
                yield DurationGlobalStrategy.calculate(compositeTimeSemantics, context, ruleConfig, promotionAggregate);
            }
            default -> continuousStrategy.calculate(context, ruleConfig, promotionAggregate);
        };
    }

    /**
     * 时长模式配置校验（与 {@link CompositeTimeContinuousStrategy} 一致）。
     */
    private void validateConfig(CompositeTimeConfig config) {
        if (config.getMaxChargeOneCycle() == null || config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle 必填且必须为正数");
        }

        List<CompositePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }

        validatePeriodsContinuous(periods);

        for (CompositePeriod period : periods) {
            validateNaturalPeriodsCoverage(period.getNaturalPeriods());
        }
    }

    private void validatePeriodsContinuous(List<CompositePeriod> periods) {
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("第一个时间段必须从 0 分钟开始");
        }
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("最后一个时间段必须结束于 1440 分钟");
        }
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("相邻时间段必须首尾相连");
            }
        }
    }

    private void validateNaturalPeriodsCoverage(List<NaturalPeriod> naturalPeriods) {
        if (naturalPeriods == null || naturalPeriods.isEmpty()) {
            throw new IllegalArgumentException("naturalPeriods 不能为空");
        }
        int totalCovered = 0;
        for (NaturalPeriod period : naturalPeriods) {
            if (period.getBeginMinute() < period.getEndMinute()) {
                totalCovered += period.getEndMinute() - period.getBeginMinute();
            } else {
                totalCovered += (MINUTES_PER_CYCLE - period.getBeginMinute()) + period.getEndMinute();
            }
        }
        if (totalCovered != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("自然时段必须覆盖全天（0-1440分钟）");
        }
    }
}
