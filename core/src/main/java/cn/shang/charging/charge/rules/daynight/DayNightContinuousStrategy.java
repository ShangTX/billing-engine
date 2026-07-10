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
import cn.shang.charging.charge.rules.PricingState;
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
        int unitMinutes = config.getUnitMinutes();

        // 初始化 PricingState：根据起点判断初始价格
        boolean isInDayAtBegin = isInDay(begin, config.getDayBeginMinute(), config.getDayEndMinute());
        BigDecimal initialUnitPrice = isInDayAtBegin ? config.getDayUnitPrice() : config.getNightUnitPrice();
        PricingState state = PricingState.builder()
                .currentUnitPrice(initialUnitPrice)
                .unitMinutes(unitMinutes)
                .cycleOrigin(cycleOriginBegin)
                .build();

        // 边界来源（BoundaryDrivenLoop 取所有 provider 返回边界的并集，按时间排序切断时间轴）：
        List<BoundaryProvider> providers = new ArrayList<>();
        // 1. 周期结束边界（24h 循环，maxChargeOneDay 封顶周期）：cycleOrigin + k*1440
        providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, MINUTES_PER_CYCLE));
        // 2. 日夜边界（待用户实现）
        providers.add(createDayNightBoundaryProvider(config));
        // 3. 免费时段起止边界：FREE_RANGE 免费段切断单元，免费段内单元免费（CONTINUOUS 优惠语义）
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        // 4. 计算窗口终点：确保最后一段在 calcEnd 切断
        providers.add(BoundaryProviders.calcEnd(end));

        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(begin, end, providers,
                (current, next, stateParam) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, stateParam),
                state);

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

    /**
     * 创建日夜边界提供器。
     * <p>
     * TODO: 用户需要实现该方法，根据 splitDayNightBoundary 配置返回合适的边界列表：
     * <ul>
     *   <li>splitDayNightBoundary=true（默认）：返回精确的日夜边界时间点</li>
     *   <li>splitDayNightBoundary=false：返回snap到单元边界的日夜边界时间点</li>
     * </ul>
     *
     * @param config DayNight配置
     * @return BoundaryProvider lambda，返回当前范围内的日夜边界列表
     */
    private BoundaryProvider createDayNightBoundaryProvider(DayNightConfig config) {
        return (current, end, state) -> {
            List<LocalDateTime> boundaries = new ArrayList<>(1);

            // 1.首先确认日夜边界位置，day-night 和 night-day
            int dayBeginMin = config.getDayBeginMinute();
            int dayEndMin = config.getDayEndMinute();

            // 2.确认current相邻的最近的一个边界位置
            LocalDateTime nearestBoundary = findNearestDayNightBoundary(current, end, dayBeginMin, dayEndMin);

            if (nearestBoundary == null || nearestBoundary.isAfter(end)) {
                return boundaries; // 范围内无边界
            }

            // 判断边界类型：是 dayBegin 还是 dayEnd
            boolean isDayBeginBoundary = isDayBeginBoundaryPoint(nearestBoundary, dayBeginMin);

            // 3. 根据splitDayNightBoundary配置决定是否需要snap
            if (config.getSplitDayNightBoundary() == null || config.getSplitDayNightBoundary()) {
                // 3-1. 截断处理，直接以此日夜边界作为此次边界
                boundaries.add(nearestBoundary);
                // 截断模式：边界处立即切换价格
                // dayBegin 边界：从 night 切到 day；dayEnd 边界：从 day 切到 night
                BigDecimal newPrice = isDayBeginBoundary ? config.getDayUnitPrice() : config.getNightUnitPrice();
                state.setCurrentUnitPrice(newPrice);
            } else {
                // 3-2. 非截断处理snap
                // 4.1 snap 计算从current开始对齐单元边界，找到包含nearestBoundary的单元
                int unitMinutes = config.getUnitMinutes();
                long minutesToBoundary = Duration.between(current, nearestBoundary).toMinutes();
                long unitIndex = Math.floorDiv(minutesToBoundary, unitMinutes);

                // 包含boundary的单元（可能跨越）
                LocalDateTime unitStart = current.plusMinutes(unitIndex * unitMinutes);
                LocalDateTime unitEnd = unitStart.plusMinutes(unitMinutes);

                // 4.2 snap 计算最后一个边界是否跨越，如果未跨越则可以直接结束处理，使用此边界作为结果
                if (nearestBoundary.equals(unitStart) || nearestBoundary.equals(unitEnd)) {
                    // 边界恰好落在单元边界上，未跨越
                    boundaries.add(nearestBoundary);
                    // snap 模式：snap 到边界后也要切换价格
                    BigDecimal newPrice = isDayBeginBoundary ? config.getDayUnitPrice() : config.getNightUnitPrice();
                    state.setCurrentUnitPrice(newPrice);
                } else {
                    // 4.2 snap 处理跨越情况，判断此单元归属于前一个日夜分段还是后一个日夜分段
                    int dayMinutes = countDayMinutes(unitStart, unitEnd, dayBeginMin, dayEndMin);
                    boolean belongsToDay = BigDecimal.valueOf(dayMinutes)
                            .compareTo(config.getBlockWeight().multiply(BigDecimal.valueOf(unitMinutes))) >= 0;

                    // 按单元起点判断边界类型：unitStart在night时段=dayBegin边界，在day时段=dayEnd边界
                    boolean isDayBeginBoundaryFromUnit = isInDay(unitStart, dayBeginMin, dayEndMin);

                    // 4.3 snap 根据边界类型和归属判断决定snap方向
                    LocalDateTime snapped;
                    if (belongsToDay) {
                        // day占优：dayBegin snap到unitEnd（入day），dayEnd snap到unitStart（留day）
                        snapped = isDayBeginBoundaryFromUnit ? unitEnd : unitStart;
                        // snap 到 unitEnd 后切到 day，snap 到 unitStart 后仍保持 day（因为单元归属 day）
                        BigDecimal newPrice = config.getDayUnitPrice();
                        state.setCurrentUnitPrice(newPrice);
                    } else {
                        // night占优：dayBegin snap到unitEnd（整个单元留night），dayEnd snap到unitEnd（入night）
                        snapped = isDayBeginBoundaryFromUnit ? unitEnd : unitEnd;
                        // snap 到 unitEnd 后切到 night（无论哪种情况）
                        BigDecimal newPrice = config.getNightUnitPrice();
                        state.setCurrentUnitPrice(newPrice);
                    }

                    boundaries.add(snapped);
                }
            }

            return boundaries;
        };
    }

    /**
     * 查找current到end范围内最近的日夜边界。
     */
    private LocalDateTime findNearestDayNightBoundary(LocalDateTime current, LocalDateTime end, int dayBeginMin, int dayEndMin) {
        // 当天的dayBegin和dayEnd
        int dayBeginHour = dayBeginMin / 60;
        int dayBeginMinute = dayBeginMin % 60;
        LocalDateTime dayBeginToday = current.withHour(dayBeginHour).withMinute(dayBeginMinute).withSecond(0).withNano(0);

        int dayEndHour = dayEndMin / 60;
        int dayEndMinute = dayEndMin % 60;
        LocalDateTime dayEndToday = current.withHour(dayEndHour).withMinute(dayEndMinute).withSecond(0).withNano(0);

        // 收集候选边界
        List<LocalDateTime> candidates = new ArrayList<>();
        if (dayBeginToday.isAfter(current) && !dayBeginToday.isAfter(end)) {
            candidates.add(dayBeginToday);
        }
        if (dayEndToday.isAfter(current) && !dayEndToday.isAfter(end)) {
            candidates.add(dayEndToday);
        }

        // 如果当天边界都已过，检查下一天
        if (candidates.isEmpty()) {
            LocalDateTime nextDay = current.plusDays(1).toLocalDate().atStartOfDay();
            LocalDateTime dayBeginNextDay = nextDay.plusMinutes(dayBeginMin);
            LocalDateTime dayEndNextDay = nextDay.plusMinutes(dayEndMin);

            if (dayBeginNextDay.isAfter(current) && !dayBeginNextDay.isAfter(end)) {
                candidates.add(dayBeginNextDay);
            }
            if (dayEndNextDay.isAfter(current) && !dayEndNextDay.isAfter(end)) {
                candidates.add(dayEndNextDay);
            }
        }

        // 返回最近的边界
        return candidates.isEmpty() ? null : candidates.stream().min(LocalDateTime::compareTo).orElse(null);
    }

    /**
     * 计算一个时间区间内落在白天的分钟数。
     */
    private static int countDayMinutes(LocalDateTime begin, LocalDateTime end, int dayBeginMin, int dayEndMin) {
        int dayMins = 0;
        LocalDateTime cursor = begin;
        while (cursor.isBefore(end)) {
            if (isInDay(cursor, dayBeginMin, dayEndMin)) {
                dayMins++;
            }
            cursor = cursor.plusMinutes(1);
        }
        return dayMins;
    }

    private HomogeneousSegment buildSegmentForDayNight(LocalDateTime current,
                                                       LocalDateTime next,
                                                       DayNightConfig config,
                                                       List<FreeTimeRange> freeTimeRanges,
                                                       PricingState state) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), null);
            }
        }
        // 从 PricingState 读取价格，无需重复判断 isInDay()
        BigDecimal unitPrice = state.getCurrentUnitPrice();
        return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                false, null, null);
    }

    /** {@code time} 是否在白天时段（按 dayBeginMinute/dayEndMinute 配置）。 */
    private static boolean isInDay(LocalDateTime time, int dayBeginMin, int dayEndMin) {
        // 时间的分钟数
        int minute = time.getHour() * 60 + time.getMinute();
        // 开始时间早于结束时间，时间应在开始结束中间才是白天
        if (dayBeginMin < dayEndMin) {
            return minute >= dayBeginMin && minute < dayEndMin;
        }
        // 开始时间晚于结束时间，说明是到次日结束时间为白天，时间应晚于开始或早于开始
        return minute >= dayBeginMin || minute < dayEndMin;
    }

    /**
     * 判断某个时间点是否是 dayBegin 边界（night 到 day 的切换点）。
     */
    private static boolean isDayBeginBoundaryPoint(LocalDateTime time, int dayBeginMin) {
        int minute = time.getHour() * 60 + time.getMinute();
        return minute == dayBeginMin;
    }
}
