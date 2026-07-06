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
| TODO-20260630-002 | feature | P2 | 物化索引预估收入能力 | ec62357 | [materialized-index-revenue-estimation.md](tracking/items/materialized-index-revenue-estimation.md) |
| TODO-20260623-001 | feature | P2 | 优化自定义规则扩展体验 | 4e081b3 | [custom-rule-extension-experience.md](tracking/items/custom-rule-extension-experience.md) |
| TODO-20260706-001 | bug | P1 | GLOBAL 时长模式 FREE_MINUTES 时段化修复 | dfaa576 | [global-duration-materialization-fix.md](tracking/items/global-duration-materialization-fix.md) |
| TODO-20260706-002 | refactor | P1 | 计费引擎抽象重构:模式行为驱动 + 规则语义注入 | dfaa576 | [rule-abstraction-refactor.md](tracking/items/rule-abstraction-refactor.md) |
