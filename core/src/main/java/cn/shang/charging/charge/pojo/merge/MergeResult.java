package cn.shang.charging.charge.pojo.merge;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 合并操作的结果
 */
@Data
public class MergeResult {
    private List<TimeSlot> mergedSlots;      // 合并后的时间段集合
    private List<TimeSlot> discardedSlots;   // 被舍弃的时间段集合
    private Map<String, List<TimeSlot>> originalToDiscarded; // 原始时间段与被舍弃部分的映射

    public MergeResult() {
        this.mergedSlots = new ArrayList<>();
        this.discardedSlots = new ArrayList<>();
        this.originalToDiscarded = new HashMap<>();
    }

    public void addMergedSlot(TimeSlot slot) {
        this.mergedSlots.add(slot);
    }

    public void addDiscardedSlot(TimeSlot slot) {
        this.discardedSlots.add(slot);

        // 记录到原始时间段映射
        String originalId = slot.getId();
        originalToDiscarded.computeIfAbsent(originalId, k -> new ArrayList<>())
                .add(slot.copyWithNewId()); // 保存副本，避免后续修改
    }

    public void addDiscardedSlots(List<TimeSlot> slots) {
        for (TimeSlot slot : slots) {
            addDiscardedSlot(slot);
        }
    }

    /**
     * 获取指定原始时间段被舍弃的部分
     */
    public List<TimeSlot> getDiscardedParts(String originalId) {
        return originalToDiscarded.getOrDefault(originalId, new ArrayList<>());
    }

    /**
     * 获取指定原始时间段剩余的部分（未被舍弃）
     */
    public List<TimeSlot> getRemainingParts(String originalId) {
        List<TimeSlot> allParts = new ArrayList<>();
        // 在mergedSlots中查找属于该原始ID的时间段
        for (TimeSlot slot : mergedSlots) {
            if (slot.getId().equals(originalId)) {
                allParts.add(slot);
            }
        }
        return allParts;
    }
}