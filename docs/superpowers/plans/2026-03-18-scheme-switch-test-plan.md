# 方案切换场景测试计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现方案切换场景的全面测试，覆盖单次切换、多次切换、SEGMENT_LOCAL/GLOBAL_ORIGIN 模式。

**Architecture:** 创建 SchemeSwitchTest 测试类，模拟旺季/淡季停车场收费场景。

**Tech Stack:** Java 17, Lombok, Maven

**Spec:** `docs/superpowers/specs/2026-03-18-scheme-switch-test-design.md`

**Status:** ✅ 已完成

---

## 文件结构

```
bill-test/src/main/java/cn/shang/charging/
└── SchemeSwitchTest.java    # 新建测试类
```

---

## Chunk 1: 测试框架搭建

### Task 1: 创建测试类骨架

**Files:**
- Create: `bill-test/src/main/java/cn/shang/charging/SchemeSwitchTest.java`

- [x] **Step 1: 创建测试类基础结构**
  - main 方法入口
  - BASE_DATE 常量
  - TIME_FORMAT 格式化器
  - 辅助方法占位

- [x] **Step 2: 实现 createBillingService 辅助方法**
  - 支持旺季/淡季两种方案配置
  - BillingConfigResolver 实现方案切换

- [x] **Step 3: 实现 createRequest 辅助方法**
  - 支持设置 SchemeChange 列表
  - 支持设置 SegmentCalculationMode

- [x] **Step 4: 编译验证** ✅ BUILD SUCCESS

---

## Chunk 2: 单次切换测试

### Task 2: 场景1 - 旺季→淡季（SEGMENT_LOCAL）

- [x] **Step 1: 实现测试方法**
  ```java
  static void testSingleSwitch_PeakToOffPeak_SegmentLocal()
  ```

- [x] **Step 2: 配置测试数据**
  - 计费时间：10月01日 08:00 - 10月15日 08:00
  - 切换点：10月11日 00:00

- [x] **Step 3: 验证分段正确性**
  - 分段数量：2
  - 分段1：旺季方案
  - 分段2：淡季方案

- [x] **Step 4: 验证金额计算**
  - 分段独立起算
  - 周期边界独立

- [x] **Step 5: 输出测试结果**

---

### Task 3: 场景2 - 淡季→旺季（GLOBAL_ORIGIN）

- [x] **Step 1: 实现测试方法**
  ```java
  static void testSingleSwitch_OffPeakToPeak_GlobalOrigin()
  ```

- [x] **Step 2: 配置测试数据**
  - 计费时间：04月15日 08:00 - 04月25日 08:00
  - 切换点：04月20日 00:00

- [x] **Step 3: 验证全局起点传递**

- [x] **Step 4: 验证周期边界计算**
  - 从全局起点计算周期边界

- [x] **Step 5: 输出测试结果**

---

## Chunk 3: 多次切换测试

### Task 4: 场景3 - 跨三个季节

- [x] **Step 1: 实现测试方法**
  ```java
  static void testMultipleSwitch_ThreeSeasons()
  ```

- [x] **Step 2: 配置测试数据**
  - 计费时间：09月01日 08:00 - 次年05月01日 08:00
  - 切换点：10月11日、04月20日

- [x] **Step 3: 验证分段数量**
  - 预期：3个分段

- [x] **Step 4: 验证每个分段的方案**

- [x] **Step 5: 验证总金额**

---

### Task 5: 场景4 - 切换点在单元边界

- [x] **Step 1: 实现测试方法**
  ```java
  static void testSwitchAtUnitBoundary()
  ```

- [x] **Step 2: 配置测试数据**
  - 切换点恰好是单元边界
  - 验证无截断单元

- [x] **Step 3: 验证单元完整性**

---

## Chunk 4: CONTINUE模式测试

### Task 6: 场景5 - CONTINUE跨方案切换

- [x] **Step 1: 实现测试方法**
  ```java
  static void testContinue_CrossScheme()
  ```

- [x] **Step 2: 第一次计算（旺季内）**

- [x] **Step 3: 第二次计算（跨入淡季）**

- [x] **Step 4: 验证分段自动识别**

- [x] **Step 5: 验证状态结转**

---

## Chunk 5: 测试验证

### Task 7: 运行完整测试

- [x] **Step 1: 编译通过** ✅
- [x] **Step 2: 所有场景通过** ✅
- [x] **Step 3: 输出完整测试报告** ✅

---

## 完成标准

- [x] 测试类创建完成
- [x] 所有测试场景实现
- [x] 测试输出详细结果
- [x] 回归测试通过
- [x] Commit 已提交

---

## 验证清单

| 场景 | 切换次数 | 计算模式 | 状态 |
|------|---------|---------|------|
| 旺季→淡季 | 1 | SEGMENT_LOCAL | ✅ |
| 淡季→旺季 | 1 | GLOBAL_ORIGIN | ✅ |
| 三季节切换 | 2 | SEGMENT_LOCAL | ✅ |
| 单元边界切换 | 1 | SEGMENT_LOCAL | ✅ |
| CONTINUE跨方案 | 1 | SEGMENT_LOCAL | ✅ |