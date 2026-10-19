# 供货产品外观初版

用户认可前三页及10条展开规则后，本轮仅修改供货产品 `/purchase/supplier-products`，沿用已确认标准。已完成 `purchase shared` 预检，先同步风格规范，再刷新凭证并修改源码。

[打开实际页面](http://127.0.0.1:5192/purchase/supplier-products)，本机使用项目 Mock，账号 `admin`，密码 `123456`。新增、编辑和报价保存验证仅在 Mock 会话执行，不代表真实后端验收。

## 外观与保留范围

- 满宽白底、浅色导航、四格汇总；主表表头与数据同为14px并居中，辅助信息12px，行高64px，控件36px。
- 九个基本筛选、五组范围条件及十三列（含勾选）完整保留。真实字形宽度决定列宽基数，剩余空间均分；列隐藏、恢复与偏好持久化保持。
- 勾选列与操作列固定；用户自选固定列保留原位置语义。实际列宽变化后重算固定偏移，即使整表总宽不变也更新。
- 每页10条完整展开；20/50条沿用表格内部限高与固定表头，切回10条清除旧纵向滚动位置。低高度窗口可滚动主工作区访问分页。
- 搜索下拉、普通选择、更多菜单、列设置、新增/编辑、详情、报价、评分记录和二次确认统一表面、间距与柔和焦点。保留全部原字段、说明、评分信息、校验和业务动作；未修改 API、DTO 或 Mock。
- 新主题仅在四条采购试点路由启用；其他路由沿用原外观与通知逻辑。

## 验证与证据

供货产品专项10组通过，覆盖1440/1115/390/1920视口、真实字形居中、满宽、固定列、相邻与非相邻自选固定、列隐藏持久化、10→20→50→10、筛选与远程搜索、空态、确认取消、详情内容、新增/编辑回显、报价必填与保存、低高度及移动弹窗、页面动效和主题隔离。浏览器无页面脚本错误。独立只读审查通过。

前三页完整专项回归通过：采购订单9组、采购退回8组、供应商9组。全量 `npm run test:delivery` 的契约、类型、构建、共享列表和 Tooltip 通过；在未修改的入库页 `smoke-shared-ui.cjs:113` 固定列悬停阴影断言中断，不宣称全量通过。日志：`_logs/supplier-product-style-delivery.txt`。

- [整页](../../qa-screenshots/2026-10-18-supplier-product-style/products-overview.png)
- [列设置](../../qa-screenshots/2026-10-18-supplier-product-style/products-columns.png)
- [新增](../../qa-screenshots/2026-10-18-supplier-product-style/products-create.png)
- [详情](../../qa-screenshots/2026-10-18-supplier-product-style/products-detail.png)
- [调整报价](../../qa-screenshots/2026-10-18-supplier-product-style/products-quote.png)
- [评分记录](../../qa-screenshots/2026-10-18-supplier-product-style/products-score-log.png)
- [专项结果](../../qa-screenshots/2026-10-18-supplier-product-style/results.json)
