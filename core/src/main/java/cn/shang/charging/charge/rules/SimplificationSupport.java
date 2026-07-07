package cn.shang.charging.charge.rules;

import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 简化计算通用骨架（层 2）：全局空隙 + effective 周期对齐 + 阈值判断。
 * <p>
 * CONTINUOUS（DayNight/CompositeTime）与 PERIOD 时长模式共用，消除两份重复的
 * {@code generateUnitsByGlobalGaps}。核心算法一份：算无优惠空隙 → 对齐 effective 周期边界
 * → 完整周期数超阈值返回简化区间。产出类型（BillingUnit / DurationSegment）与详细路径由调用方各自处理。
 * <p>
 * <b>关键洞察</b>：简化只发生在 gap（无优惠空隙）内的完整周期块，bubble 段是优惠段不在 gap 内，
 * 故 gap 内 effective offset = 自然 offset（线性增长），只需算 gap 起始的 effective offset
 * （减去之前的 bubble 时长），无需递推算所有 effective 周期边界。
 * <ul>
 *   <li>CONTINUOUS（无 bubble，已由 {@link ContinuousStrategy#assertNoBubbleSupported} 校验）：
 *       bubbleDurationBefore=0，effectiveOffset=自然 offset，退化为原有对齐，行为不变。</li>
 *   <li>PERIOD（有 bubble）：bubbleDurationBefore&gt;0，effective 边界后移，支持 bubble。</li>
 * </ul>
 */
public final class SimplificationSupport {

    private SimplificationSupport() {
    }

    /** 时间区间（用于全局空隙计算）。 */
    public static final class Range {
        public final LocalDateTime begin;
        public final LocalDateTime end;

        public Range(LocalDateTime begin, LocalDateTime end) {
            this.begin = begin;
            this.end = end;
        }
    }

    /**
     * 简化区间：gap 内完整周期块的时间范围 + 周期数 + 起止周期索引。
     * <ul>
     *   <li>{@code begin/end}：简化段起止时间（effective 周期边界，已含 bubble 后移）</li>
     *   <li>{@code startK/endK}：起止周期索引（相对 origin，CONTINUOUS 用于 buildSimplifiedUnit）</li>
     *   <li>{@code cycleCount}：完整周期数（= endK - startK）</li>
     * </ul>
     */
    public static final class SimplifiedBlock {
        public final LocalDateTime begin;
        public final LocalDateTime end;
        public final int cycleCount;
        public final int startK;
        public final int endK;

        public SimplifiedBlock(LocalDateTime begin, LocalDateTime end, int cycleCount, int startK, int endK) {
            this.begin = begin;
            this.end = end;
            this.cycleCount = cycleCount;
            this.startK = startK;
            this.endK = endK;
        }
    }

    /**
     * 算无优惠空隙（gap = 优惠段之间的间隙 + 头尾）。
     * <p>
     * bubble 段属于优惠段，不在 gap 内（gap 是无优惠的连续区间）。
     * 入参 {@code freeTimeRanges} 不要求排序，本方法防御性排序。
     */
    public static List<Range> computeGaps(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                          List<FreeTimeRange> freeTimeRanges) {
        List<FreeTimeRange> sortedRanges = new ArrayList<>();
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getEndTime().isAfter(calcBegin) && range.getBeginTime().isBefore(calcEnd)) {
                sortedRanges.add(range);
            }
        }
        sortedRanges.sort(Comparator.comparing(FreeTimeRange::getBeginTime));

        List<Range> gaps = new ArrayList<>();
        LocalDateTime cursor = calcBegin;
        for (FreeTimeRange range : sortedRanges) {
            LocalDateTime rangeBegin = range.getBeginTime().isBefore(calcBegin) ? calcBegin : range.getBeginTime();
            LocalDateTime rangeEnd = range.getEndTime().isAfter(calcEnd) ? calcEnd : range.getEndTime();
            if (rangeBegin.isAfter(cursor)) {
                gaps.add(new Range(cursor, rangeBegin));
            }
            if (rangeEnd.isAfter(cursor)) {
                cursor = rangeEnd;
            }
        }
        if (cursor.isBefore(calcEnd)) {
            gaps.add(new Range(cursor, calcEnd));
        }
        return gaps;
    }

    /**
     * 统计 {@code [origin, time]} 内的 bubble 段总时长（分钟）。
     * <p>
     * bubble 段不占用周期时长，用于把自然 offset 修正为 effective offset。
     * 仅统计 rangeType=BUBBLE 的段，按与 {@code [origin, time]} 的交集裁剪。
     */
    public static long bubbleDurationBefore(LocalDateTime time, LocalDateTime origin,
                                            List<FreeTimeRange> bubbleRanges) {
        if (bubbleRanges == null || bubbleRanges.isEmpty()) return 0;
        long sum = 0;
        for (FreeTimeRange range : bubbleRanges) {
            if (range.getRangeType() != FreeTimeRangeType.BUBBLE) continue;
            LocalDateTime bubbleBegin = range.getBeginTime();
            LocalDateTime bubbleEnd = range.getEndTime();
            // 与 [origin, time] 求交集
            if (!bubbleEnd.isAfter(origin) || !bubbleBegin.isBefore(time)) continue;
            LocalDateTime effectiveBegin = bubbleBegin.isBefore(origin) ? origin : bubbleBegin;
            LocalDateTime effectiveEnd = bubbleEnd.isAfter(time) ? time : bubbleEnd;
            if (effectiveEnd.isAfter(effectiveBegin)) {
                sum += Duration.between(effectiveBegin, effectiveEnd).toMinutes();
            }
        }
        return sum;
    }

    /**
     * 找 gap 内可简化的完整周期块。
     * <p>
     * 按有效偏移（effective offset = 自然 offset - 之前 bubble 时长）对齐周期边界：
     * <ul>
     *   <li>startK = ceil(effectiveOffset(gap.begin) / cycleMinutes)（第一个 &ge; gap.begin 的周期边界索引）</li>
     *   <li>endK = floor(effectiveOffset(gap.end) / cycleMinutes)（第一个 &le; gap.end 的周期边界索引）</li>
     * </ul>
     * gap 内无 bubble，effective offset 线性增长，简化区间起止时间用
     * {@code gap.begin + (k*cycleMinutes - effOffsetBegin)} 算（gap 内自然 = effective）。
     *
     * @param gap          无优惠空隙
     * @param origin       周期起点（CONTINUOUS: calcBegin/billingOrigin；PERIOD: calcBegin）
     * @param bubbleRanges bubble 免费段列表（CONTINUOUS 传 List.of()；PERIOD 传 BUBBLE 段列表）
     * @param cycleMinutes 周期长度（分钟）
     * @param threshold    简化阈值（完整周期数 &gt; 阈值才简化，与 CONTINUOUS 原逻辑一致）
     * @return SimplifiedBlock 或 null（完整周期数不足阈值）
     */
    public static SimplifiedBlock findSimplifiedBlock(Range gap, LocalDateTime origin,
                                                     List<FreeTimeRange> bubbleRanges,
                                                     int cycleMinutes, int threshold) {
        long bubbleBeforeBegin = bubbleDurationBefore(gap.begin, origin, bubbleRanges);
        long bubbleBeforeEnd = bubbleDurationBefore(gap.end, origin, bubbleRanges);
        long effOffsetBegin = Duration.between(origin, gap.begin).toMinutes() - bubbleBeforeBegin;
        long effOffsetEnd = Duration.between(origin, gap.end).toMinutes() - bubbleBeforeEnd;
        if (effOffsetBegin < 0) effOffsetBegin = 0;
        if (effOffsetEnd < 0) effOffsetEnd = 0;

        int startK = effOffsetBegin % cycleMinutes == 0
                ? (int) (effOffsetBegin / cycleMinutes)
                : (int) (effOffsetBegin / cycleMinutes) + 1;
        int endK = (int) (effOffsetEnd / cycleMinutes);

        if (endK - startK > threshold) {
            // gap 内无 bubble，effective offset 线性 = 自然偏移，直接用 gap.begin 推算边界时间点
            LocalDateTime simplifiedBegin = gap.begin.plusMinutes((long) startK * cycleMinutes - effOffsetBegin);
            LocalDateTime simplifiedEnd = gap.begin.plusMinutes((long) endK * cycleMinutes - effOffsetBegin);
            return new SimplifiedBlock(simplifiedBegin, simplifiedEnd, endK - startK, startK, endK);
        }
        return null;
    }
}
