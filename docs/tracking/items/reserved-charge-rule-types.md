# 落地预留计费规则常量

---
id: TODO-20260508-002
type: feature
priority: P2
status: todo
source_git: 1206a30
created_at: 2026-05-08
completed_at:
completed_git:
---

## 背景

`BConstants.ChargeRuleType` 中存在 `times`、`naturalTime`、`nrTimeMix` 等预留常量，README_CN 中也说明它们尚未实现。

## 目标

明确这些预留规则是否继续保留、实现、合并到现有规则，或从对外文档中降级。

## 范围

- 评估 `times`、`naturalTime`、`nrTimeMix` 的业务价值。
- 为保留项补充设计文档和实现计划。
- 对不再计划的项更新 README 和 USER_GUIDE。

## 验收标准

- 每个预留常量都有明确状态：实现、保留、合并或废弃。
- 对外文档不再只停留在“尚未实现”的模糊描述。

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`
- `README.md`
- `README_CN.md`
- `docs/USER_GUIDE.md`

## 备注

该事项可以拆分成多个规则级别的独立设计。
