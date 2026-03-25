# 空时段问题修复计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从源头杜绝空时段产生，修正 FreeTimeRangeMerger.preprocessRanges() 中的判断条件。

**Architecture:** 修改两处判断条件：boundaryReferences 判断和 mergedRanges 条件。

**Tech Stack:** Java 17, Lombok, Maven

**Spec:** `docs/superpowers/specs/2026-03-18-empty-time-range-fix-design.md`

**Status:** ✅ 已完成

---

## Chunk 1: 核心逻辑修复

### Task 1: 修改 boundaryReferences 判断条件

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java`

- [x] **Step 1: 修改判断条件**
  - 原：`if (originalRange.getBeginTime().isAfter(overallEnd))`
  - 改：`if (!originalRange.getBeginTime().isBefore(overallEnd))`
  - 说明：`!isBefore` 等价于 `>=`，包含 `beginTime == overallEnd` 的情况

- [x] **Step 2: 更新注释**
  - 添加说明：包括从 overallEnd 开始的时段

- [x] **Step 3: 编译验证** ✅ BUILD SUCCESS

---

### Task 2: 修改 mergedRanges 条件

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java`

- [x] **Step 1: 修改判断条件**
  - 原：`if (!start.isAfter(end))`
  - 改：`if (start.isBefore(end))`
  - 说明：`isBefore` 确保必须有正长度 (`start < end`)

- [x] **Step 2: 更新注释**
  - 移除旧注释：允许 start == end
  - 添加新注释：必须有正长度

- [x] **Step 3: 编译验证** ✅ BUILD SUCCESS

---

## Chunk 2: 测试验证

### Task 3: 运行回归测试

- [x] **Step 1: 运行 BoundaryReferencesTest** ✅ 全部通过
- [x] **Step 2: 运行 ContinueModeTest** ✅ 全部通过
- [x] **Step 3: 运行 PromotionCarryOverTest** ✅ 全部通过

---

## Chunk 3: 文档更新

### Task 4: 更新文档

- [x] **Step 1: 创建设计文档** ✅ `docs/superpowers/specs/2026-03-18-empty-time-range-fix-design.md`
- [x] **Step 2: 更新 MEMORY.md** ✅ 添加 #18 空时段问题修复
- [x] **Step 3: Commit** ✅ `cd5cbc5 fix: 从源头杜绝空时段产生`

---

## 完成标准

- [x] 代码修改完成并通过编译
- [x] 所有测试通过
- [x] 设计文档已创建
- [x] MEMORY.md 已更新
- [x] Commit 已提交

---

## 实现总结

### 修改的文件

1. `FreeTimeRangeMerger.java` - 修正两处判断条件

### 核心变更

| 位置 | 原条件 | 新条件 | 效果 |
|------|--------|--------|------|
| boundaryReferences | `isAfter` (>) | `!isBefore` (>=) | 边界时段正确进入 |
| mergedRanges | `!isAfter` (<=) | `isBefore` (<) | 杜绝空时段 |

### 测试验证

- BoundaryReferencesTest ✅
- ContinueModeTest ✅
- PromotionCarryOverTest ✅