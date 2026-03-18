# 延伸与优惠交互实现计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现边界参考机制，让延伸逻辑能够识别窗口外的优惠边界，确保延伸不会"闯入"未处理的优惠区域。

**Architecture:** 在 FreeTimeRangeMerger 中区分三种时段处理，通过 boundaryReferences 传递窗口外的优惠信息给规则层，延伸时检查合并后的时段列表。

**Tech Stack:** Java 17, Lombok, Maven

**Spec:** `docs/superpowers/specs/2026-03-18-extension-promotion-interaction-design.md`

**Status:** ✅ 已完成

---

## 文件结构

```
core/src/main/java/cn/shang/charging/
├── promotion/pojo/
│   └── TimeRangeMergeResult.java    # 新增 boundaryReferences 字段
├── promotion/
│   ├── FreeTimeRangeMerger.java     # 修改预处理逻辑
│   └── PromotionEngine.java         # 传递 boundaryReferences
├── promotion/pojo/
│   └── PromotionAggregate.java      # 新增 boundaryReferences 字段
└── charge/rules/
    ├── relativetime/
    │   └── RelativeTimeRule.java    # 修改 findNextFreeRangeBoundary
    └── daynight/
        └── DayNightRule.java        # 修改 findNextFreeRangeBoundary
```

---

## Chunk 1: 数据结构变更

### Task 1: TimeRangeMergeResult 新增字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/TimeRangeMergeResult.java`

- [x] **Step 1: 新增 boundaryReferences 字段和方法**
- [x] **Step 2: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 3: Commit** (合并提交)

---

### Task 2: PromotionAggregate 新增字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionAggregate.java`

- [x] **Step 1: 新增 boundaryReferences 字段**
- [x] **Step 2: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 3: Commit** (合并提交)

---

## Chunk 2: 核心逻辑修改

### Task 3: FreeTimeRangeMerger 修改预处理逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java`

- [x] **Step 1: 修改 preprocessRanges() 方法**
- [x] **Step 2: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 3: Commit** (合并提交)

---

### Task 4: PromotionEngine 传递 boundaryReferences

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`

- [x] **Step 1: 修改 evaluate() 方法返回值**
- [x] **Step 2: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 3: Commit** (合并提交)

---

## Chunk 3: 规则层修改

### Task 5: RelativeTimeRule 修改延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [x] **Step 1: 修改 extendLastUnit 方法签名和调用**
- [x] **Step 2: 修改 findNextFreeRangeBoundary 方法**
- [x] **Step 3: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 4: Commit** (合并提交)

---

### Task 6: DayNightRule 修改延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [x] **Step 1: 同 RelativeTimeRule 的修改方式**
- [x] **Step 2: 编译验证** ✅ BUILD SUCCESS
- [x] **Step 3: Commit** (合并提交)

---

## Chunk 4: 测试验证

### Task 7: 测试验证

- [x] **Step 1: 运行 CONTINUE 模式测试** ✅ 所有测试通过
- [x] **Step 2: 运行优惠结转测试** ✅ 所有测试通过

---

## 完成标准

- [x] 所有代码修改完成并通过编译
- [x] 新增测试场景通过
- [x] 回归测试全部通过
- [x] 不违背设计原则
- [x] 文档已更新（CLAUDE.md）

---

## 实现总结

### 修改的文件

1. `TimeRangeMergeResult.java` - 新增 `boundaryReferences` 字段和 `addBoundaryReference()` 方法
2. `PromotionAggregate.java` - 新增 `boundaryReferences` 字段
3. `FreeTimeRangeMerger.java` - 修改 `preprocessRanges()` 区分窗口前后时段
4. `PromotionEngine.java` - 传递 `boundaryReferences` 到 `PromotionAggregate`
5. `RelativeTimeRule.java` - 修改 `findNextFreeRangeBoundary()` 和 `extendLastUnit()`
6. `DayNightRule.java` - 同上

### 核心变更

- 窗口前的时段 → 丢弃
- 窗口内的时段 → mergedRanges（参与计算）
- 窗口后的时段 → boundaryReferences（边界参考）

### 测试验证

- `ContinueModeTest` - 所有测试通过
- `PromotionCarryOverTest` - 所有测试通过