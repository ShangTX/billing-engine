package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSupport;
import cn.shang.charging.charge.rules.SimplificationSupport;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `dayNight` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 自然日封顶 + 简化计算（全局空隙实现，决策 C）。
 * 复用不足单元计费等公共基础设施（{@link ContinuousStrategy}）与 FREE_MINUTES 时段化（{@link RuleSupport}），
 * 不再继承旧基类 {@code AbstractTimeBasedRule}（TODO-20260706-002 阶段7 废弃）。
 * 封顶 + 累计逻辑由通用 {@link ContinuousStrategy#applyCapAndAccumulate} 承载
 * （TODO-20260706-002 阶段3：4 份合并为 1 份），周期切换/cap 标记等差异由 {@link DayNightSemantics} 注入。
 * <p>
 * 简化路径改"全局视角算无优惠空隙"实现（TODO-20260706-002 阶段3b）：
 * 从 {@code freeTimeRanges} 直接算无优惠空隙，每个 gap 对齐周期边界，
 * 覆盖周期数 > 阈值则生成简化单元，否则与优惠段一起走边界驱动。
 * 旧切段模型（splitTimeAxis/organizeByCycle/TimeFragment/CycleFragments）已迁移到全局空隙实现。
 * <p>
 * 由 {@link DayNightRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 */
final class DayNightContinuousStrategy implements BillingRule<DayNightConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightSemantics dayNightSemantics = new DayNightSemantics();

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();

        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, context.getWindow());
        final List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // CONTINUOUS 模式不支持 BUBBLE 免费时段（bubble 需 effective 周期，CONTINUOUS 未消费）
        ContinuousStrategy.assertNoBubbleSupported(freeTimeRanges);
        // 周期封顶金额
        BigDecimal cycleCapAmount = dayNightSemantics.cycleCap(config);
        // 是否使用简化
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && ContinuousStrategy.isSimplificationEnabled(config, context.getBillingConfigResolver(), context, cycleCapAmount);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        // 全局空隙实现（决策 C）：算无优惠空隙，长空隙简化，短空隙与优惠段走边界驱动
        // FREE_MINUTES 时段化后免费段参与 gaps，简化能正确处理，不再保守排除
        List<BillingUnit> allUnits;
        if (simplificationEnabled && threshold > 0) {
            allUnits = generateUnitsByGlobalGaps(calcBegin, calcEnd, context, config,
                    freeTimeRanges, threshold);
        } else {
            allUnits = calculateBoundaryDriven(calcBegin, calcEnd, context, config, freeTimeRanges);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        BigDecimal accumulatedAmount = BigDecimal.ZERO;
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd);
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        if (materialized.getPromotionUsages() != null) {
            allUsages.addAll(materialized.getPromotionUsages());
        }

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    /**
     * 全局空隙实现（决策 C）：
     * <ol>
     *   <li>从 {@code freeTimeRanges} 算无优惠空隙（gap = 优惠段之间的间隙 + 头尾）</li>
     *   <li>每个 gap 对齐周期边界，覆盖完整周期数 &gt; 阈值 → 简化单元；周期边界外的 gap 头尾片段走边界驱动</li>
     *   <li>优惠段（gap 之间的免费时段）走边界驱动生成明细</li>
     * </ol>
     * 核心算法（computeGaps + findSimplifiedBlock）由 {@link SimplificationSupport} 承载，
     * 与 PERIOD 时长模式简化共用一份。CONTINUOUS 已校验无 bubble，bubbleRanges 传空，
     * effective offset = 自然 offset，行为与原切段模型一致。
     * 封顶/累计由 {@link ContinuousStrategy#applyCapAndAccumulate} 在每个边界驱动片段内独立处理
     * （简化段与优惠段均以 carryOverAccumulated=0 起算）。
     */
    private List<BillingUnit> generateUnitsByGlobalGaps(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                        BillingContext context, DayNightConfig config,
                                                        List<FreeTimeRange> freeTimeRanges, int threshold) {
        // 1. 算无优惠空隙（CONTINUOUS 已校验无 bubble）
        List<SimplificationSupport.Range> gaps = SimplificationSupport.computeGaps(calcBegin, calcEnd, freeTimeRanges);

        // 2. 把每个 gap 拆为"完整周期块（可简化）+ 头尾部分片段（走边界驱动）"，优惠段单独走边界驱动
        int cycleMinutes = MINUTES_PER_CYCLE;
        BigDecimal cycleCapAmount = dayNightSemantics.cycleCap(config);
        List<BillingUnit> allUnits = new ArrayList<>();

        LocalDateTime promoCursor = calcBegin;
        for (SimplificationSupport.Range gap : gaps) {
            // gap 之前的优惠段（promoCursor ~ gap.begin）走边界驱动
            if (gap.begin.isAfter(promoCursor)) {
                allUnits.addAll(calculateBoundaryDriven(promoCursor, gap.begin, context, config, freeTimeRanges));
            }

            // CONTINUOUS 无 bubble，bubbleRanges=List.of()，effective offset = 自然 offset
            SimplificationSupport.SimplifiedBlock block = SimplificationSupport.findSimplifiedBlock(
                    gap, calcBegin, List.of(), cycleMinutes, threshold);

            if (block != null) {
                // 头部部分片段（gap.begin ~ 简化块起点）走边界驱动
                if (block.begin.isAfter(gap.begin)) {
                    allUnits.addAll(calculateBoundaryDriven(gap.begin, block.begin, context, config, freeTimeRanges));
                }
                // 完整周期块 → 简化单元（startK/cycleCount 与原逻辑一致，buildSimplifiedUnit 用 calcBegin 锚定周期边界）
                allUnits.add(ContinuousStrategy.buildSimplifiedUnit(
                        block.startK, block.cycleCount, cycleCapAmount, calcBegin, cycleMinutes));
                // 尾部部分片段（简化块终点 ~ gap.end）走边界驱动
                if (block.end.isBefore(gap.end)) {
                    allUnits.addAll(calculateBoundaryDriven(block.end, gap.end, context, config, freeTimeRanges));
                }
            } else {
                // 完整周期数不足阈值，整个 gap 走边界驱动
                allUnits.addAll(calculateBoundaryDriven(gap.begin, gap.end, context, config, freeTimeRanges));
            }

            promoCursor = gap.end;
        }
        // 末尾优惠段（最后一个 gap 之后到 calcEnd）走边界驱动
        if (promoCursor.isBefore(calcEnd)) {
            allUnits.addAll(calculateBoundaryDriven(promoCursor, calcEnd, context, config, freeTimeRanges));
        }

        return allUnits;
    }

    /**
     * 边界驱动路径：构造 providers + {@link BoundaryDrivenLoop#run} + {@link ContinuousStrategy#applyCapAndAccumulate}。
     * 供非简化路径与简化路径的头尾/优惠段复用。
     */
    private List<BillingUnit> calculateBoundaryDriven(LocalDateTime begin, LocalDateTime end,
            BillingContext context, DayNightConfig config, List<FreeTimeRange> freeTimeRanges) {
        if (!begin.isBefore(end)) {
            return new ArrayList<>();
        }
        LocalDateTime cycleOriginBegin = context.getBeginTime();
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();
        int unitMinutes = config.getUnitMinutes();

        // snapUsDayFlag：splitDayNightBoundary=false 时，snap 到 unitStart 的后段记录的归属（day?）。
        // 跨越单元整体归占优侧同质段，snap==us 时后段起点(us)落在 b 前非占优侧，
        // 此时段归属由 snap 决定（而非段起点时间点），避免再次计算跨段价格。
        Map<LocalDateTime, Boolean> snapUsDayFlag = new HashMap<>();

        // 边界来源（BoundaryDrivenLoop 取所有 provider 返回边界的并集，按时间排序切断时间轴）：
        List<BoundaryProvider> providers = new ArrayList<>();
        // 1. 周期结束边界（24h 循环，maxChargeOneDay 封顶周期）：cycleOrigin + k*1440
        providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, MINUTES_PER_CYCLE));
        // 2. 日夜边界：
        //    splitDayNightBoundary=true（默认）：在精确 dayBegin/dayEnd 处切断，段纯 day/night。
        //    splitDayNightBoundary=false：日夜边界 snap 到 unit edge，跨越单元整体归 blockWeight 占优侧
        //      同质段，段内统一单价（占优侧），定价用时间点（snap 归属或段起点 isInDay），不用窗口。
        providers.add((current, e) -> {
            List<LocalDateTime> result = new ArrayList<>();
            LocalDateTime day = current.toLocalDate().atStartOfDay();
            for (int offset = 0; offset <= 1; offset++) {
                LocalDateTime dayStart = day.plusDays(offset);
                LocalDateTime dayBegin = dayStart.plusMinutes(dayBeginMin);
                LocalDateTime dayEnd = dayBeginMin < dayEndMin
                        ? dayStart.plusMinutes(dayEndMin)
                        : dayStart.plusDays(1).plusMinutes(dayEndMin);
                for (LocalDateTime exactBoundary : List.of(dayBegin, dayEnd)) {
                    if (!exactBoundary.isAfter(current) || exactBoundary.isAfter(e)) {
                        continue;
                    }
                    if (Boolean.FALSE.equals(config.getSplitDayNightBoundary())) {
                        boolean isDayBegin = exactBoundary.equals(dayBegin);
                        LocalDateTime snapped = snapDayNightBoundary(cycleOriginBegin, exactBoundary,
                                unitMinutes, dayBeginMin, dayEndMin, config.getBlockWeight(), snapUsDayFlag, isDayBegin);
                        if (snapped != null && snapped.isAfter(current) && !snapped.isAfter(e)) {
                            result.add(snapped);
                        }
                    } else {
                        result.add(exactBoundary);
                    }
                }
            }
            return result;
        });
        // 3. 免费时段起止边界：FREE_RANGE 免费段切断单元，免费段内单元免费（CONTINUOUS 优惠语义）
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        // 4. 计算窗口终点：确保最后一段在 calcEnd 切断
        providers.add(BoundaryProviders.calcEnd(end));

        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(begin, end, providers,
                (current, next) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, snapUsDayFlag));

        return ContinuousStrategy.applyCapAndAccumulate(segments, dayNightSemantics, context, config,
                cycleOriginBegin, begin, null);
    }

    private void validateConfig(DayNightConfig config) {
        if (config.getMaxChargeOneDay() == null) {
            throw new IllegalArgumentException("maxChargeOneDay is required");
        }
        if (config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }

    private HomogeneousSegment buildSegmentForDayNight(LocalDateTime current,
                                                       LocalDateTime next,
                                                       DayNightConfig config,
                                                       List<FreeTimeRange> freeTimeRanges,
                                                       Map<LocalDateTime, Boolean> snapUsDayFlag) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), null);
            }
        }
        // 时间点定价（不用窗口算 dayMinutes）：
        //   snap us 后段（snapUsDayFlag 命中）：用 snap 时记录的占优侧归属（段起点落在 b 前非占优侧时，
        //     段起点时间点会给错单价，故用 snap 归属）。
        //   其余段（纯段 / snap ue 前段）：用段起点时间点 isInDay（起点必在占优侧）。
        boolean day = snapUsDayFlag.getOrDefault(current,
                isInDay(current, config.getDayBeginMinute(), config.getDayEndMinute()));
        BigDecimal unitPrice = day ? config.getDayUnitPrice() : config.getNightUnitPrice();
        return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                false, null, null);
    }

    /**
     * splitDayNightBoundary=false 时，将日夜边界 {@code exactBoundary} snap 到 unit edge：
     * 跨越单元 [unitStart, unitEnd] 整体归入 blockWeight 占优侧同质段。
     * <ul>
     *   <li>{@code exactBoundary} 恰好落在 unit edge（非跨越单元）：直接返回该边界。</li>
     *   <li>{@code exactBoundary} 在 unit 内部：snap 到占优侧 unit edge。
     *     <ul>
     *       <li>day 占优：snap unitEnd（跨越单元归前段 day）或 unitStart（归后段 day）</li>
     *       <li>night 占优：跨越单元整体归入更大时段（避免切断）
     *         <ul>
     *           <li>dayBegin（night→day）：snap unitStart，跨越单元归入后段 day</li>
     *           <li>dayEnd（day→night）：snap unitEnd，跨越单元归入前段 day</li>
     *         </ul>
     *       </li>
     *     </ul>
     *     snap==unitStart 时，后段起点落在 b 前非占优侧，需在 {@code snapUsDayFlag} 记录归属供段构造定价。</li>
     * </ul>
     *
     * @param isDayBegin true=dayBegin边界（night→day），false=dayEnd边界（day→night）
     * @return snap 后的边界；若 unit 无效返回 null
     */
    private static LocalDateTime snapDayNightBoundary(LocalDateTime cycleOrigin, LocalDateTime exactBoundary,
                                                     int unitMinutes, int dayBeginMin, int dayEndMin,
                                                     BigDecimal blockWeight, Map<LocalDateTime, Boolean> snapUsDayFlag,
                                                     boolean isDayBegin) {
        long minutesFromOrigin = Duration.between(cycleOrigin, exactBoundary).toMinutes();
        long unitIndex = Math.floorDiv(minutesFromOrigin, unitMinutes);
        LocalDateTime unitStart = cycleOrigin.plusMinutes(unitIndex * unitMinutes);
        LocalDateTime unitEnd = unitStart.plusMinutes(unitMinutes);
        // 边界恰好落在 unit edge：非跨越单元，直接用原边界
        if (exactBoundary.equals(unitStart) || exactBoundary.equals(unitEnd)) {
            return exactBoundary;
        }
        // 跨越单元：按完整 unit 计算 day 占比，snap 到占优侧 unit edge
        int dayMins = countDayMinutes(unitStart, unitEnd, dayBeginMin, dayEndMin);
        boolean belongsToDay = BigDecimal.valueOf(dayMins)
                .compareTo(blockWeight.multiply(BigDecimal.valueOf(unitMinutes))) >= 0;
        boolean usInDay = isInDay(unitStart, dayBeginMin, dayEndMin);
        LocalDateTime snapped;
        if (belongsToDay) {
            // day 占优：dayEnd(day 在 b 前)->unitEnd；dayBegin(day 在 b 后)->unitStart
            snapped = usInDay ? unitEnd : unitStart;
        } else {
            // night 占优：跨越单元整体归入更大时段（避免切断）
            if (isDayBegin) {
                // dayBegin（night→day）：snap unitStart，跨越单元归入后段 day
                snapped = unitStart;
            } else {
                // dayEnd（day→night）：snap unitEnd，跨越单元归入前段 day
                snapped = unitEnd;
            }
        }
        // snap==unitStart 时后段从 us 开始（us 在 b 前非占优侧），记录归属供段构造定价
        if (snapped.equals(unitStart)) {
            snapUsDayFlag.put(unitStart, belongsToDay);
        }
        return snapped;
    }

    /** {@code time} 是否在白天时段（按 dayBeginMinute/dayEndMinute 配置）。 */
    private static boolean isInDay(LocalDateTime time, int dayBeginMin, int dayEndMin) {
        int minute = time.getHour() * 60 + time.getMinute();
        if (dayBeginMin < dayEndMin) {
            return minute >= dayBeginMin && minute < dayEndMin;
        }
        return minute >= dayBeginMin || minute < dayEndMin;
    }

    /** [begin, end) 区间内落在白天时段的分钟数。 */
    private static int countDayMinutes(LocalDateTime begin, LocalDateTime end, int dayBeginMin, int dayEndMin) {
        int dayMins = 0;
        LocalDateTime current = begin;
        while (current.isBefore(end)) {
            if (isInDay(current, dayBeginMin, dayEndMin)) {
                dayMins++;
            }
            current = current.plusMinutes(1);
        }
        return dayMins;
    }
}
