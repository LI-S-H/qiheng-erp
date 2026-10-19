# ERP 业务工作台方案

这轮将工作台从“经营图表居前”改为“业务处理居前”。经用户认可后，已迁移到实际 Dashboard，落地范围和验证见[实际工作台记录](../2026-10-19-dashboard-pilot/README.md)。本目录保留独立设计预览作为对照；实际项目另统一使用本地 Noto Sans SC 字体。

- 当前前端：<http://127.0.0.1:9071/dashboard>，需要正常登录。
- 新版设计：<http://127.0.0.1:9075/>，示例数据，无需登录；也可直接打开同目录 `preview.html`。
- 构建：`node docs/design/2026-10-19-erp-workbench/build.cjs`。
- 核验：`node docs/design/2026-10-19-erp-workbench/qa.cjs`。
- 启动预览：`node docs/design/2026-10-19-erp-workbench/serve.cjs`。

## 与上一版的实质区别

| 区域 | 上一版 | 新版 |
| --- | --- | --- |
| 首屏 | 大幅经营趋势在主位、待办窄列 | 业务待办为主区、订单状态侧栏 |
| 待办 | 全部类型纵向排列 | 分类标签、原始单据摘要、完整标题、数量与优先级 |
| 操作入口 | 主要依赖侧栏导航 | 常用业务入口横条，同时保留侧栏 |
| 订单状态 | 页面下方柱形图 | 首屏采购/销售切换、六种当前状态与数量 |
| 待办详情 | 居中弹窗 | 右侧抽屉、紧凑两列事实、依据记录、处理指引与固定底栏 |
| 数据分析 | 主导首屏 | 保留趋势、毛利率、库存状态、商品排行与供应商完整评分，按次序展开 |
| 核心指标 | 四个独立彩色卡片 | 同一白底容器、细分隔线、统一排版，颜色仅用于语义区分 |

## 设计参考

