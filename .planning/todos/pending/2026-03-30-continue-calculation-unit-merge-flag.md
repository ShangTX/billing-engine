---
created: 2026-03-30T13:02:36.419Z
title: 继续计算结果单元合并标志
area: billing
files:
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingSegmentResult.java
---

## Problem

CONTINUE 模式下，继续计算结果的第一个单元可能与上一次最后一个计费单元合并，但当前没有明确的标志告知调用方这一情况。

**具体问题**：
- 调用方无法判断新结果的第一单元是否是全新单元还是与上次最后单元合并的结果
- 这影响调用方对计费结果的展示和存储策略
- 可能导致重复计费或数据不一致

**相关代码位置**：
- `BillingUnit.java` - 计费单元结构
- `BillingResult.java` - 返回结果结构
- `isTruncated` 字段已有但语义不够明确

## Solution

**方案待定**，可能的实现方式：

1. **新增字段**：在 `BillingUnit` 中增加 `mergedFromPrevious` 布尔字段
2. **新增字段**：在 `BillingResult` 中增加 `firstUnitMerged` 布尔字段
3. **利用现有字段**：扩展 `isTruncated` 的语义，或新增枚举值

**需要确认**：
- 调用方具体需要什么信息来正确处理合并场景
- 是否需要知道具体合并了多少分钟/金额