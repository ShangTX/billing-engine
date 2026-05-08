# Tracking Documents

本目录用于维护项目待办和已完成事项的详情文档。

## 文件结构

- `docs/TODO.md`：当前待实现功能和待解决问题索引。
- `docs/DONE.md`：已完成事项索引。
- `docs/tracking/items/`：每个事项的详情文档。
- `docs/tracking/templates/`：事项详情模板。

## 事项生命周期

1. **发现事项**：创建详情文档，并在 `docs/TODO.md` 添加索引。
2. **开始处理**：先阅读详情文档，必要时补充上下文和验收标准。
3. **完成处理**：实现、验证并提交后，从 `docs/TODO.md` 移除索引，追加到 `docs/DONE.md`。
4. **更新详情**：将详情文档状态改为 `done`，记录完成日期、完成 Git 版本和验证命令。

## Git 版本记录

- `source_git`：创建事项时的 `git rev-parse --short HEAD`。
- `completed_git`：完成事项的主要实现提交短 SHA。
- 如果事项只涉及文档整理，也应记录对应文档提交短 SHA。
