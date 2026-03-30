# Billing Engine CONTINUE Mode Fix

## What This Is

修复 CONTINUE 模式下截断单元重复计费问题。这是一个 Java Maven 多模块时间计费引擎（停车收费场景），当使用 CONTINUE 模式从上次计算结果继续计算时，截断单元（isTruncated=true）会导致重复收费。

## Core Value

CONTINUE 模式下，截断单元与后续计算的第一个单元正确合并，避免重复收费。

## Requirements

### Validated

- ✓ 时间计费引擎核心功能 — 现有代码已验证
- ✓ FROM_SCRATCH 模式正常工作 — 现有代码已验证
- ✓ CarryOver 状态传递机制 — 现有代码已验证
- ✓ 序列化类型转换工具（TypeConversionUtil）— 已实现

### Active

- [ ] 在 SegmentCarryOver 中添加 lastTruncatedUnitStartTime 字段
- [ ] 在 BillingResult 中添加 firstUnitMerged 标志位
- [ ] 在 BillingUnit 中添加 mergedFromPrevious 标志位
- [ ] ResultAssembler 正确提取截断单元信息
- [ ] BillingService 在 CONTINUE 模式传递截断单元信息
- [ ] AbstractTimeBasedRule 处理截断单元合并计算逻辑
- [ ] 更新 README.md 和 README_CN.md 文档
- [ ] 测试验证修复效果

### Out of Scope

- 计算位置与查询位置区别设计 — 后续独立处理
- 其他计费模式问题 — 本次仅关注 CONTINUE 模式
- UI/前端相关 — 后端纯计算引擎

## Context

### 问题场景
```
第一次计算：07:30-09:00，单元长度45分钟
- 单元1：07:30-08:15，完整单元，已收费
- 单元2：08:15-09:00，截断单元（isTruncated=true），已按45分钟收费

第二次计算（CONTINUE）：09:00-10:30
- 单元1：09:00-09:45，按完整45分钟收费 ← 问题：08:15-09:00 已收费，重复！
```

### 方案B 解决思路
1. 第一次计算结束，SegmentCarryOver 记录 `lastTruncatedUnitStartTime=08:15`
2. 第二次计算开始，发现截断单元起始时间
3. 合并计算：08:15-09:00（已截断） + 09:00-09:45（新） = 08:15-09:45（完整）
4. 返回结果：第一个单元标记为 merged，调用方更新上一次的单元2

### 技术背景
- Java 21, Maven 多模块项目
- Lombok 用于 POJO
- 核心模块无外部依赖
- 已有 TypeConversionUtil 处理序列化类型转换

## Constraints

- **向后兼容**: 现有 FROM_SCRATCH 模式行为不变
- **无破坏性变更**: 新增字段使用 Boolean（可空），不改变现有字段语义
- **纯计算**: 不涉及缓存、持久化、日志存储
- **测试验证**: 现有 ContinueModeTest 必须通过

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 方案B：在 carryover 中记录截断单元开始时间 | 调用方可明确知道需要合并，避免歧义 | — Pending |
| 新增 firstUnitMerged 标志而非修改现有字段 | 向后兼容，调用方可选择性处理 | — Pending |
| 使用 Boolean 类型（可空） | 区分"未设置"与"false" | — Pending |

---

*Last updated: 2026-03-30 after initialization*

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state