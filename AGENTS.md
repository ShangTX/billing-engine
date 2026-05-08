# AGENTS.md

本文件为 AI 编程助手（GitHub Copilot、Cursor、Windsurf、Claude Code、Codex 等）提供项目指导。

---

## 项目文档

| 文档 | 位置 | 用途 |
|------|------|------|
| README | `README.md` | 英文项目入口、安装与最短示例，不作为完整 API Reference |
| 中文 README | `README_CN.md` | 中文项目入口、安装与最短示例，不作为完整 API Reference |
| 用户指南 | `docs/USER_GUIDE.md` | 面向调用方的唯一详细使用说明 |
| 能力文档 | `docs/billing-engine-capabilities.md` | 计费引擎能力梳理和设计讨论基础（英文） |
| 中文能力文档 | `docs/billing-engine-capabilities-zh.md` | 计费引擎能力梳理和设计讨论基础（中文） |
| 中文计算流程 | `docs/billing-engine-calculation-flow-zh.md` | 当前核心计费链路、查询链路和结果汇总流程 |
| 待办索引 | `docs/TODO.md` | 当前待实现功能和待解决问题 |
| 完成索引 | `docs/DONE.md` | 已完成事项及完成版本 |
| 事项详情 | `docs/tracking/items/` | 待办/问题的背景、目标、范围和验收标准 |
| 设计文档 | `docs/superpowers/specs/` | 当前有效的设计规格 |
| 实施计划 | `docs/superpowers/plans/` | 当前有效的实现计划 |
| 历史归档 | `docs/superpowers/archive/` | 历史设计和计划归档，仅作参考 |

`CLAUDE.md` 只作为 Claude Code 兼容入口，项目规则以本文件为准。
`docs/superpowers/archive/` 和其他历史文档不作为当前实现依据；当前状态以能力文档、TODO/DONE、有效 specs/plans 为准。

---

## 待办与问题追踪

项目使用 `docs/TODO.md`、`docs/DONE.md` 和 `docs/tracking/items/` 跟踪待实现功能和待解决问题。

### 新增待办

当发现新的功能、问题或设计债务，且不会在当前改动中立即完成时：

1. 在 `docs/tracking/items/` 新增详情文档，可从 `docs/tracking/templates/item-template.md` 复制结构。
2. 在 `docs/TODO.md` 添加索引行，链接到详情文档。
3. `source_git` 使用新增待办时的 `git rev-parse --short HEAD`。
4. 详情文档必须包含背景、目标、范围、验收标准和相关文件。

### 开始处理待办

处理已有事项前：

1. 先检查 `docs/TODO.md` 是否已有对应条目。
2. 阅读对应详情文档，确认范围和验收标准。
3. 如范围变化，先更新详情文档，再开始实现。

### 完成待办

当事项完成后，AI agent 必须同步更新追踪文档：

1. 确认实现已验证，并记录验证命令。
2. 将该条目从 `docs/TODO.md` 删除。
3. 在 `docs/DONE.md` 添加完成记录，包含完成日期和 `completed_git`。
4. 更新详情文档：`status: done`、`completed_at`、`completed_git`，并补充验证命令。
5. 如果完成提交尚未生成，可以先提交实现，再用后续文档提交记录迁移；`completed_git` 应指向主要实现提交。

---

## 开发流程

涉及新功能、行为变更、架构调整或复杂问题修复时，AI agent 应遵循 Superpowers 工作流：

1. **评估**：先阅读现有代码、文档和 `docs/TODO.md`，确认问题边界、现有设计和是否已有追踪事项。
2. **设计**：需要设计取舍时，先在 `docs/superpowers/specs/` 编写或更新设计文档。
3. **计划**：多步骤实现前，在 `docs/superpowers/plans/` 编写或更新实施计划。
4. **实现**：按计划执行，保持改动范围清晰，不引入无关重构。
5. **验证**：运行与改动风险匹配的测试或构建命令，并记录验证结果。
6. **文档同步**：根据“文档同步要求”更新 README、USER_GUIDE、能力文档、流程文档和 TODO/DONE。

小型文档修正、注释补充或无行为变化的局部清理，可以不新增 spec/plan，但仍需完成评估、验证和文档同步判断。

---

## 项目概述

一个**时间计费引擎**，适用于停车收费、场地租赁、按时间计费的服务。

**核心设计**：
```
时间轴 → 计费单元切割 → 应用优惠 → 应用收费规则 → 生成计费明细 → 汇总费用
```

**核心特性**：
- 可扩展规则系统（新增规则不修改核心引擎）
- 可追溯计费过程（完整明细输出）
- 支持继续计算（从已有结果继续）
- 支持计费分段（规则可随时间变化）
- 支持可组合规则和多种优惠类型（FREE_RANGE、FREE_MINUTES）

---

## 架构

### 模块结构

| 模块 | 职责 | 依赖 |
|------|------|------|
| **core** | 核心计费引擎，纯计算逻辑，无副作用 | 无 |
| **billing-api** | 便捷 API 封装，视图层逻辑、查询视图、优惠等效金额 | core |
| **billing-v3-spring-boot-starter** | Spring Boot 3.0.x - 3.4.x | billing-api |
| **billing-v4-spring-boot-starter** | Spring Boot 3.5.x - 4.x | billing-api |
| **bill-test** | 集成测试、示例、测试结果生成工具 | core, billing-api |

### 模块分层原则

- `core` 只负责计费计算，不处理缓存、数据库、持久化、日志存储等副作用。
- `billing-api` 提供调用侧便捷封装，处理查询视图、时间取整、优惠等效金额等视图层或辅助逻辑。
- 调用方优先使用 `billing-api`；需要高级定制时可以直接使用 `core`。
- `bill-test` 可以放集成验证、可运行样例和测试辅助工具，但不要让测试工具反向污染正式 API。

