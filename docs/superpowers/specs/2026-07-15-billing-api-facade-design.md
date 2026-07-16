# billing-api 轻量接入门面设计

日期：2026-07-15

## 背景

分钟精度、优惠等效金额、结果汇总等确定性计费能力已经收敛到 `core`。`billing-api` 不再承载独立计费语义，应调整为轻量接入门面，降低调用方组装成本，同时不重新定义 core 已有能力。

## 边界

- `core`：负责确定性计费语义和防御性保护。若调用方传入带秒数的时间，core 内部按分钟计算时可能通过 `toMinutes()` 等方式截断秒级差异；这只是保护处理，不作为推荐入口语义。
- `billing-api`：负责调用方接入便利，包括请求归一化、默认组件组装、便捷重载和兼容入口。
- `TimeRounding`：保留在 `billing-api`，定位为接入层显式归一化工具。调用方可在构造 `BillingRequest` 前使用，也可通过 `BillingTemplate` 默认归一化。

## 决策

1. `billing-api.TimeRounding` 不下沉到 core，避免和 core 的分钟精度保护形成双源语义。
2. `BillingTemplate.calculate` 不再原地修改传入的 `BillingRequest`，而是复制后归一化再调用 `BillingService`。
3. `BillingTemplate.calculateRaw` 直接调用 core，不做归一化，用于调用方已完成输入清洗或需要严格控制请求对象的场景。
4. `BillingTemplate.normalize` 显式暴露接入层归一化，方便调用方在入库、审计或复用前获得标准请求。
5. `BillingTemplate.builder(configResolver)` 提供默认 core 组件组装，并允许注册自定义计费规则和优惠规则。
6. 删除 `PromotionSavingsAnalyzer` 和 `BillingTemplate.calculatePromotionSavings`。优惠金额语义统一使用 core 的 `PromotionEquivalentCalculator` / `equivalentAmountSpec`。

## 非目标

- 不删除 `billing-api` 模块。
- 不移动 `TimeRounding` 到 core。
- 不让 `billing-api` 重新实现计费规则、优惠聚合或等效金额算法。

## 兼容性

- 现有 `new BillingTemplate(billingService, configResolver)` 仍可用。
- 现有 `calculate(request)` / `calculate(request, mode)` 仍可用，但不再修改原始 request。
- `calculatePromotionSavings` 不再保留；旧估算器不是权威等效金额语义。
- `TimeRoundingMode` 保留作为兼容字段；`BillingTemplate` 默认使用 `TRUNCATE_BOTH`，显式 `CEIL_BEGIN_TRUNCATE_END` 时对计费时间收窄、对外部 `FREE_RANGE` 优惠时间段放宽。其他旧枚举值按 `TRUNCATE_BOTH` 处理。
