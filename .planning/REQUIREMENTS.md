# Requirements

## v1 Requirements

### CONTINUE-01: SegmentCarryOver 扩展
- [ ] **CONTINUE-01**: SegmentCarryOver 包含 `lastTruncatedUnitStartTime` 字段（LocalDateTime 类型）
- [ ] **CONTINUE-02**: 字段正确序列化/反序列化
- [ ] **CONTINUE-03**: isTruncated=false 时字段为 null

### CONTINUE-02: BillingResult 合并标志
- [ ] **CONTINUE-04**: BillingResult 包含 `firstUnitMerged` 字段（Boolean 类型）
- [ ] **CONTINUE-05**: 字段在 CONTINUE 模式正确设置

### CONTINUE-03: BillingUnit 合并标志
- [ ] **CONTINUE-06**: BillingUnit 包含 `mergedFromPrevious` 字段（Boolean 类型）
- [ ] **CONTINUE-07**: 合并单元正确标记

### CONTINUE-04: ResultAssembler 提取逻辑
- [ ] **CONTINUE-08**: extractLastTruncatedUnitStartTime 方法正确实现
- [ ] **CONTINUE-09**: buildBillingCarryOver 正确调用提取方法

### CONTINUE-05: BillingService 传递逻辑
- [ ] **CONTINUE-10**: calculate() 方法提取 previousTruncatedUnitStartTime
- [ ] **CONTINUE-11**: BillingContext 包含 previousTruncatedUnitStartTime 字段
- [ ] **CONTINUE-12**: BillingContext.builder() 正确传递字段

### CONTINUE-06: 规则层合并计算
- [ ] **CONTINUE-13**: AbstractTimeBasedRule 处理 previousTruncatedUnitStartTime
- [ ] **CONTINUE-14**: 第一个单元开始时间正确调整
- [ ] **CONTINUE-15**: mergedFromPrevious 标志正确设置

### CONTINUE-07: 文档更新
- [ ] **CONTINUE-16**: README.md 包含新增字段说明
- [ ] **CONTINUE-17**: README_CN.md 包含新增字段说明

### CONTINUE-08: 测试验证
- [ ] **CONTINUE-18**: ContinueModeTest 通过
- [ ] **CONTINUE-19**: 截断单元合并场景金额正确（无重复收费）

---

## Out of Scope

- 计算位置与查询位置区别设计 — 后续独立 TODO
- 其他计费模式问题 — 本次仅 CONTINUE 模式
- UI/前端 — 后端纯计算引擎
- 性能优化 — 功能修复优先

---

## Traceability

| REQ-ID | Phase | Status |
|--------|-------|--------|
| CONTINUE-01 ~ CONTINUE-19 | 1 | Pending |

---

*Last updated: 2026-03-30*