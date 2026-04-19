# AGENTS.md

本文件为 AI 编程助手（GitHub Copilot、Cursor、Windsurf 等）提供项目指导。

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
- 多种优惠类型（FREE_RANGE、FREE_MINUTES）

---

## 架构

### 模块结构

| 模块 | 职责 | 依赖 |
|------|------|------|
| **core** | 核心计费引擎，纯计算逻辑，无副作用 | 无 |
| **billing-api** | 便捷 API 封装，视图层逻辑 | core |
| **billing-v3-spring-boot-starter** | Spring Boot 3.0.x - 3.4.x | billing-api |
| **billing-v4-spring-boot-starter** | Spring Boot 3.5.x - 4.x | billing-api |
| **bill-test** | 集成测试 | core, billing-api |

### 计费管道

```
BillingService.calculate()
├── SegmentBuilder.buildSegments()     # 按收费方案变化切割
├── RuleResolver                       # 解析每个分段的规则
├── PromotionEngine.evaluate()         # 聚合优惠
├── BillingCalculator.calculate()      # 应用计费规则
└── ResultAssembler.assemble()         # 汇总结果
```

### 关键类

- `BillingService` - 核心调度入口
- `BillingRequest` / `BillingResult` - 输入/输出 POJO
- `BillingContext` - 计算上下文（不可变）
- `BillingRule` / `PromotionRule` - 规则接口（新增规则时实现）
- `PromotionAggregate` - 聚合免费时段
- `BillingTemplate` - 便捷 API 入口（billing-api 模块）

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
- 简单场景零开销
- 复杂度判断集中在计费开始时

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

当新计费规则无法通过现有逻辑实现时：

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

## 构建与测试

```bash
# 构建所有模块
mvn clean install

# 构建单个模块
mvn clean install -pl core

# 运行集成测试
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.PromotionTest"
```

---

## 文档同步要求

代码变更时，需同步更新文档：

| 文档 | 更新时机 |
|------|---------|
| `README.md` / `README_CN.md` | 对外 API 变更（类、字段、方法、枚举、常量） |
| `CLAUDE.md` | 架构变更、核心流程变更、新增特性 |
| `AGENTS.md` | 架构变更、开发原则变更 |

---

## 代码风格

- Lombok 注解广泛使用（`@Data`、`@Builder`、`@AllArgsConstructor`、`@Accessors(chain=true)`）
- 规则实现使用泛型类型安全（`configClass()` 类型检查）
- POJO 位于 `billing.pojo` 和 `promotion.pojo` 包
- 常量位于 `BConstants.ChargeRuleType` / `PromotionRuleType`