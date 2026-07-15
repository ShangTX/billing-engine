package cn.shang.charging.promotion.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间段合并结果。
 * <p>
 * 由 {@code FreeTimeRangeMerger.merge()} 产出，包含：
 * <ul>
 *   <li>合并后的互斥时间段（mergedRanges）</li>
 *   <li>被舍弃的时间段（discardedRanges：超出整体区间、被高优先级覆盖）</li>
 *   <li>原始段ID到被舍弃部分的映射（originalToDiscarded）</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class TimeRangeMergeResult {

    /** 合并后的互斥时间段（按时间排序，已截取到整体区间内） */
    private List<FreeTimeRange> mergedRanges;
    /** 被舍弃的时间段（超出整体区间或被高优先级覆盖的部分） */
    private List<FreeTimeRange> discardedRanges;
    /** 原始时间段ID → 被舍弃部分列表（用于追踪哪些原始段被丢弃了多少） */
    private Map<String, List<FreeTimeRange>> originalToDiscarded;


    public TimeRangeMergeResult() {
        this.mergedRanges = new ArrayList<>();
        this.discardedRanges = new ArrayList<>();
        this.originalToDiscarded = new HashMap<>();
    }

    public void addMergedRange(FreeTimeRange range) {
        this.mergedRanges.add(range);
    }

    public void addDiscardedRange(FreeTimeRange range) {
        this.discardedRanges.add(range);

        // 记录到原始时间段映射
        String originalId = range.getId();
        originalToDiscarded.computeIfAbsent(originalId, k -> new ArrayList<>())
                .add(range.copyWithNewId()); // 保存副本，避免后续修改
    }

    public void addDiscardedRanges(List<FreeTimeRange> ranges) {
        for (FreeTimeRange range : ranges) {
            addDiscardedRange(range);
        }
    }

    /**
     * 获取指定原始时间段被舍弃的部分
     */
    public List<FreeTimeRange> getDiscardedParts(String originalId) {
        return originalToDiscarded.getOrDefault(originalId, new ArrayList<>());
    }

    /**
     * 获取指定原始时间段剩余的部分（未被舍弃）
     */
    public List<FreeTimeRange> getRemainingParts(String originalId) {
        List<FreeTimeRange> allParts = new ArrayList<>();
        // 在mergedRanges中查找属于该原始ID的时间段
        for (FreeTimeRange range : mergedRanges) {
            if (range.getId().equals(originalId)) {
                allParts.add(range);
            }
        }
        return allParts;
    }
}
