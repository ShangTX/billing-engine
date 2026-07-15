package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时长计费通用产出工具（层 2）：从同质段构建 {@link DurationSegment}，应用时段封顶 + 周期封顶。
 * <p>
 * PERIOD / GLOBAL 两模式的公共骨架（{@link #buildPeriodMode} / {@link #buildGlobalMode}），
 * 规则族差异（单价、period 标签、period 封顶、周期长度）通过 {@link RuleSemantics} 注入：
 * <ul>
 *   <li>{@link RuleSemantics#priceAt} 替代各规则族私有 priceResolver</li>
 *   <li>{@link RuleSemantics#periodLabel} 替代 day/night 等人类可读标签</li>
 *   <li>{@link RuleSemantics#periodCap} 替代各规则族 period 级封顶（仅 CompositeTime 非 null）</li>
 *   <li>{@link RuleSemantics#periodKey} 用于 period 累计跟踪与切换重置</li>
 *   <li>{@link RuleSemantics#cycleMinutes} 替代硬编码 1440</li>
 * </ul>
 * <p>
 * TODO-20260706-002 阶段4：从 DayNightDurationStrategy 搬通用逻辑，删 PeriodResolver 接口。
 */
public final class DurationSupport {

    private DurationSupport() {
    }

    /**
     * 时长模式产出结果：DurationSegment 列表 + 周期封顶金额 + 周期封顶后实收。
     */
    public static final class DurationResult {
        public final List<DurationSegment> segments;
        /** 周期封顶金额（null=无封顶或未配置），DurationSegment 不落盘周期封顶。 */
        public final BigDecimal cycleCapApplied;
        /** 周期封顶后的实收（= 各段 chargedAmount 之和与周期封顶取 min）。 */
        public final BigDecimal chargedAmount;

        public DurationResult(List<DurationSegment> segments, BigDecimal cycleCapApplied, BigDecimal chargedAmount) {
            this.segments = segments;
            this.cycleCapApplied = cycleCapApplied;
            this.chargedAmount = chargedAmount;
        }
    }

    /**
     * 计算单个同质段的应收金额（时段封顶前）。免费段返回 0。
     * unitMinutes 按 seg 起点经 semantics 查询（全局统一或按 period）。
     * <p>
     * 按 {@link RuleSemantics#incompleteMode} 处理不足一个 unitMinutes 的余数部分：
     * 默认 {@code FULL_CHARGE}（余数收一个全额，即"不满一小时按一小时算"），
     * {@code PROPORTIONAL} 按比例（与原按比例逻辑一致）。
     */
    public static <C extends RuleConfig> BigDecimal segmentCharge(
            HomogeneousSegment seg, RuleSemantics<C> semantics, C config, LocalDateTime cycleOrigin) {
        if (seg.isFree()) return BigDecimal.ZERO;
        int unitMinutes = semantics.unitMinutes(seg.getBeginTime(), config, cycleOrigin);
        BigDecimal price = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;
        if (unitMinutes <= 0) return BigDecimal.ZERO;
        return chargeByMode(price, seg.durationMinutes(), unitMinutes, semantics, config);
    }

    /**
     * 计算单个同质段的按规则原价（封顶前，免费段也用规则单价算）。
     * 用于 {@link DurationSegment#originalAmount()}（等效优惠金额聚合）。
     * <p>
     * 与 {@link #segmentCharge} 用相同的 {@code incompleteUnitChargeMode} 逻辑，保持一致。
     */
    public static <C extends RuleConfig> BigDecimal segmentOriginalCharge(
            HomogeneousSegment seg, RuleSemantics<C> semantics, C config, LocalDateTime cycleOrigin) {
        int unitMinutes = semantics.unitMinutes(seg.getBeginTime(), config, cycleOrigin);
        if (unitMinutes <= 0) return BigDecimal.ZERO;
        BigDecimal price = semantics.priceAt(seg.getBeginTime(), seg.getEndTime(), config, cycleOrigin);
        return chargeByMode(price, seg.durationMinutes(), unitMinutes, semantics, config);
    }

    /**
     * 按 {@link BConstants.IncompleteUnitChargeMode} 计算同质段应收金额（封顶前）。
     * <p>
     * 时长模式同质段时长可为任意值（不一定对齐 unitMinutes），拆分为整除部分 + 余数部分：
     * <ul>
     *   <li>整除部分：{@code unitPrice × fullUnits}（fullUnits = segMinutes / unitMinutes）</li>
     *   <li>余数部分（remainder &gt; 0 时）：按 {@code incompleteUnitChargeMode} 处理
     *     <ul>
     *       <li>{@code PROPORTIONAL}：{@code unitPrice × remainder / unitMinutes}（按比例）</li>
     *       <li>{@code FULL_CHARGE}：{@code unitPrice}（余数收一个全额，即"不满一小时按一小时算"）</li>
     *       <li>{@code FREE}：{@code 0}（余数免费，整除部分仍收）</li>
     *       <li>{@code THRESHOLD_MINUTES}/{@code THRESHOLD_RATIO}：余数按阈值判定</li>
     *     </ul>
     *   </li>
     * </ul>
     * 余数部分复用 {@link ContinuousStrategy#computeIncompleteCharge}，语义与 CONTINUOUS 截断单元一致。
     * remainder == 0 时直接返回整除部分，不调用 computeIncompleteCharge（避免 FULL_CHARGE 多收一个全额）。
     */
    private static <C extends RuleConfig> BigDecimal chargeByMode(BigDecimal unitPrice, int segMinutes, int unitMinutes,
                                                                   RuleSemantics<C> semantics, C config) {
        int fullUnits = segMinutes / unitMinutes;
        int remainder = segMinutes % unitMinutes;
        BigDecimal baseCharge = unitPrice.multiply(BigDecimal.valueOf(fullUnits));
        if (remainder == 0) {
            return baseCharge.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal remainderCharge = ContinuousStrategy.computeIncompleteCharge(
                unitPrice, remainder, unitMinutes,
                semantics.incompleteMode(config),
                semantics.thresholdMinutes(config),
                semantics.thresholdRatio(config));
        return baseCharge.add(remainderCharge).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 应用周期封顶：返回 min(cycleCap, accumulated)。cycleCap 为 null 时直接返回 accumulated。
     */
    public static BigDecimal applyCycleCap(BigDecimal cycleCap, BigDecimal accumulated) {
        if (cycleCap == null || accumulated.compareTo(cycleCap) <= 0) {
            return accumulated;
        }
        return cycleCap;
    }

    /**
     * 统计 BUBBLE 免费段的总时长（分钟）。bubble 段不占用周期时长，用于 GLOBAL 模式周期数计算。
     */
    public static int sumBubbleDuration(List<HomogeneousSegment> segments) {
        if (segments == null || segments.isEmpty()) return 0;
        int sum = 0;
        for (HomogeneousSegment seg : segments) {
            if (seg.isBubble()) {
                sum += seg.durationMinutes();
            }
        }
        return sum;
    }

    /**
     * PERIOD 模式：周期内按时长计费。
     * <p>
     * - 时段封顶：周期内同 period 累计达 period.maxCharge，该 period 后续段 chargedAmount 削减（落盘）<br>
     * - 周期封顶：每周期内所有段 chargedAmount 之和达 maxChargeOneCycle，该周期实收 = min(cap, 之和)（不落盘到段）<br>
     * - 跨周期：period 累计每周期重置
     */
    public static <C extends RuleConfig> DurationResult buildPeriodMode(
            List<HomogeneousSegment> segments,
            BigDecimal cycleCap,
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin) {

        List<DurationSegment> result = new ArrayList<>();
        if (segments.isEmpty()) {
            return new DurationResult(result, cycleCap, BigDecimal.ZERO);
        }

        int cycleMinutes = semantics.cycleMinutes();
        BigDecimal totalCharged = BigDecimal.ZERO;

        // 周期跟踪：按累计有效时长（非 bubble 段时长）切周期。bubble 段不占用周期时长。
        long effectiveAccumInCycle = 0;
        BigDecimal cycleAccumulated = BigDecimal.ZERO;

        // period 跟踪（周期内）
        String currentPeriodKey = null;
        BigDecimal periodAccumulated = BigDecimal.ZERO;
        BigDecimal periodCap = null;

        for (HomogeneousSegment seg : segments) {
            if (seg.isBubble()) {
                // bubble 段不切分、不占用周期时长、不触发周期切换，charged=0
                String bubblePeriodLabel = semantics.periodLabel(seg.getBeginTime(), config, cycleOrigin);
                BigDecimal bubblePeriodCap = semantics.periodCap(seg.getBeginTime(), config, cycleOrigin);
                result.add(new DurationSegment(
                        seg.getBeginTime(), seg.getEndTime(), bubblePeriodLabel,
                        0, seg.getUnitPrice(), BigDecimal.ZERO, bubblePeriodCap,
                        seg.getFreePromotionId(), BigDecimal.ZERO));
                continue;
            }

            // 非 bubble 段：按周期容量切分（段可能跨周期边界，切分后分别算 charged）
            LocalDateTime segBegin = seg.getBeginTime();
            LocalDateTime segEnd = seg.getEndTime();
            int remainingMinutes = seg.durationMinutes();
            boolean isFree = seg.isFree();

            while (remainingMinutes > 0) {
                long capacity = cycleMinutes - effectiveAccumInCycle;
                int frontMinutes;
                boolean splitsCycle;
                if (remainingMinutes <= capacity) {
                    frontMinutes = remainingMinutes;
                    splitsCycle = false;
                } else {
                    frontMinutes = (int) capacity;
                    splitsCycle = true;
                }
                LocalDateTime frontEnd = splitsCycle ? segBegin.plusMinutes(frontMinutes) : segEnd;

                // 构造前段子段，重新算 charged（同质段单价一致，切分后准确）
                HomogeneousSegment frontSeg = new HomogeneousSegment(segBegin, frontEnd,
                        seg.getUnitPrice(), seg.getOriginalAmount(), isFree,
                        isFree ? seg.getFreePromotionId() : null, null);
                BigDecimal charged = isFree ? BigDecimal.ZERO
                        : segmentCharge(frontSeg, semantics, config, cycleOrigin);
                BigDecimal original = segmentOriginalCharge(frontSeg, semantics, config, cycleOrigin);

                // period 识别（周期内切换）
                String newPeriodKey = semantics.periodKey(segBegin, config, cycleOrigin);
                if (!newPeriodKey.equals(currentPeriodKey)) {
                    currentPeriodKey = newPeriodKey;
                    periodAccumulated = BigDecimal.ZERO;
                    periodCap = semantics.periodCap(segBegin, config, cycleOrigin);
                }
                String segmentPeriodLabel = semantics.periodLabel(segBegin, config, cycleOrigin);

                // 时段封顶（周期内累计，落盘到 chargedAmount）
                if (periodCap != null && !isFree) {
                    BigDecimal newAccum = periodAccumulated.add(charged);
                    if (newAccum.compareTo(periodCap) > 0) {
                        charged = periodCap.subtract(periodAccumulated);
                        if (charged.signum() < 0) charged = BigDecimal.ZERO;
                        periodAccumulated = periodCap;
                    } else {
                        periodAccumulated = newAccum;
                    }
                }

                cycleAccumulated = cycleAccumulated.add(charged);
                effectiveAccumInCycle += frontMinutes;

                result.add(new DurationSegment(
                        segBegin, frontEnd, segmentPeriodLabel,
                        isFree ? 0 : frontMinutes,
                        seg.getUnitPrice(), charged, periodCap,
                        isFree ? seg.getFreePromotionId() : null, original));

                if (splitsCycle) {
                    // 周期切换：结算当前周期，重置累计
                    totalCharged = totalCharged.add(applyCycleCap(cycleCap, cycleAccumulated));
                    cycleAccumulated = BigDecimal.ZERO;
                    effectiveAccumInCycle = 0;
                    currentPeriodKey = null;
                    periodAccumulated = BigDecimal.ZERO;
                    periodCap = null;
                    segBegin = frontEnd;
                    remainingMinutes -= frontMinutes;
                } else {
                    remainingMinutes = 0;
                }
            }
        }

        // 结算最后一个周期
        totalCharged = totalCharged.add(applyCycleCap(cycleCap, cycleAccumulated));

        return new DurationResult(result, cycleCap, totalCharged);
    }

    /**
     * GLOBAL 模式：全局按时长计费，输出聚合后的收费桶。
     * <p>
     * - FREE_MINUTES 时段化（TODO-20260706-001）：免费段参与边界驱动，最终只输出收费汇总桶<br>
     * - 同质收费桶：按 periodKey + periodLabel + unitPrice + unitMinutes 聚合收费分钟，begin/end 置空<br>
     * - 时段封顶：完整周期部分按 period.maxCharge × fullCycles，尾周期按实际尾周期费用与 period.maxCharge 取小<br>
     * - 周期封顶：完整周期部分按 maxChargeOneCycle × fullCycles，尾周期按实际尾周期费用与 maxChargeOneCycle 取小<br>
     * - 周期划分基于 effective minutes；BUBBLE 免费段不占用周期时长
     */
    public static <C extends RuleConfig> DurationResult buildGlobalMode(
            List<HomogeneousSegment> segments,
            long totalMinutes,
            BigDecimal cycleCap,
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin) {

        if (segments.isEmpty()) {
            return new DurationResult(new ArrayList<>(), cycleCap, BigDecimal.ZERO);
        }

        int cycleMinutes = semantics.cycleMinutes();
        // bubble 免费段不占用周期时长：full/tail 周期按有效时长（总时长 - bubble 时长）算
        int bubbleMinutes = sumBubbleDuration(segments);
        int effectiveMinutes = Math.max((int) (totalMinutes - bubbleMinutes), 0);
        int fullCycles = cycleMinutes > 0 ? effectiveMinutes / cycleMinutes : 0;
        int tailMinutes = cycleMinutes > 0 ? effectiveMinutes % cycleMinutes : 0;
        int fullEndOffset = fullCycles * cycleMinutes;

        LinkedHashMap<GlobalBucketKey, GlobalBucketAccumulator> buckets = new LinkedHashMap<>();
        GlobalPartResult fullPart = buildGlobalPart(segments, 0, fullEndOffset, fullCycles,
                semantics, config, cycleOrigin, buckets);
        GlobalPartResult tailPart = buildGlobalPart(segments, fullEndOffset, effectiveMinutes,
                tailMinutes > 0 ? 1 : 0, semantics, config, cycleOrigin, buckets);

        BigDecimal totalBeforeCycleCap = fullPart.chargedAmount.add(tailPart.chargedAmount);
        BigDecimal finalAmount = totalBeforeCycleCap;
        BigDecimal cycleCapApplied = null;
        if (cycleCap != null && cycleCap.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fullLimit = cycleCap.multiply(BigDecimal.valueOf(fullCycles));
            BigDecimal fullCharged = fullPart.chargedAmount.min(fullLimit);
            BigDecimal tailCharged = tailMinutes > 0 ? tailPart.chargedAmount.min(cycleCap) : BigDecimal.ZERO;
            finalAmount = fullCharged.add(tailCharged).setScale(2, RoundingMode.HALF_UP);
            cycleCapApplied = fullLimit.add(tailCharged).setScale(2, RoundingMode.HALF_UP);
        }

        List<DurationSegment> result = new ArrayList<>();
        for (GlobalBucketAccumulator bucket : buckets.values()) {
            if (bucket.chargedMinutes <= 0) {
                continue;
            }
            result.add(new DurationSegment(
                    null,
                    null,
                    bucket.periodLabel,
                    bucket.chargedMinutes,
                    bucket.unitPrice,
                    bucket.chargedAmount.setScale(2, RoundingMode.HALF_UP),
                    bucket.periodCap,
                    null,
                    bucket.originalAmount.setScale(2, RoundingMode.HALF_UP)
            ));
        }

        return new DurationResult(result, cycleCapApplied, finalAmount.setScale(2, RoundingMode.HALF_UP));
    }

    private static <C extends RuleConfig> GlobalPartResult buildGlobalPart(
            List<HomogeneousSegment> segments,
            int effectiveBegin,
            int effectiveEnd,
            int capMultiplier,
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin,
            LinkedHashMap<GlobalBucketKey, GlobalBucketAccumulator> outputBuckets) {

        if (effectiveEnd <= effectiveBegin) {
            return new GlobalPartResult(BigDecimal.ZERO);
        }

        LinkedHashMap<String, GlobalPeriodAccumulator> periods = new LinkedHashMap<>();
        int effectiveCursor = 0;
        for (HomogeneousSegment seg : segments) {
            if (seg.isBubble()) {
                continue;
            }

            int segEffectiveBegin = effectiveCursor;
            int segEffectiveEnd = effectiveCursor + seg.durationMinutes();
            int overlapBegin = Math.max(segEffectiveBegin, effectiveBegin);
            int overlapEnd = Math.min(segEffectiveEnd, effectiveEnd);
            int overlapMinutes = Math.max(overlapEnd - overlapBegin, 0);

            if (overlapMinutes > 0 && !seg.isFree()) {
                LocalDateTime clippedBegin = seg.getBeginTime().plusMinutes(overlapBegin - segEffectiveBegin);
                BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;
                int unitMinutes = semantics.unitMinutes(clippedBegin, config, cycleOrigin);
                String periodKey = semantics.periodKey(clippedBegin, config, cycleOrigin);
                String periodLabel = semantics.periodLabel(clippedBegin, config, cycleOrigin);
                BigDecimal periodCap = semantics.periodCap(clippedBegin, config, cycleOrigin);

                GlobalPeriodAccumulator period = periods.computeIfAbsent(periodKey,
                        ignored -> new GlobalPeriodAccumulator(periodLabel, periodCap));
                GlobalBucketKey bucketKey = new GlobalBucketKey(periodKey, periodLabel, unitPrice, unitMinutes);
                GlobalRawBucket rawBucket = period.buckets.computeIfAbsent(bucketKey,
                        ignored -> new GlobalRawBucket(periodKey, periodLabel, unitPrice, unitMinutes, periodCap));
                rawBucket.minutes += overlapMinutes;
            }

            effectiveCursor = segEffectiveEnd;
        }

        BigDecimal partCharged = BigDecimal.ZERO;
        for (GlobalPeriodAccumulator period : periods.values()) {
            BigDecimal periodBudget = null;
            if (period.periodCap != null && period.periodCap.compareTo(BigDecimal.ZERO) > 0) {
                periodBudget = period.periodCap.multiply(BigDecimal.valueOf(capMultiplier));
            }

            for (GlobalRawBucket rawBucket : period.buckets.values()) {
                BigDecimal rawAmount = chargeByMode(rawBucket.unitPrice, rawBucket.minutes,
                        rawBucket.unitMinutes, semantics, config);
                BigDecimal chargedAmount = rawAmount;
                if (periodBudget != null) {
                    chargedAmount = rawAmount.min(periodBudget.max(BigDecimal.ZERO));
                    periodBudget = periodBudget.subtract(chargedAmount);
                }

                GlobalBucketAccumulator out = outputBuckets.computeIfAbsent(rawBucket.key(),
                        ignored -> new GlobalBucketAccumulator(rawBucket.periodLabel,
                                rawBucket.unitPrice, rawBucket.unitMinutes, rawBucket.periodCap));
                out.chargedMinutes += rawBucket.minutes;
                out.chargedAmount = out.chargedAmount.add(chargedAmount);
                out.originalAmount = out.originalAmount.add(rawAmount);
                partCharged = partCharged.add(chargedAmount);
            }
        }

        return new GlobalPartResult(partCharged.setScale(2, RoundingMode.HALF_UP));
    }

    private record GlobalBucketKey(String periodKey, String periodLabel, BigDecimal unitPrice, int unitMinutes) {
        GlobalBucketKey {
            unitPrice = unitPrice != null ? unitPrice.stripTrailingZeros() : BigDecimal.ZERO;
        }
    }

    private static final class GlobalPartResult {
        final BigDecimal chargedAmount;

        GlobalPartResult(BigDecimal chargedAmount) {
            this.chargedAmount = chargedAmount;
        }
    }

    private static final class GlobalPeriodAccumulator {
        final String periodLabel;
        final BigDecimal periodCap;
        final LinkedHashMap<GlobalBucketKey, GlobalRawBucket> buckets = new LinkedHashMap<>();

        GlobalPeriodAccumulator(String periodLabel, BigDecimal periodCap) {
            this.periodLabel = periodLabel;
            this.periodCap = periodCap;
        }
    }

    private static final class GlobalRawBucket {
        final String periodKey;
        final String periodLabel;
        final BigDecimal unitPrice;
        final int unitMinutes;
        final BigDecimal periodCap;
        int minutes;

        GlobalRawBucket(String periodKey, String periodLabel, BigDecimal unitPrice, int unitMinutes, BigDecimal periodCap) {
            this.periodKey = periodKey;
            this.periodLabel = periodLabel;
            this.unitPrice = unitPrice;
            this.unitMinutes = unitMinutes;
            this.periodCap = periodCap;
        }

        GlobalBucketKey key() {
            return new GlobalBucketKey(periodKey, periodLabel, unitPrice, unitMinutes);
        }
    }

    private static final class GlobalBucketAccumulator {
        final String periodLabel;
        final BigDecimal unitPrice;
        final int unitMinutes;
        final BigDecimal periodCap;
        int chargedMinutes;
        BigDecimal chargedAmount = BigDecimal.ZERO;
        BigDecimal originalAmount = BigDecimal.ZERO;

        GlobalBucketAccumulator(String periodLabel, BigDecimal unitPrice,
                                int unitMinutes, BigDecimal periodCap) {
            this.periodLabel = periodLabel;
            this.unitPrice = unitPrice;
            this.unitMinutes = unitMinutes;
            this.periodCap = periodCap;
        }
    }

    // ==================== 简化路径（PERIOD 模式） ====================

    /**
     * 构建简化时长段：N 个完整 effective 周期合并为一个 DurationSegment。
     * <p>
     * 简化段跨多个 period，periodLabel/periodCap 不适用（已按 cycleCap 封顶）。
     * chargedAmount = cycleCap × cycleCount（每周期封顶），originalAmount 同（简化段无优惠）。
     */
    public static DurationSegment buildSimplifiedDurationSegment(LocalDateTime begin, LocalDateTime end,
                                                                 int cycleCount, int cycleMinutes,
                                                                 BigDecimal cycleCap) {
        BigDecimal totalAmount = cycleCap.multiply(BigDecimal.valueOf(cycleCount));
        return new DurationSegment(
                begin, end,
                "SIMPLIFIED",
                cycleCount * cycleMinutes,
                cycleCap,
                totalAmount,
                null,
                null,
                totalAmount);
    }

    /**
     * PERIOD 模式简化路径：长周期无优惠区间合并为简化段，短区间/优惠段走 {@link #buildPeriodMode} 详细。
     * <p>
     * 核心算法（computeGaps + findSimplifiedBlock）由 {@link SimplificationSupport} 承载，与 CONTINUOUS 共用一份。
     * bubble 段通过 effective offset 处理（周期边界后移）：gap 内无 bubble，effective offset 线性；
     * gap 之前的 bubble 时长从 effective offset 中扣除。
     * <p>
     * <b>子区间独立计算</b>：优惠段/头尾片段各自调用 buildPeriodMode（effectiveAccumInCycle 从 0 起算）。
     * 由于简化段对齐 effective 周期边界，头尾片段落在简化段的相邻不完整周期内（不跨周期边界），
     * 优惠段 charged=0，故独立计算的周期划分差异不影响 chargedAmount。
     *
     * @param segments        边界驱动产出的同质段（含 bubble/free 标记）
     * @param cycleCap        周期封顶金额
     * @param semantics       规则族语义
     * @param config          规则配置
     * @param cycleOrigin     周期起点
     * @param freeTimeRanges  免费段列表（含 BUBBLE 段，用于算 gaps + effective offset）
     * @param threshold       简化阈值（完整周期数 &gt; 阈值才简化）
     * @return DurationResult（segments 为简化段 + 详细段混合）
     */
    public static <C extends RuleConfig> DurationResult buildPeriodModeSimplified(
            List<HomogeneousSegment> segments,
            BigDecimal cycleCap,
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin,
            List<FreeTimeRange> freeTimeRanges,
            int threshold) {

        List<DurationSegment> result = new ArrayList<>();
        if (segments.isEmpty()) {
            return new DurationResult(result, cycleCap, BigDecimal.ZERO);
        }

        LocalDateTime calcBegin = segments.get(0).getBeginTime();
        LocalDateTime calcEnd = segments.get(segments.size() - 1).getEndTime();
        int cycleMinutes = semantics.cycleMinutes();

        // 分离 bubble 段（用于 effective offset 计算）
        List<FreeTimeRange> bubbleRanges = new ArrayList<>();
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getRangeType() == FreeTimeRangeType.BUBBLE) {
                bubbleRanges.add(range);
            }
        }

        // 算无优惠空隙（bubble 段是优惠，不在 gap 内）
        List<SimplificationSupport.Range> gaps = SimplificationSupport.computeGaps(calcBegin, calcEnd, freeTimeRanges);

        BigDecimal totalCharged = BigDecimal.ZERO;
        LocalDateTime promoCursor = calcBegin;

        for (SimplificationSupport.Range gap : gaps) {
            // gap 之前的优惠段（含 bubble）走详细
            if (gap.begin.isAfter(promoCursor)) {
                DurationResult detail = buildPeriodModeForRange(segments, promoCursor, gap.begin,
                        cycleCap, semantics, config, cycleOrigin);
                result.addAll(detail.segments);
                totalCharged = totalCharged.add(detail.chargedAmount);
            }

            // gap 内简化判断（effective offset，含 bubble 后移）
            SimplificationSupport.SimplifiedBlock block = SimplificationSupport.findSimplifiedBlock(
                    gap, calcBegin, bubbleRanges, cycleMinutes, threshold);

            if (block != null) {
                // 头部片段（gap.begin ~ 简化块起点）走详细
                if (block.begin.isAfter(gap.begin)) {
                    DurationResult head = buildPeriodModeForRange(segments, gap.begin, block.begin,
                            cycleCap, semantics, config, cycleOrigin);
                    result.addAll(head.segments);
                    totalCharged = totalCharged.add(head.chargedAmount);
                }
                // 简化段（N 个完整 effective 周期）
                result.add(buildSimplifiedDurationSegment(block.begin, block.end, block.cycleCount, cycleMinutes, cycleCap));
                totalCharged = totalCharged.add(cycleCap.multiply(BigDecimal.valueOf(block.cycleCount)));
                // 尾部片段（简化块终点 ~ gap.end）走详细
                if (block.end.isBefore(gap.end)) {
                    DurationResult tail = buildPeriodModeForRange(segments, block.end, gap.end,
                            cycleCap, semantics, config, cycleOrigin);
                    result.addAll(tail.segments);
                    totalCharged = totalCharged.add(tail.chargedAmount);
                }
            } else {
                // 完整周期数不足阈值，整个 gap 走详细
                DurationResult detail = buildPeriodModeForRange(segments, gap.begin, gap.end,
                        cycleCap, semantics, config, cycleOrigin);
                result.addAll(detail.segments);
                totalCharged = totalCharged.add(detail.chargedAmount);
            }

            promoCursor = gap.end;
        }
        // 末尾优惠段（最后一个 gap 之后到 calcEnd）走详细
        if (promoCursor.isBefore(calcEnd)) {
            DurationResult detail = buildPeriodModeForRange(segments, promoCursor, calcEnd,
                    cycleCap, semantics, config, cycleOrigin);
            result.addAll(detail.segments);
            totalCharged = totalCharged.add(detail.chargedAmount);
        }

        return new DurationResult(result, cycleCap, totalCharged);
    }

    /**
     * 对 [begin, end] 子区间跑详细 buildPeriodMode：先从 segments 裁剪子区间段，再调用 {@link #buildPeriodMode}。
     */
    private static <C extends RuleConfig> DurationResult buildPeriodModeForRange(
            List<HomogeneousSegment> segments, LocalDateTime begin, LocalDateTime end,
            BigDecimal cycleCap, RuleSemantics<C> semantics, C config, LocalDateTime cycleOrigin) {
        List<HomogeneousSegment> clipped = clipSegments(segments, begin, end);
        return buildPeriodMode(clipped, cycleCap, semantics, config, cycleOrigin);
    }

    /**
     * 从同质段列表裁剪 [begin, end] 子区间：跨边界的段被切分，rangeType/free 标记保留。
     */
    private static List<HomogeneousSegment> clipSegments(List<HomogeneousSegment> segments,
                                                        LocalDateTime begin, LocalDateTime end) {
        List<HomogeneousSegment> clipped = new ArrayList<>();
        for (HomogeneousSegment seg : segments) {
            if (!seg.getEndTime().isAfter(begin) || !seg.getBeginTime().isBefore(end)) continue;
            LocalDateTime segBegin = seg.getBeginTime().isBefore(begin) ? begin : seg.getBeginTime();
            LocalDateTime segEnd = seg.getEndTime().isAfter(end) ? end : seg.getEndTime();
            clipped.add(new HomogeneousSegment(segBegin, segEnd, seg.getUnitPrice(), seg.getOriginalAmount(),
                    seg.isFree(), seg.getFreePromotionId(), seg.getRangeType(), null));
        }
        return clipped;
    }
}
