---
created: 2026-03-30T13:02:36.419Z
title: 计算位置与查询位置区别设计
area: billing-api
files:
  - billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java
  - billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
---

## Problem

当前设计没有明确向调用方区分"计算位置"和"查询位置"的概念，导致调用方可能混淆两者的语义。

**具体问题**：
- `calcEndTime` vs `queryTime` 的区别不够清晰
- `BillingResultViewer.viewAtTime()` 返回结果中缺少明确的类型区分
- 调用方可能不知道返回的是"计算到某位置的结果"还是"查询某时间点的结果"

**现有相关字段**：
- `BillingRequest.queryTime` - 查询时间点
- `BillingRequest.calcEndTime` - 计算结束时间
- `BillingResult.calculationEndTime` - 实际计算到的时间
- `CalculationWithQueryResult` - 已区分 calculationResult 和 queryResult

## Solution

**方案待定**，可能的实现方式：

1. **文档完善**：在 README 中明确说明两个概念的区别
2. **返回值增强**：在 BillingResult 中增加 `resultType` 枚举区分类型
3. **API 设计**：提供更明确的 API 方法命名

**概念定义**：
- **计算位置 (calculation position)**：引擎实际计算到的时间点，费用确定
- **查询位置 (query position)**：调用方想要查询的时间点，可能已计算也可能未计算

**需要确认**：
- 是否需要在返回结果中明确标识类型
- 调用方在什么场景下需要区分两者