# DONE

本文件记录已经完成的功能和问题处理结果。条目从 `docs/TODO.md` 迁移而来。

## 维护规则

- 完成日期使用 `YYYY-MM-DD`。
- `completed_git` 记录完成该事项的 Git 短 SHA；如果实现和迁移分成多个提交，记录主要实现提交。
- 详情文档继续保留在 `docs/tracking/items/`，并更新状态为 `done`。

## 完成列表

| 完成日期 | ID | 类型 | 标题 | completed_git | 详情 |
|----------|----|------|------|---------------|------|
| 2026-05-18 | TODO-20260514-007 | feature | 收敛核心引擎职责边界与规则复杂度 | d70eb72 | [engine-boundary-and-rule-complexity.md](tracking/items/engine-boundary-and-rule-complexity.md) |
| 2026-05-18 | TODO-20260508-002 | feature | 落地预留计费规则常量 | d70eb72 | [reserved-charge-rule-types.md](tracking/items/reserved-charge-rule-types.md) |
| 2026-05-18 | TODO-20260508-005 | feature | 明确跨时间段计费单元处理的语义边界 | d70eb72 | [cross-period-unit-handling.md](tracking/items/cross-period-unit-handling.md) |
| 2026-05-18 | TODO-20260508-006 | feature | 新增 naturalTime 多自然时段计费规则 | d70eb72 | [multi-natural-period-rule.md](tracking/items/multi-natural-period-rule.md) |
| 2026-05-18 | TODO-20260508-001 | feature | 实现金额减免和折扣优惠 | d70eb72 | [promotion-amount-discount.md](tracking/items/promotion-amount-discount.md) |
| 2026-05-18 | TODO-20260508-004 | feature | 支持不完整计费单元的多种计费方式 | d70eb72 | [incomplete-unit-charge-modes.md](tracking/items/incomplete-unit-charge-modes.md) |
| 2026-05-18 | TODO-20260508-003 | feature | 扩展测试结果生成器规则覆盖 | d70eb72 | [billing-test-generator-rule-coverage.md](tracking/items/billing-test-generator-rule-coverage.md) |
| 2026-06-26 | TODO-20260623-002 | feature | Compact 计费结果模式 | 9f75d8d | [compact-billing-result-mode.md](tracking/items/compact-billing-result-mode.md) |
| 2026-07-01 | TODO-20260630-001 | refactor | UNIT_BASED 模式降级为独立计费规则 | 1a67c7b | [unit-based-mode-as-independent-rule.md](tracking/items/unit-based-mode-as-independent-rule.md) |
| 2026-07-01 | TODO-20260626-001 | bug | 实现不足单元计费方式配置的实际计费逻辑 | b02e2f0 | [incomplete-unit-charge-logic-implementation.md](tracking/items/incomplete-unit-charge-logic-implementation.md) |
| 2026-07-01 | TODO-20260630-003 | feature | 时长计费模式（Duration-Based Billing Mode） | 8338b2c | [duration-based-billing-mode.md](tracking/items/duration-based-billing-mode.md) |
| 2026-07-03 | TODO-20260702-002 | refactor | 计费规则门面策略结构重构 | f6ed969 | [facade-strategy-refactor.md](tracking/items/facade-strategy-refactor.md) |
| 2026-07-03 | TODO-20260702-001 | bug | GLOBAL_ORIGIN 窗口截取模式半成品止血 | 3e3830f | [global-origin-half-finished.md](tracking/items/global-origin-half-finished.md) |
