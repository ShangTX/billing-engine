# 优惠两级模型实现

---
id: TODO-20260702-003
type: feature
priority: P1
status: done
source_git: 81ca938
created_at: 2026-07-02
completed_at: 2026-07-03
completed_git: 1c032c5
---

## 背景

当前 `PromotionEngine.evaluate` 每段独立执行，外部优惠（`externalPromotions`，如优惠券）在多分段下重复使用——FREE_MINUTES 每段各分配一次、FREE_RANGE 跨段边界被切断或重复。这是真实 bug：用户和运营者视角是"这笔停车享了什么优惠"，分段是引擎内部实现细节，不应导致优惠重复或丢失。

`docs/designs/segment-promotion-consistency.md` 曾论述"GLOBAL_ORIGIN 减法"保证外部优惠一致，但代码核实 GLOBAL_ORIGIN 是半成品（TODO-20260702-001），且减法只对 GLOBAL_ORIGIN 生效。

新设计（`docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.2）改为"外部优惠跨段共享可用量池 + 段内按优先级聚合 + 回写扣减"，不依赖减法，SEGMENT_LOCAL 下也能保证一致。

## 目标

- 外部优惠全局一致：整笔停车享一次，多分段不重复
- 方案内优惠每段独立：分段1 用方案A 的优惠、分段2 用方案B 的优惠
- 段内两类优惠按优先级聚合，外部优惠可能被方案内优惠覆盖而未使用
- 按优惠来源从本段结果分辨实际使用量，回写扣减可用量池，下段拿到正确剩余

## 范围

包含：

- **外部优惠可用量池**：分段前建立，跨段共享剩余量（FREE_MINUTES/FREE_RANGE 的剩余量）
- **段内聚合**：每段 `PromotionEngine.evaluate` 入参为"剩余外部优惠 + 本段方案内优惠规则"，按优先级聚合，产出本段最终免费段
- **回写扣减**：从本段结果按来源（`PromotionUsage.promotionId`）分辨实际使用的外部优惠，回写扣减池
- **AMOUNT/DISCOUNT 事后结算**：不进核心计算，由 `AmountDiscountApplier` 在最终结果上统一结算

不包含：

- 门面策略结构（TODO-20260702-002，本 TODO 的前置）
- FREE_MINUTES 时段化下放（TODO-20260702-004）
- GLOBAL_ORIGIN 窗口截取细节（TODO-20260702-001 止血，截取待下一阶段）
- 外部优惠状态载体的具体形式（carryOver vs 分段专用，见 spec §5 开放问题）

## 验收标准

- 多分段下外部优惠（FREE_MINUTES/FREE_RANGE）只使用一次，不重复
- 方案内优惠每段独立，不跨段
- 段内两类优惠按优先级聚合，外部优惠可能被覆盖而未使用
- AMOUNT/DISCOUNT 事后结算，不参与免费段切分
- 现有优惠测试通过，单分段行为不变

## 相关文件

- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`（入参改为剩余外部优惠 + 方案内）
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`（分段循环管理可用量池 + 回写扣减）
- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionUsage.java`（来源标识）
- `core/src/main/java/cn/shang/charging/promotion/AmountDiscountApplier.java`（事后结算）

## 备注

- 依赖 TODO-20260702-002（门面策略结构）：策略结构先立，优惠处理落到策略侧
- 与 TODO-20260701-001（FREE_RANGE PromotionUsage）关联：回写扣减依赖 PromotionUsage 的来源标识和实际使用量
- 取代 `docs/designs/segment-promotion-consistency.md` 的减法方案
- 优先级 P1：外部优惠重复是真实 bug

## 验证

```bash
mvn -pl bill-test -am test
# Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
# 含 ExternalPromotionPoolTest：FREE_MINUTES 跨段不重复 + FREE_RANGE 跨段边界分裂
```
