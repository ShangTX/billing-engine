# Roadmap

## Phase 1: 修复 CONTINUE 模式截断单元重复计费

**Goal:** 在 carryover 中记录截断单元信息，CONTINUE 时合并计算避免重复收费

**Status:** planning

**Requirements:** CONTINUE-01 ~ CONTINUE-19

### Success Criteria
1. 第一次计算结束时，SegmentCarryOver 正确记录 lastTruncatedUnitStartTime
2. 第二次 CONTINUE 计算时，第一个单元与截断单元正确合并
3. BillingResult.firstUnitMerged 标志正确设置，调用方可识别合并单元
4. ContinueModeTest 测试通过，无重复收费

### Scope
- 数据结构扩展（SegmentCarryOver, BillingResult, BillingUnit）
- 业务逻辑修改（ResultAssembler, BillingService, AbstractTimeBasedRule）
- 文档更新（README.md, README_CN.md）
- 测试验证

### Key Files
- `core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`

---

## Phase Coverage

| Phase | Requirements | Coverage |
|-------|--------------|----------|
| 1 | CONTINUE-01 ~ CONTINUE-19 | 100% |

---

*Roadmap created: 2026-03-30*