### 计费管道

```
BillingService.calculate()
├── SegmentBuilder.buildSegments()     # 按收费方案变化切割
├── BillingConfigResolver              # 解析每个分段的规则、优惠和计费模式
├── PromotionEngine.evaluate()         # 聚合优惠
├── BillingCalculator.calculate()      # 应用计费规则
└── ResultAssembler.assemble()         # 汇总结果
```

### 关键类

- `BillingService` - 核心调度入口
- `BillingRequest` / `BillingResult` - 输入/输出 POJO
- `BillingContext` - 计算上下文
- `BillingRule` / `PromotionRule` - 规则接口（新增规则时实现）
- `PromotionAggregate` - 聚合免费时段
- `BillingTemplate` - 便捷 API 入口（billing-api 模块）
- `BillingResultViewer` - 查询时点视图逻辑
- `PromotionEquivalentCalculator` - 优惠等效金额计算

查询时点金额通过 `BillingUnit.valueSpec` 和 `BillingResultViewer` 计算；通用查询层不应解析规则私有 `ruleData`。

---

## 开发原则（必须遵守）

### 原则1：核心引擎只负责计算
- ✅ 计费计算
- ❌ 缓存、数据库、持久化、日志存储（外层实现）

### 原则2：规则必须是纯计算
- ✅ 无副作用：输入 → 输出（确定性）
- ❌ 访问数据库、调用远程接口、依赖外部状态

### 原则3：时间计算必须可重复
- 同样输入 → 同样输出（确定性）

### 原则4：规则不应相互依赖
- ❌ 规则 A 调用规则 B
- ✅ Engine → RuleA, Engine → RuleB（引擎统一执行）

### 原则5：规则配置与规则实现分离
- `RuleConfig`: 只描述参数
- `BillingRule`: 负责计算

### 原则6：简单优先，高级特性隔离
- 简单场景零额外复杂度。
- 复杂度判断集中在计费开始或统一编排层，不要散落在规则内部。
- 高级特性通过明确配置或独立数据结构隔离，避免影响普通计费路径。
- 规则私有逻辑可以复杂，但不要让规则私有语义泄漏到通用查询层或核心管道。

---

## 禁止事项

| 禁止行为 | 错误示例 |
|---------|---------|
| 在规则中访问数据库 | `rule.calculate()` 内部查询数据库 |
| 规则修改全局状态 | 修改全局变量、修改共享对象 |
| 规则改变计费流程 | 改变引擎执行顺序 |
| 规则之间相互调用 | RuleA → RuleB |

---

## 核心代码修改原则

当需要修改 core 主流程、公共 POJO、查询语义、规则公共抽象或新增无法通过现有扩展点实现的计费能力时：

1. **先评估**：确认无法通过规则扩展实现
2. **不影响其他规则**：现有测试必须全部通过
3. **向后兼容**：已有计算结果保持不变

---

## 计费模式

| 模式 | 说明 |
|------|------|
| `CONTINUOUS` | 连续时间模式，时间单位可被优惠/规则打断 |
| `UNIT_BASED` | 计费单位模式，固定单位长度，独立计算 |

规则必须通过 `supportedModes()` 声明支持的模式。

---

## 优惠类型

| 类型 | 说明 |
|------|------|
| `FREE_RANGE` | 免费时间段（如 01:00-04:00 免费） |
| `FREE_MINUTES` | 免费分钟数（在时间窗口内最优分配） |

---

## 文档同步要求

代码变更时，需同步更新文档：

| 文档 | 更新时机 |
|------|---------|
| `README.md` / `README_CN.md` | 对外 API 变更（类、字段、方法、枚举、常量） |
| `docs/USER_GUIDE.md` | 调用方式、查询语义、结果结构、业务使用方式变化 |
| `docs/billing-engine-capabilities.md` / `docs/billing-engine-capabilities-zh.md` | 已实现能力、规则覆盖、优惠覆盖、限制项变化 |
| `docs/billing-engine-calculation-flow-zh.md` | 核心计费流程、查询流程、结果汇总、结转、简化计算流程变化 |
| `docs/TODO.md` / `docs/DONE.md` / `docs/tracking/items/` | 新增、开始或完成待办事项 |
| `docs/superpowers/specs/` | 新设计、新机制、设计原则变更 |
| `docs/superpowers/plans/` | 多步骤实现计划 |
| `AGENTS.md` | 架构变更、开发原则变更、AI 协作规则变更 |

`CLAUDE.md` 不再维护项目规则；如需调整 AI 协作规则，更新 `AGENTS.md`。
`docs/billing-engine-capabilities.md` 与 `docs/billing-engine-capabilities-zh.md` 是同一能力文档的双语版本，能力变化时必须同步更新。

---

## Git 提交规范

- 提交信息前缀格式：`[工具|模型|主要插件]`，例如 `[codex|gpt-5|superpowers] docs: update capability docs`。
- 只修改由当前 agent 自己创建的提交信息；不要改写用户或其他 agent 的提交，除非用户明确要求。
- 提交前确认工作区中是否存在无关改动，避免把无关文件混入提交。

---

## 代码风格

- Lombok 注解广泛使用（`@Data`、`@Builder`、`@AllArgsConstructor`、`@Accessors(chain=true)`）
- 规则实现使用泛型类型安全（`configClass()` 类型检查）
- POJO 位于 `cn.shang.charging.billing.pojo` 和 `cn.shang.charging.promotion.pojo` 包
- 常量位于 `BConstants.ChargeRuleType` / `PromotionRuleType`