- [Arco Design Pro 工作台](https://vue-pro.arco.design/dashboard/workplace)：主次分栏、紧凑信息区、常用入口。核对了[官方页面结构](https://github.com/arco-design/arco-design-pro-vue/tree/main/arco-design-pro-vite/src/views/dashboard/workplace)，未引入其代码或商标素材。
- [ERPNext / Frappe Workspace](https://docs.frappe.io/framework/user/en/desk/navigation/workspace)：数字指标、快捷入口、图表与短业务列表的组合。
- [ERPNext Selling](https://docs.frappe.io/erpnext/selling)：业务入口与销售趋势的组合，将信息连接到原业务页。

项目实际依赖是 Vue + shadcn-vue / Reka UI + Tailwind，未安装 Arco。正式落地继续复用已有组件，不更换全局组件库。

## 接口与数据映射

`build.cjs` 原样提取 `erp-web/src/modules/dashboard/api.ts` 中已有 `mockOverview` / `mockInventoryStatus`，不伪造新接口，不调用后端，不写业务数据。

| 展示内容 | 现有来源 | 规则 |
| --- | --- | --- |
| 指标、同比/环比 | `GET /dashboard/overview` 的 `metrics`、`access.metrics` | 保留空值、无可比基线、权限及负数 |
| 业务待办 | `todos` / `detail.model` / `detail.items` | 先按 sortWeight；按 businessLabel 分类；事项数与类别数明确区分 |
| 单据详情 | documentNo、sourceDocumentNo、counterpartyName、amountFen、waitHours、waitLevel、documentStatus | 仅 amountFen 除以100；空金额显示—；原始来源与等待信息完整保留 |
| 库存待复核 | productCode、productName、warehouseName、unitName、availableQty、safetyStockQty、suggestedPurchaseQty、severity | 建议采购量为0时保留0及单位 |
| 系统异常 | exceptionNo、summary、exceptionType、sourceModule、occurredAt、severity | TRACKED只读，不提供完成或跳转执行按钮 |
| 订单状态 | orderStages / orderStagePeriod / orderStagePermissions | 本月新建订单当前状态分布，包含取消，不当成漏斗/转化率 |
| 经营趋势 | trend / trendPermissions | 原日期、7/15/30、负值坐标、每日数值；日期为列、销售/采购/毛利为行，数值矩阵默认展开；区间毛利率=毛利合计÷销售合计，销售>0才计算 |
| 库存四态 | `GET /dashboard/inventory-status?warehouseId` 的 distribution | 按产品与仓库记录统计；与风险SKU指标不混算、不推导健康率 |
| 风险明细 | inventoryStatus.riskPreview | 展示编码、仓库、库存与单位；不从列表推导完整仓库分布 |
| 商品/供应商 | topProducts / supplierPerformance | 首屏5条，完整排行抽屉；商品按销售额配比较条、销量和可用库存；供应商按交期评分排序、同分并列、未评分不排名；所有评分、平均交期与依据金额保留；null/NOT_READY不变成0分 |

抽屉中的“前往处理”沿用实际页面的 `TODO_NAVIGATION` 路由和 status/from/riskOnly 参数，打开当前前端业务页。预览自身不执行审核、确认或数据变更。

## 预览边界与验证

- 页面从现有固定示例提取数据，示例更新时间与本月订单周期可能不同；原示例存在零库存统计与风险记录、负毛利与status等不一致，未人为改写或推导业务结论。
- 仓库选择仅过滤已有风险记录。因没有每仓独立示例分布，单仓时明确提示需读取对应接口，避免展示虚假分布；实际落地继续使用当前RemoteSearchSelect和warehouseId请求。
- 正式接入保持overview共享store、权限裁剪、刷新节流、失败保留旧数据、通知和路由todoId联动，不以本预览脚本替代这些逻辑。
- 浏览器覆盖1440/1920/1115/390、8类明细、金额与零值、分类/周期/状态切换、完整排行、权限空态、键盘、减弱动画与刷新锁；16组通过，无页面脚本异常、无网络请求。
- 本轮文件仅位于 `docs/design/`，未改前端源码、契约或全局样式；运行预览构建及对应真实浏览器检查，不重复运行与本预览无关的全量业务测试。

视觉产物：`workbench-1440.png`、`workbench-full.png`、`detail-order.png`、`detail-system.png`、`detail-stock.png`、`detail-mobile.png`、`trend-hover.png`、`detail-products.png`、`detail-suppliers.png`、`detail-products-mobile.png`、`detail-suppliers-mobile.png`。主文字14px，辅助文字桌面12px、手机不低于11px；侧栏15px；统一白底指标，无强辉光。

## 2026-10-19 本轮交互与详情修复

- 趋势坐标原来使用固定 600×230 的 SVG 再非等比拉伸，导致字形比例不一致。现在按容器实际像素绘图，字体、字号与界面保持一致，ResizeObserver 同步响应式尺寸。
- 提示卡原来只有固定位置，索引也按整个容器宽度映射。现在按绘图区计算日期，跟随指针并避让边缘，同时显示对应日期的竖虚线和三条线圆点；方向键也能定位，移出/失焦即清理。
- 每日金额以日期横列、指标纵行默认显示；首列固定，7/15/30 天完整数值可横向滚动，元单位、两位小数和负值保持准确。
- 商品抽屉使用名次、完整商品名称与编码、销售额、销量/可用库存和相对销售额轨道，替换整块大表格。
- 供应商抽屉突出交期主分与名次，四项独立评分并排呈现，交期和评分依据金额置于底部，保留全部字段；列表和详情共用排序与名次规则。
- 额外回归：Tooltip 在同一日期内也跟随鼠标，首尾边界避让，三线圆点坐标与原始数据一致；默认数值表固定首列；手机两种排行抽屉无溢出；供应商同分与 NOT_READY/null 不误排名、不变成 0 分。
