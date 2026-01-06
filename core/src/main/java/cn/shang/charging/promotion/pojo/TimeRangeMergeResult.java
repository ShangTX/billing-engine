package cn.shang.charging.promotion.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class TimeRangeMergeResult {

    private List<FreeTimeRange> mergedRanges;      // 合并后的时间段集合
    private List<FreeTimeRange> discardedRanges;   // 被舍弃的时间段集合
    private Map<String, List<FreeTimeRange>> originalToDiscarded; // 原始时间段与被舍弃部分的映射


    public TimeRangeMergeResult() {
        this.mergedRanges = new ArrayList<>();
        this.discardedRanges = new ArrayList<>();
        this.originalToDiscarded = new HashMap<>();
    }

    public void addMergedSlot(FreeTimeRange slot) {
        this.mergedRanges.add(slot);
    }

    public void addDiscardedSlot(FreeTimeRange slot) {
        this.discardedRanges.add(slot);

        // 记录到原始时间段映射
        String originalId = slot.getId();
        originalToDiscarded.computeIfAbsent(originalId, k -> new ArrayList<>())
                .add(slot.copyWithNewId()); // 保存副本，避免后续修改
    }

    public void addDiscardedSlots(List<FreeTimeRange> slots) {
        for (FreeTimeRange slot : slots) {
            addDiscardedSlot(slot);
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
        for (FreeTimeRange slot : mergedRanges) {
            if (slot.getId().equals(originalId)) {
                allParts.add(slot);
            }
        }
        return allParts;
    }
}
