package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
        String periodLabel = null;

        for (HomogeneousSegment seg : segments) {
            if (seg.isBubble()) {
                // bubble 段不切分、不占用周期时长、不触发周期切换，charged=0
                result.add(new DurationSegment(
                        seg.getBeginTime(), seg.getEndTime(), periodLabel,
                        0, seg.getUnitPrice(), BigDecimal.ZERO, periodCap,
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
                    periodLabel = semantics.periodLabel(segBegin, config, cycleOrigin);
                }

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
                        segBegin, frontEnd, periodLabel,
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
     * GLOBAL 模式：全局按时长计费。
     * <p>
     * - FREE_MINUTES 时段化（TODO-20260706-001）：与 PERIOD 同路径，免费段独立，DurationSegment 同质<br>
     * - 时段封顶：同 period 类型全局累计达 period.maxCharge × 周期数，该 period 后续段削减（落盘）<br>
     * - 周期封顶：所有段 chargedAmount 之和达 maxChargeOneCycle × 周期数，实收 = min(cap×周期数, 总额)（不落盘到段）<br>
     * - 周期数 = ceil(总分钟数 / 周期分钟数)
     */
    public static <C extends RuleConfig> DurationResult buildGlobalMode(
            List<HomogeneousSegment> segments,
            long totalMinutes,
            BigDecimal cycleCap,
            RuleSemantics<C> semantics,
            C config,
            LocalDateTime cycleOrigin) {

        List<DurationSegment> result = new ArrayList<>();
        if (segments.isEmpty()) {
            return new DurationResult(result, cycleCap, BigDecimal.ZERO);
        }

        int cycleMinutes = semantics.cycleMinutes();
        // bubble 免费段不占用周期时长：周期数按有效时长（总时长 - bubble 时长）算
        int bubbleMinutes = sumBubbleDuration(segments);
        int effectiveMinutes = Math.max((int) (totalMinutes - bubbleMinutes), 0);
        int cycleCount = (int) Math.ceil((double) effectiveMinutes / cycleMinutes);
        if (cycleCount < 1) cycleCount = 1;

        int n = segments.size();
        BigDecimal[] rawCharges = new BigDecimal[n];
        int[] chargedMinutesArr = new int[n];   // 每段收费分钟（免费段=0，收费段=段时长）
        Map<String, BigDecimal> periodCapMap = new HashMap<>();     // 各 period 封顶
        Map<String, String> periodLabelMap = new HashMap<>();       // 各 period 标签

        // 第一遍：计算每段原始应收 + chargedMinutes，并识别 period 封顶/标签
        for (int i = 0; i < n; i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = segmentCharge(seg, semantics, config, cycleOrigin);
            rawCharges[i] = charged;
            chargedMinutesArr[i] = seg.isFree() ? 0 : seg.durationMinutes();
            if (!seg.isFree()) {
                String key = semantics.periodKey(seg.getBeginTime(), config, cycleOrigin);
                periodCapMap.putIfAbsent(key, semantics.periodCap(seg.getBeginTime(), config, cycleOrigin));
                periodLabelMap.putIfAbsent(key, semantics.periodLabel(seg.getBeginTime(), config, cycleOrigin));
            }
        }

        // 第二遍：应用时段封顶（period.maxCharge × 周期数），按时间顺序累计削减
        Map<String, BigDecimal> periodApplied = new HashMap<>();
        for (int i = 0; i < n; i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = rawCharges[i];

            if (!seg.isFree()) {
                String key = semantics.periodKey(seg.getBeginTime(), config, cycleOrigin);
                BigDecimal cap = periodCapMap.get(key);
                if (cap != null) {
                    BigDecimal capMultiplied = cap.multiply(BigDecimal.valueOf(cycleCount));
                    BigDecimal before = periodApplied.getOrDefault(key, BigDecimal.ZERO);
                    BigDecimal after = before.add(charged);
                    if (after.compareTo(capMultiplied) > 0) {
                        charged = capMultiplied.subtract(before);
                        if (charged.signum() < 0) charged = BigDecimal.ZERO;
                        periodApplied.put(key, capMultiplied);
                    } else {
                        periodApplied.put(key, after);
                    }
                }
            }
            rawCharges[i] = charged;
        }

        // 第三遍：构建 DurationSegment，求总额
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            HomogeneousSegment seg = segments.get(i);
            BigDecimal charged = rawCharges[i];
            totalAmount = totalAmount.add(charged);

            String key = semantics.periodKey(seg.getBeginTime(), config, cycleOrigin);
            String label = periodLabelMap.get(key);
            BigDecimal pCap = periodCapMap.get(key);

            result.add(new DurationSegment(
                    seg.getBeginTime(),
                    seg.getEndTime(),
                    label,
                    chargedMinutesArr[i],
                    seg.getUnitPrice(),
                    charged,
                    pCap,
                    seg.isFree() ? seg.getFreePromotionId() : null,
                    segmentOriginalCharge(seg, semantics, config, cycleOrigin)
            ));
        }

        // 周期封顶（不落盘到段）
        BigDecimal cycleCapApplied = cycleCap != null
                ? cycleCap.multiply(BigDecimal.valueOf(cycleCount)) : null;
        BigDecimal finalAmount = (cycleCapApplied != null && totalAmount.compareTo(cycleCapApplied) > 0)
                ? cycleCapApplied : totalAmount;

        return new DurationResult(result, cycleCapApplied, finalAmount);
    }
}
