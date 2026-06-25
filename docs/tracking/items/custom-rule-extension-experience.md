# 优化自定义规则扩展体验

---
id: TODO-20260623-001
type: feature
priority: P2
status: todo
source_git: 4e081b3
created_at: 2026-06-23
completed_at:
completed_git:
---

## 背景

当前 `BillingRule` 接口虽然只有三个核心方法（`calculate`、`configClass`、`supportedModes`），但要实现一个能投入生产使用的自定义规则，调用方需要自行处理大量关注点：

1. **周期管理** — 24 小时周期划分、周期边界计算、跨周期截断
2. **封顶逻辑** — 周期封顶、结转累计金额、封顶后单元合并为免费单元
3. **免费时段交互** — 时间轴切分（`splitTimeAxis`）、气泡抽出模型、免费覆盖判断
4. **CONTINUE 模式** — `RuleState` 序列化/反序列化、状态恢复、截断单元处理
5. **简化计算** — `hasComplexFeatures`、`isSimplifiedSupported`、`buildSimplifiedUnit`、`findCyclesWithPromotion`
6. **累计金额** — `previousAccumulatedAmount`、`truncatedUnitChargedAmount` 扣减
7. **费用稳定窗口** — `feeEffectiveStart`/`feeEffectiveEnd` 计算
8. **截断标记** — `isTruncated` 检测和标记
9. **BillingSegmentResult 构建** — 每个规则重复构建相似的返回对象

这些关注点散落在 `AbstractTimeBasedRule`（~400 行）、各规则的内部类（`TimeFragment`、`CycleFragments`、`CycleUnits` 每规则各一份）、以及 `BillingService` 的 CONTINUE 模式前置处理中。

`AbstractTimeBasedRule` 试图做基类，但：
- 没有定义清晰的模板方法骨架（各子类的 `calculate` 流程差异很大）
- 大量逻辑（时间轴切分、周期组织、封顶应用）在各子类中重复
- 自定义规则开发者不知道该继承哪些行为、覆盖哪些方法
- 没有示例或文档说明"最简单的规则怎么写"
- 没有标准测试套件来验证自定义规则的正确性

同时，`CompositeTimeRule`（~1440 行）已经演化为超大类，同时承担 UNIT_BASED/CONTINUOUS 两种计费模式、简化计算、封顶、状态管理等多重职责。

## 目标

1. 为 `AbstractTimeBasedRule` 建立清晰的模板方法骨架，让子类只需覆盖核心差异逻辑
2. 抽取通用的周期管理、封顶、状态管理逻辑到基类或独立组件
3. 编写自定义规则开发指南，覆盖最简单规则到复杂规则的渐进式示例
4. 提供规则实现的验证清单或标准测试基类

## 范围

包含：

- 梳理 `AbstractTimeBasedRule` 和各子类的公共逻辑，评估可抽取的抽象
- 设计模板方法骨架：明确哪些方法是必须覆盖的、哪些是可选的、哪些由基类处理
- 评估 `CompositeTimeRule` 的职责拆分方案（可选，属于内部重构）
- 编写 `docs/guides/custom-rule-guide.md` 自定义规则开发指南
- 评估是否需要 `BillingRuleTestBase` 标准测试基类

不包含：

- 新增计费规则能力
- 改变现有规则的对外行为
- 停车业务专属规则实现

## 验收标准

- `AbstractTimeBasedRule` 有清晰的模板方法骨架，每个方法的职责和覆盖规则明确
- 自定义规则开发指南覆盖从最简规则到复杂规则的渐进式示例
- 指南中明确列出"实现一个规则必须处理的所有关注点清单"
- 现有所有规则测试通过，行为不变

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`

## 备注

- 此事项与已完成的 `TODO-20260514-007`（收敛核心引擎职责边界与规则复杂度）有承接关系——后者识别了规则复杂度问题，本事项聚焦于自定义规则的扩展体验
- 模板方法重构需要向后兼容，不改变现有规则的公共行为
- 优先级 P2，可在下一轮规则相关改动时一并推进
