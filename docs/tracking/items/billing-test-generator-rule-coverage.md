# 扩展测试结果生成器规则覆盖

---
id: TODO-20260508-003
type: feature
priority: P3
status: todo
source_git: 1206a30
created_at: 2026-05-08
completed_at:
completed_git:
---

## 背景

当前测试结果生成器第一版只支持 `dayNight`。`TestFeature` 已经预留了 `relativeTime`、`compositeTime`、`flatFree` 等规则相关功能点。

## 目标

让测试结果生成器可以根据规则类型生成更多规则的样本 JSON。

## 范围

- 支持 `relativeTime` 多时段和周期封顶样本。
- 支持 `compositeTime` 自然时段、跨时段模式、不足单元模式样本。
- 支持 `flatFree` 统一免费样本。

## 验收标准

- `TestGenerationRequest.chargeRuleType` 可以选择更多已实现规则。
- 每个新增规则至少有一个 JUnit 测试验证可生成 JSON。
- Runner 示例可以方便切换规则类型和功能点。

## 相关文件

- `bill-test/src/main/java/cn/shang/charging/generator/BillingTestCaseGenerator.java`
- `bill-test/src/main/java/cn/shang/charging/generator/TestFeature.java`
- `bill-test/src/main/java/cn/shang/charging/generator/BillingTestCaseGeneratorRunner.java`

## 备注

保持生成器只输出计费结果，不输出预期金额。
