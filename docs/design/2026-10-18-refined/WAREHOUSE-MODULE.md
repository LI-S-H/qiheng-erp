# 仓储模块外观迁移

按仓库管理、库存管理、入库单、出库单、库存流水顺序逐页实现、核验，再整模块交付。沿用已认可的采购和销售视觉，保留原业务内容、字段、权限、API、状态流转与库存计算。

## 本机体验

Mock 服务账号 `admin`，密码 `123456`；测试中的保存仅影响当前浏览器 Mock 会话。

| 页面 | 入口 | 专项检查 | 真实截图 |
| --- | --- | --- | --- |
| 仓库管理 | [打开](http://127.0.0.1:5192/warehouse/warehouses) | 9组 | [整页](../../qa-screenshots/2026-10-18-warehouse-style/warehouses/overview.png) |
| 库存管理 | [打开](http://127.0.0.1:5192/warehouse/stocks) | 8组 | [筛选与列表](../../qa-screenshots/2026-10-18-warehouse-style/stocks/list-1440x900.png) |
| 入库单 | [打开](http://127.0.0.1:5192/warehouse/inbound-bills) | 9组 | [列设置](../../qa-screenshots/2026-10-18-warehouse-style/inbound-bills/columns.png)、[商品明细](../../qa-screenshots/2026-10-18-warehouse-style/inbound-bills/expanded.png) |
| 出库单 | [打开](http://127.0.0.1:5192/warehouse/outbound-bills) | 9组 | [明细](../../qa-screenshots/2026-10-18-warehouse-style/outbound-bills/expanded.png)、[新增](../../qa-screenshots/2026-10-18-warehouse-style/outbound-bills/create.png) |
| 库存流水 | [打开](http://127.0.0.1:5192/warehouse/stock-bills) | 8组 | [整页](../../qa-screenshots/2026-10-18-warehouse-style/stock-bills/overview.png)、[变动明细](../../qa-screenshots/2026-10-18-warehouse-style/stock-bills/expanded.png) |

## 统一标准及截图反馈修复

- 页面满宽，主列表表头与数据14px居中；实际文字宽度决定列宽基数，剩余空间均匀分配，保留原固定列与横向滚动。
- 普通筛选200px、短字段160px、业务对象240px，自然换行；查询与重置紧跟条件并底部对齐。风险库存预设和查询、重置作为一组，避免原来等分拉长和按钮漂到远处。
- 每页10条完整展开；20/50条才限制表格内部高度。分页保持原短防抖、请求隔离和状态锁，加载层即时出现、220ms淡出，减少动态效果时近即时。
- 商品与流水明细13px、表头和记录38px、标题栏36px、卡片最多1060px；窄屏内部横滚。主列表64px行高限定直系单元格，修复其误作用于嵌套明细导致过大的根因；弹窗产品明细同步13px/38px。
- 出入库直接复用供货产品的 `TableColumnPreferencesCanvas`，保留原可选列、方向隔离的记忆与恢复默认，以及列切换过渡。原首尾固定逻辑不变。
- 新增、编辑表单沿用16px标题、13px字段和控件、12px说明、36px输入、柔和焦点与浅色错误边框；数字框无原生上下箭头。详情、二次确认与底层 inert 保留。
- 仓库地址、联系人、电话、启停用和批量操作，库存风险与占用状态，出入库来源/质量/手工调整，以及流水正负变化、确认信息与只读限制全部保留。

## 验证与边界

`smoke-warehouse-style.cjs` 五页共43组通过，覆盖1440/1115/390/1920视口、实际文字居中、列宽、满宽、滚动和固定列、筛选、空态、刷新、分页、明细展开收起、列偏好、表单校验、Mock保存与确认取消。独立审阅已通过。

采购四页36组、销售三页27组回归通过。采购旧主题隔离场景改用尚未迁移且含分页的产品列表，避免继续误将已迁移库存页当作旧主题。12页加载过渡专项通过，覆盖即时加载、保留旧记录、分页锁、220ms淡出，以及有下一页时真实点击下一页后的记录切换；减少动态效果及离开新主题路由的隔离同样通过。并行浏览器时出现过采购加载断言时序失败，串行复测两页均通过，未修改产品业务逻辑或放宽通过条件。

契约检查、TypeScript类型检查和生产构建通过。全量 `test:delivery` 的契约、类型、构建、共享列表、Tooltip、公共表格、鉴权检查通过，随后在未修改的 `smoke-dashboard.cjs:78` 等待工作台加载遮罩达到不透明状态超时；不将全量门禁宣称为通过。视觉与交互采用项目 Mock，未连接生产后端执行库存写入。

详细日志位于 `_logs/warehouse-style-*.txt`；每页检查记录位于上述截图目录的 `results.json`。
