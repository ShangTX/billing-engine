# Phase 1: 修复 CONTINUE 模式截断单元重复计费 - Context

**Gathered:** 2026-03-30
**Status:** Ready for planning

<domain>
## Phase Boundary

修复 CONTINUE 模式下截断单元重复计费问题：
- 截断单元（isTruncated=true）已收取完整单元费用
- 第二次计费时第一个单元若与截断单元时间重叠，不应重复收费
- 需要向调用方提供明确的合并标志

</domain>

<decisions>
## Implementation Decisions

### CarryOver 扩展
- 在 SegmentCarryOver 中添加 `lastTruncatedUnitStartTime` 字段（LocalDateTime）
- 如果 isTruncated=false，该字段为 null
- 如果 isTruncated=true，该字段记录截断单元的开始时间

### CONTINUE 模式合并计算
- 第二次计费开始时，检查 carryover 中的 lastTruncatedUnitStartTime
- 如果存在，第二次计费结果的第一个单元根据该时间信息进行合并计算
- 合并后的单元应该是一个完整的计费单元（不再截断）

### 返回值标志
- 在 BillingResult 中添加 `firstUnitMerged` 字段（Boolean）
- 在 BillingUnit 中添加 `mergedFromPrevious` 字段（Boolean）
- 让调用方知道第一个计费单元需要与上一次结果合并处理

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 核心数据结构
- `core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java` — 计算状态传递对象
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java` — 计费单元结构，包含 isTruncated 字段
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java` — 计费结果结构

### 计算逻辑
- `core/src/main/java/cn/shang/charging/billing/BillingService.java` — 计费服务入口
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java` — 结果汇总

### 规则实现
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java` — 时间规则基类

### 测试验证
- `bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java` — CONTINUE 模式测试类

</canonical_refs>

<specifics>
## Specific Ideas

**问题场景**：
```
第一次计算：07:30-09:00，单元长度45分钟
- 单元1：07:30-08:15，完整单元，已收费
- 单元2：08:15-09:00，截断单元（isTruncated=true），已按45分钟收费

第二次计算（CONTINUE）：09:00-10:30
- 单元1：09:00-09:45，按完整45分钟收费 ← 问题：08:15-09:00 已收费，重复！
```

**方案B 解决思路**：
1. 第一次计算结束，SegmentCarryOver 记录 `lastTruncatedUnitStartTime=08:15`
2. 第二次计算开始，发现截断单元起始时间
3. 合并计算：08:15-09:00（已截断） + 09:00-09:45（新） = 08:15-09:45（完整）
4. 返回结果：第一个单元标记为 merged，调用方更新上一次的单元2

</specifics>

<deferred>
## Deferred Ideas

None — 方案B 完整覆盖修复范围

</deferred>

---

*Phase: 01-continue*
*Context gathered: 2026-03-30*