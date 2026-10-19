# 工作台正式落地与字体统一

实际页面：[工作台](http://127.0.0.1:9071/dashboard)。需要正常登录；连接现有开发后端。独立预览仍保留用于对照。

## 显示与交互

- 首屏按业务处理排序：统一白底核心指标、常用入口、分类待办与订单状态；下方保留经营趋势、库存、商品和供应商信息。
- 待办改为右侧详情抽屉，保留已有字段、依据记录、处理指引、权限与原业务入口；商品详情采用金额排行。供应商按综合评分排序、同分并列，综合评分在概览最右列以绿色显示，排名和供应商身份保持在左侧，中间依次展示质量评分和平均交期，详情右上角突出；概览另保留供应商身份、质量评分与平均交期，详情保留交期/质量/价格/服务四项评分和评分依据金额。无有效综合分不参与排名，0分仍为有效评分。
- 经营趋势四项汇总居中。坐标轴使用实际布局尺寸绘制，增加横轴日期密度；桌面7天完整显示，较长周期及窄屏按可用宽度分布，保留首末日期。
- 趋势提示随鼠标移动，显示定位线与系列数据点，支持键盘；日期横列、采购/销售/毛利纵行的数值表默认展开。
- 页内读取继续使用系统局部加载层，保留旧内容、操作锁和淡出；路由切换保留原全页过渡。遵循系统减少动态效果设置。

## 字体

全局改用自托管 `Noto Sans SC Variable`，通过 `@fontsource-variable/noto-sans-sc` 的 Unicode 分片加载，无运行时字体 CDN。正文、表格、表单、弹窗与图表继承统一字体；保留代码等宽字体和现有14px表格基准。取消旧字体按钮向下2px的专用修正，改为上下对称留白。OFL 许可证位于 `erp-web/public/fonts/NotoSansSC-LICENSE.txt`。

## 数据边界与验证入口

未改变 Dashboard API、DTO、接口金额单位、状态机、后端和会话数据缓存。业务待办金额仍按分换算，空值与0分开显示；库存量按产品精度和单位呈现。测试使用受控HTTP响应及只读真实页面，不向实际业务提交数据。

- `scripts/smoke-dashboard.cjs`：转调当前工作台布局专项 `smoke-dashboard-pilot.cjs`，替代旧布局断言。
- `scripts/smoke-dashboard-inventory-status.cjs`：产品精度、长文字统一Tooltip、空态和权限降级。
- `scripts/test-dashboard-session.cjs`：并发合并、节流、失败保留、换账号及旧请求隔离。
- `scripts/smoke-font-readability.cjs`：实际中文字形、表头与数据字号及居中、弹窗、手机、助手和本地字体请求。

浏览器证据分别位于 `docs/qa-screenshots/2026-10-19-dashboard-pilot/` 和 `docs/qa-screenshots/2026-10-19-noto-font/`；执行结果以对应 `result.json` 与 `_logs/` 本轮日志为准。

本轮结果：工作台15组、字体5组、助手9组、铃铛、库存、公共表格专项及会话状态单测通过；OpenAPI/退货契约、类型检查和生产构建通过。真实后端工作台只读截图见 `actual-dashboard-1440.png` 与 `actual-trend-1440.png`。最新供应商与头像调整专项见 `_logs/supplier-polish-browser.txt` 和 `_logs/ai-avatar-polish-browser.txt`。

排行的概览表格整行及详情卡片提供浅灰蓝悬停背景，详情入口按钮两侧至少12px留白，并兼容减少动态效果。助手头像使用蓝色助手、绿色用户，分组标题与会话名称同为13px；本轮按用户补充要求保持计划卡片原样。

待办列表的左右留白由每个行按钮承载，列表容器不再保留横向padding，悬停与键盘聚焦背景可覆盖面板完整内宽；图标和文字仍保留22/17/14px响应式留白。综合评分最终按用户要求位于概览最右列，详情仍在右上角。

最新工作台16组通过，包含1440/1920/1115/390下待办两侧真实鼠标悬停和综合评分最右列断言，截图为 `todo-hover-full-row.png`、`ranking-overview.png`。契约、类型与构建通过；完整交付本轮止于公共表格宽屏固定列阴影断言（`smoke-shared-ui.cjs:190`），详见 `_logs/supplier-last-todo-hover-delivery.txt`，工作台独立结果见 `_logs/supplier-last-todo-hover-browser.txt`。

`test:delivery` 未全绿：已通过契约、构建、共享组件、登录、工作台和库存，停在 `smoke-system-users.cjs` 调用 `smoke-helpers.cjs:502` 的旧汇总样式断言。该断言要求无容器边框、每格有阴影/边框/10px圆角；此前已迁移用户页使用统一带边框条带与无阴影分格。本轮未为此改回已认可的用户页外观，也未放宽旧断言。完整日志为 `_logs/dashboard-font-delivery.txt`。
