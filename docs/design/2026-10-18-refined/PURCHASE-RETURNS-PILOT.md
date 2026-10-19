# 采购退回外观初版

采购订单已获用户认可，本次按同一标准只推进下一页 `/purchase/returns`，交由用户查看初版，不批量替换其它页面。

## 范围

- 复用已认可的浅灰侧栏、白色工作区、四格汇总、36px控件、14px表头和主数据、12px辅助信息、64px记录行与单个状态标签。
- 表头及数据居中，并使用真实文本 Range 测量列宽；保留九列、五筛选、首尾固定列、固定表头及表格内部滚动。页面铺满主工作区，宽屏余量均匀分配。
- 新增、编辑、详情、审核数量、日期、远程搜索、二次确认和取消原因统一使用同一视觉规则。正文滚动、底部操作、权限、校验及二层 inert 保护保留。
- 共享退货页仅 `PURCHASE_RETURN` 条件启用新外观；销售退货仍保留原布局。采购订单只增加语义样式标记，避免依赖其列序号。
- `PromptDialog` 增加可选遮罩样式，未传入时完全使用原默认值。采购退回取消原因弹窗使用新主题的低透明遮罩及无模糊背景。
- 未修改 API、DTO、Mock、权限、状态动作、数量规则或业务文案；只去掉已被单个状态标签完整表达的重复提示。

## 查看

[打开采购退回](http://127.0.0.1:5192/purchase/returns)。本机服务使用项目 Mock，账号 `admin`，密码 `123456`。新增、编辑验证仅发生在独立浏览器 Mock 会话中。

截图与结果位于 `docs/qa-screenshots/2026-10-18-purchase-return-style/`：

- [列表初版](../../qa-screenshots/2026-10-18-purchase-return-style/returns-1440x900.png)
- [新增](../../qa-screenshots/2026-10-18-purchase-return-style/returns-create.png)
- [详情](../../qa-screenshots/2026-10-18-purchase-return-style/returns-detail.png)
- [取消原因](../../qa-screenshots/2026-10-18-purchase-return-style/returns-cancel-reason.png)
- [浏览器结果](../../qa-screenshots/2026-10-18-purchase-return-style/results.json)

## 验证

- `purchase shared` 前端预检已执行，规则同步后已刷新凭证。
- `npm run check:openapi`、`npm run build` 通过；后续交付命令再次通过契约、类型和构建。
- `node scripts/smoke-purchase-returns-style.cjs` 8组通过：1440/1115/390/1920四视口、真实字形居中、字号、列宽、满宽、状态单标签、固定列与表头、查询锁、原五筛选、远程搜索及互斥、空态、刷新与每页条数、编辑预填及保存回显、详情内容、审核数量、二次确认和取消原因 inert、新增必填/日期/数量/搜索及Mock保存、低高度和手机弹窗、路由切换动效、销售退货隔离、已认可采购订单样式。无页面脚本错误。
- `node scripts/smoke-purchase-style.cjs` 原采购订单完整9组回归通过；退回最终专项又检查了订单字号与居中。
- `npm run test:delivery` 已执行：契约、类型、构建、共享列表和 Tooltip 通过；在未修改的 `/warehouse/inbound-bills` 公共表格测试中，`smoke-shared-ui.cjs:113` 的固定列 hover 阴影断言失败，全量交付未通过。本轮不扩大到入库单修复。
- 既有退回旧 smoke 仍使用此前“更多操作菜单”和无固定列的验收方式，已与当前页面不符；本轮用新专项验证当前真实入口，保留旧脚本未改。

初版等待用户对外观提出反馈；尚未将其它业务页切换为新风格。

后续共同修正：采购订单、采购退回和供应商管理统一改为每页10条完整展开，20/50条才启用限高内部滚动，见[三页分页高度修复](./PAGINATION-SCROLL.md)。此前首屏分页的历史验证不再作为10条模式的验收条件。
