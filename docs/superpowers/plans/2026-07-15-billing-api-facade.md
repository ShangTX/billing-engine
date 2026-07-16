# billing-api 轻量接入门面实施计划

Spec：`docs/superpowers/specs/2026-07-15-billing-api-facade-design.md`

## 步骤

- [x] 增强 `BillingTemplate`：新增 `calculateRaw`、`normalize`、请求深拷贝和 builder。
- [x] 保留 `TimeRounding` 在 `billing-api`，补充注释说明它是接入层工具，core 截断秒数只是保护。
- [x] 删除 `PromotionSavingsAnalyzer` 和 `BillingTemplate.calculatePromotionSavings`，统一使用 core 等效金额语义。
- [x] 补充测试：验证 `calculate` 不修改原始请求，`normalize` 返回分钟对齐副本，覆盖两种入口取整模式，builder 能组装默认入口并注册自定义规则。
- [x] 更新 README、用户指南和能力文档，重写 `billing-api` 定位。
- [x] 运行 `mvn test`。
