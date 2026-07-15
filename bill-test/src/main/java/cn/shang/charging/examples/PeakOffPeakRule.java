package cn.shang.charging.examples;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 高峰/平峰按比例计费示例规则。
 *
 * <p>这个示例用于展示“路径 A”：直接实现 {@link BillingRule}，同时复用
 * {@link BoundaryDrivenLoop} 这个轻量调度原语。它不是正式内置规则，也不注册到默认
 * {@code BillingRuleRegistry}，调用方需要手动注册 ruleType = {@value #RULE_TYPE}。</p>
 *
 * <h3>规则语义</h3>
 * <ul>
 *   <li>每天一个高峰时段，例如 08:00-20:00。</li>
 *   <li>高峰和平峰使用不同单元价格。</li>
 *   <li>以 {@code unitMinutes} 作为一个价格单位；不足一个单位的片段按分钟比例收费。</li>
 *   <li>示例只声明支持 {@code CONTINUOUS}，不支持时长模式和 UNIT_BASED。</li>
 * </ul>
 *
 * <h3>边界设计</h3>
 * <p>本规则把所有会影响计费语义的位置交给 {@link BoundaryDrivenLoop} 统一切开：</p>
 * <ul>
 *   <li>费率边界：高峰开始、高峰结束。</li>
 *   <li>单元边界：从当前段起点向后推 {@code unitMinutes}。</li>
 *   <li>免费段边界：FREE_RANGE 的起止时间。</li>
 *   <li>计算终点：本分段的 {@code calcEnd}。</li>
 * </ul>
 *
 * <p>切分后的每个 {@link HomogeneousSegment} 都满足“段内费率一致、免费状态一致”，
 * 因此可以直接转换成 {@link BillingUnit}。</p>
 */
public class PeakOffPeakRule implements BillingRule<PeakOffPeakRule.Config> {

    /** 自定义规则类型。RuleConfig#getType() 和 registry.register(...) 必须使用同一个值。 */
    public static final String RULE_TYPE = "peakOffPeak";

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          Config config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        List<FreeTimeRange> freeRanges = freeRangesOf(promotionAggregate);

        /*
         * 1. 组装边界来源。
         *
         * BoundaryDrivenLoop 每次会从这些 provider 中取“current 之后最近的一个边界”。
         * provider 自身不能修改外部状态，也不能返回 current 之前或 calcEnd 之后的时间。
         */
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(rateBoundaryProvider(config));
        providers.add(unitBoundaryProvider(config.getUnitMinutes()));
        providers.add(BoundaryProviders.freeRangeEdges(freeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        /*
         * 2. 运行边界驱动循环，得到同质段。
         *
         * 同质段不是最终结果，只是“这段时间内计费参数不变”的中间形态。
         * 这样可以把“怎么切时间轴”和“怎么计算金额”拆开，规则逻辑会清楚很多。
         */
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(
                calcBegin,
                calcEnd,
                providers,
                (begin, end) -> buildSegment(begin, end, config, freeRanges));

        /*
         * 3. 示例规则自行把同质段转换为 BillingUnit。
         *
         * 路径 A 的特点是规则完全掌控结果结构；如果规则需要周期封顶、时段封顶、时长模式，
         * 应考虑后续改走 RuleSemantics + 通用策略路径，而不是把这里继续堆复杂。
         */
        List<BillingUnit> units = toUnits(segments);
        BigDecimal totalAmount = units.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PromotionUsage> usages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeRanges, calcBegin, calcEnd);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(units)
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .promotionUsages(usages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    @Override
    public Class<Config> configClass() {
        return Config.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    /**
     * 构建一个同质段。
     *
     * <p>因为免费段边界已经由 {@link BoundaryProviders#freeRangeEdges(List)} 切开，
     * 所以只要某个免费段完整覆盖 [begin, end]，这整个同质段就可以标记为免费。</p>
     */
    private HomogeneousSegment buildSegment(LocalDateTime begin,
                                            LocalDateTime end,
                                            Config config,
                                            List<FreeTimeRange> freeRanges) {
        for (FreeTimeRange range : freeRanges) {
            if (covers(range, begin, end)) {
                return new HomogeneousSegment(
                        begin,
                        end,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        range.getId(),
                        range.getRangeType(),
                        null);
            }
        }

        /*
         * 非免费段按段起点判断当前费率。
         *
         * 由于费率边界已经切开，[begin, end] 内不会同时跨高峰和平峰，
         * 所以用 begin 判断即可。
         */
        BigDecimal unitPrice = isPeak(begin, config)
                ? config.getPeakUnitPrice()
                : config.getOffPeakUnitPrice();
        BigDecimal amount = proportionalAmount(unitPrice, begin, end, config.getUnitMinutes());

        return new HomogeneousSegment(
                begin,
                end,
                unitPrice,
                amount,
                false,
                null,
                null);
    }

    /**
     * 把同质段转换为计费单元。
     *
     * <p>这个示例故意不做 compact 合并，方便读者一眼看到每个边界切出来的结果。
     * 如果业务希望压缩传输体积，可以在规则中自行合并相邻同价同状态单元。</p>
     */
    private List<BillingUnit> toUnits(List<HomogeneousSegment> segments) {
        List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;

        for (HomogeneousSegment segment : segments) {
            BigDecimal charged = segment.isFree()
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : scale(segment.getOriginalAmount());
            accumulated = accumulated.add(charged);

            units.add(BillingUnit.builder()
                    .beginTime(segment.getBeginTime())
                    .endTime(segment.getEndTime())
                    .durationMinutes(segment.durationMinutes())
                    .unitPrice(scale(segment.getUnitPrice()))
                    .originalAmount(scale(segment.getOriginalAmount()))
                    .free(segment.isFree())
                    .freePromotionId(segment.getFreePromotionId())
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .ruleData(segment.getRuleData())
                    .compact(false)
                    .count(1)
                    .build());
        }

        return units;
    }

    /**
     * 单元边界 provider。
     *
     * <p>这里的单元边界以“当前同质段起点”为锚点，而不是以自然整点为锚点。
     * 例如免费段在 08:13 结束、unitMinutes=60，则下一个单元边界是 09:13。</p>
     */
    private BoundaryProvider unitBoundaryProvider(int unitMinutes) {
        return (current, calcEnd) -> {
            LocalDateTime boundary = current.plusMinutes(unitMinutes);
            return boundary.isAfter(calcEnd) ? null : boundary;
        };
    }

    /**
     * 费率边界 provider。
     *
     * <p>只返回 current 之后最近的高峰开始或结束边界。由于计算窗口可能跨天，
     * 每次检查 current 当天和次日两组边界即可覆盖最近候选。</p>
     */
    private BoundaryProvider rateBoundaryProvider(Config config) {
        return (current, calcEnd) -> {
            LocalDateTime dayStart = current.toLocalDate().atStartOfDay();
            LocalDateTime nearest = null;

            for (int i = 0; i <= 1; i++) {
                LocalDateTime day = dayStart.plusDays(i);
                nearest = nearer(current, calcEnd, nearest, day.plusMinutes(config.getPeakBeginMinute()));
                nearest = nearer(current, calcEnd, nearest, day.plusMinutes(config.getPeakEndMinute()));
            }

            return nearest;
        };
    }

    private LocalDateTime nearer(LocalDateTime current,
                                 LocalDateTime calcEnd,
                                 LocalDateTime nearest,
                                 LocalDateTime candidate) {
        if (!candidate.isAfter(current) || candidate.isAfter(calcEnd)) {
            return nearest;
        }
        return nearest == null || candidate.isBefore(nearest) ? candidate : nearest;
    }

    /**
     * 判断时间点是否在高峰时段内。
     *
     * <p>示例为了保持简单，只支持“同日内 begin < end”的高峰区间，
     * 不支持 22:00-06:00 这种跨午夜高峰区间。</p>
     */
    private boolean isPeak(LocalDateTime time, Config config) {
        int minute = time.getHour() * 60 + time.getMinute();
        return minute >= config.getPeakBeginMinute() && minute < config.getPeakEndMinute();
    }

    /**
     * 按分钟比例计算片段金额。
     *
     * <p>公式：片段金额 = 单元价格 * 片段分钟数 / 单元分钟数。</p>
     */
    private BigDecimal proportionalAmount(BigDecimal unitPrice,
                                          LocalDateTime begin,
                                          LocalDateTime end,
                                          int unitMinutes) {
        int minutes = (int) Duration.between(begin, end).toMinutes();
        return unitPrice
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
    }

    private boolean covers(FreeTimeRange range, LocalDateTime begin, LocalDateTime end) {
        return range != null
                && range.getBeginTime() != null
                && range.getEndTime() != null
                && !range.getBeginTime().isAfter(begin)
                && !range.getEndTime().isBefore(end);
    }

    private List<FreeTimeRange> freeRangesOf(PromotionAggregate promotionAggregate) {
        if (promotionAggregate == null || promotionAggregate.getFreeTimeRanges() == null) {
            return List.of();
        }
        return promotionAggregate.getFreeTimeRanges();
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private void validateConfig(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getPeakBeginMinute() < 0 || config.getPeakEndMinute() > 1440
                || config.getPeakBeginMinute() >= config.getPeakEndMinute()) {
            throw new IllegalArgumentException("peak range must be within [0,1440] and begin < end");
        }
        if (config.getPeakUnitPrice() == null || config.getPeakUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("peakUnitPrice must be non-negative");
        }
        if (config.getOffPeakUnitPrice() == null || config.getOffPeakUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("offPeakUnitPrice must be non-negative");
        }
    }

    /**
     * 高峰/平峰规则配置。
     *
     * <p>作为嵌套类放在同一个 Java 文件中，是为了让示例保持自包含。
     * 真实业务中也可以把配置类拆成单独文件。</p>
     */
    @Data
    @Accessors(chain = true)
    public static class Config implements RuleConfig {
        /** 规则配置 ID，业务侧自定义，用于追踪。 */
        private String id;

        /** 一个价格单位的分钟数，例如 60 表示“每小时单价”。 */
        private int unitMinutes = 60;

        /** 高峰开始分钟数，从 00:00 起算。例如 8 * 60 表示 08:00。 */
        private int peakBeginMinute = 8 * 60;

        /** 高峰结束分钟数，从 00:00 起算。例如 20 * 60 表示 20:00。 */
        private int peakEndMinute = 20 * 60;

        /** 高峰单元价格。 */
        private BigDecimal peakUnitPrice = BigDecimal.ZERO;

        /** 平峰单元价格。 */
        private BigDecimal offPeakUnitPrice = BigDecimal.ZERO;

        @Override
        public String getType() {
            return RULE_TYPE;
        }
    }
}
