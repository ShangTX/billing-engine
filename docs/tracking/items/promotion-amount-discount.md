# 实现金额减免和折扣优惠

---
id: TODO-20260508-001
type: feature
priority: P2
status: todo
source_git: 1206a30
created_at: 2026-05-08
completed_at:
completed_git:
---

## 背景

`BConstants.PromotionType` 中已有 `AMOUNT` 和 `DISCOUNT`，文档中也标注为待实现，但当前主要优惠能力集中在 `FREE_RANGE` 和 `FREE_MINUTES`。

## 目标

为金额减免和折扣优惠补齐设计、实现、测试和文档说明。

## 范围

- 定义金额减免和折扣优惠的规则配置。
- 明确它们与免费时段、免费分钟、外部优惠的组合顺序。
- 补充核心计算、测试和用户文档。

## 验收标准

- 可以通过规则或外部输入应用金额减免。
- 可以通过规则或外部输入应用折扣优惠。
- 与现有 `FREE_RANGE`、`FREE_MINUTES` 组合时结果可解释。
- README、README_CN 和 USER_GUIDE 说明同步。

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`
- `README_CN.md`
- `docs/USER_GUIDE.md`

## 备注

实现前需要先讨论优惠叠加顺序和优惠等效金额语义。
