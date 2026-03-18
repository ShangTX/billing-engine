# 空时段问题修复设计

**Status:** ✅ 已完成

**Date:** 2026-03-18

---

## 问题描述

在特定场景下，`FreeTimeRangeMerger.preprocessRanges()` 会产生空时段（beginTime == endTime），这些空时段最终被记录到 `usedFreeRanges` 中，导致数据不清洁。

### 典型场景

```
免费时段：09:00-10:00
计算窗口：07:30-09:00
```

### 问题现象

```json
{
  "usedFreeRanges": [
    {"beginTime": "2026-03-10 09:00:00", "endTime": "2026-03-10 09:00:00"}
  ]
}
```

---

## 根因分析

### 代码路径

```
FreeTimeRangeMerger.preprocessRanges()
    ↓
判断：beginTime.isAfter(overallEnd) ?
    ↓ NO（09:00 > 09:00 = false）
截取逻辑：
    start = max(09:00, 07:30) = 09:00
    end = min(10:00, 09:00) = 09:00
    ↓
start == end → 空时段进入 processed 列表
```

### 问题根源

| 条件 | 原逻辑 | 问题 |
|------|--------|------|
| boundaryReferences 判断 | `isAfter(overallEnd)` | 漏掉 `beginTime == overallEnd` 的情况 |
| mergedRanges 条件 | `!isAfter(end)` | 允许 `start == end` 的空时段通过 |

---

## 设计方案

### 方案：从源头杜绝

**核心思想**：在 `preprocessRanges()` 中修正判断条件，从源头防止空时段进入 `mergedRanges`。

### 修改点

#### 1. boundaryReferences 判断修正

```java
// 修改前
if (originalRange.getBeginTime().isAfter(overallEnd))

// 修改后：>= overallEnd 都进入 boundaryReferences
if (!originalRange.getBeginTime().isBefore(overallEnd))
```

#### 2. mergedRanges 条件收紧

```java
// 修改前：允许 start == end
if (!start.isAfter(end))

// 修改后：必须有正长度 (start < end)
if (start.isBefore(end))
```

### 数据流修复后

```
preprocessRanges()
    │
    ├─ endTime < overallStart → discardedRanges
    │
    ├─ beginTime >= overallEnd → boundaryReferences（修复点1）
    │
    └─ 与窗口有交集
          │
          ├─ 窗口后部分 → discardedRanges
          │
          └─ start < end → mergedRanges（修复点2）
```

---

## 影响分析

### 正面影响

1. **数据清洁**：不再产生空时段
2. **boundaryReferences 正确**：从窗口边界开始的优惠正确进入边界参考
3. **无需事后检查**：从源头杜绝，减少后续处理开销

### 无负面影响

- 所有现有测试通过
- boundaryReferences 功能正常
- CONTINUE 模式正常

---

## 测试验证

- `BoundaryReferencesTest` - 全部通过
- `ContinueModeTest` - 全部通过
- `PromotionCarryOverTest` - 全部通过