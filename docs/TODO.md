# TODO

本文件是项目待实现功能和待解决问题的索引。每个条目必须链接到 `docs/tracking/items/` 下的详情文档。

## 维护规则

- 新增待办时，在本文件添加索引行，并创建对应详情文档。
- `source_git` 记录创建待办时的 Git 短 SHA。
- 开始实现前，先检查本文件是否已有对应条目，避免重复记录。
- 完成后，从本文件删除该条目，并追加到 `docs/DONE.md`。
- 完成迁移时，详情文档保留在原路径，更新其状态和完成信息。

## 待办列表

| ID | 类型 | 优先级 | 标题 | source_git | 详情 |
|----|------|--------|------|------------|------|
| TODO-20260508-001 | feature | P2 | 实现金额减免和折扣优惠 | 1206a30 | [promotion-amount-discount.md](tracking/items/promotion-amount-discount.md) |
| TODO-20260508-002 | feature | P2 | 落地预留计费规则常量 | 1206a30 | [reserved-charge-rule-types.md](tracking/items/reserved-charge-rule-types.md) |
| TODO-20260508-003 | feature | P3 | 扩展测试结果生成器规则覆盖 | 1206a30 | [billing-test-generator-rule-coverage.md](tracking/items/billing-test-generator-rule-coverage.md) |
| TODO-20260508-004 | feature | P2 | 支持不完整计费单元的多种计费方式 | b1a1a6c | [incomplete-unit-charge-modes.md](tracking/items/incomplete-unit-charge-modes.md) |
| TODO-20260508-005 | feature | P2 | 统一跨时间段计费单元处理方式 | b1a1a6c | [cross-period-unit-handling.md](tracking/items/cross-period-unit-handling.md) |
| TODO-20260508-006 | feature | P2 | 新增多自然时段计费规则 | b1a1a6c | [multi-natural-period-rule.md](tracking/items/multi-natural-period-rule.md) |
