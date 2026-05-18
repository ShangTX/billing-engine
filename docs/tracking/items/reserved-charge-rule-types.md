# 落地预留计费规则常量

---
id: TODO-20260508-002
type: feature
priority: P2
status: done
source_git: 1206a30
created_at: 2026-05-08
completed_at: 2026-05-18
completed_git:
---

## 背景

`BConstants.ChargeRuleType` 中存在 `times`、`naturalTime`、`nrTimeMix` 等预留常量，README_CN 中也说明它们尚未实现。

## 目标

明确这些预留规则是否继续保留、实现、合并到现有规则，或从对外文档中降级。

## 决策结果

| 常量 | 决策 | 理由 |
|------|------|------|
| `naturalTime` | 废弃 | `CompositeTimeRule.NaturalPeriod` 已完整覆盖多自然时段场景 |
| `nrTimeMix` | 废弃 | `CompositeTimeRule` 本身就是 natural-relative mix 设计 |
| `times` | 保留为预留 | 按次数计费是非时间计费场景，不在当前引擎范围内 |

## 实施内容

1. **BConstants.java**：
   - `naturalTime`、`nrTimeMix` 标记为 `@Deprecated`，注释说明使用 `compositeTime` 替代
   - `times` 保留，注释说明”非时间计费场景，需另行设计”

2. **README_CN.md / README.md**：
   - 更新能力说明，明确 `naturalTime`、`nrTimeMix` 已被 `compositeTime` 覆盖
   - 区分预留优惠类型（AMOUNT/DISCOUNT）和预留计费类型（times）

3. **billing-engine-capabilities-zh.md**：
   - 新增表格形式的预留规则状态说明

## 验收标准

- ✅ 每个预留常量都有明确状态：实现、保留、合并或废弃。
- ✅ 对外文档不再只停留在”尚未实现”的模糊描述。
