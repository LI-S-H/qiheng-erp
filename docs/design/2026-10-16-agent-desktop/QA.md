# 设计提案与交互原型验证

验证日期：2026-10-16。浏览器：本机 Edge，由项目 Playwright 驱动。原型地址：`http://127.0.0.1:5188/docs/design/2026-10-16-agent-desktop/`。

## 结果

独立原型主回归 **29 项检查通过**。补充检查通过：新会话清空旧条件、390px 固定单号列、可见辅助字号不低于 12px。桌面和窄屏截图已实际打开查看。

原型没有调用业务 API，也未提交真实单据或调用模型。结果不等同于生产业务验收、真实后端联调或完整 WCAG 合规认证。

## 可复现验证

先启动 `node serve.cjs`，再从任意目录执行本目录 `qa-prototype.cjs`。脚本使用仓库已有的 Playwright 和本机 Edge，不需要额外安装依赖。

| 验证 | 实际路径 / 断言 | 结果 |
| --- | --- | --- |
| 导航 | 工作台、采购、库存、智能助手、方案画布、设计规范逐个点击 | 通过 |
| 快速前往 | Ctrl+K → 输入“库存” → 匹配页面 → Esc 关闭 | 通过，仅导航 |
| 采购筛选 | 供应商“上海” AND 状态“待审核” → 所有命中行同时满足 | 通过 |
| 列表状态 | 重置筛选 → 下一页 → 上一页 | 通过 |
| 订单详情 | 点击单号 → 只读详情 → Esc → 焦点回到单号 | 通过 |
| 业务到 AI | 详情“请 AI 解读” → 输入框带演示单号与未连接真实单据说明 | 通过 |
| 用户消息 | 发送问题 → 用户消息右对齐，浅蓝气泡、圆角、宽度上限；AI 在左侧 | 通过 |
| 长文本 | 连续 180 个字母与 HTML 字样 → 换行、不执行 HTML、没有横向溢出 | 通过 |
| 范围澄清 | 选择华东中心仓，输入预算 50000 → 生成计划 | 通过 |
| 计划画布 | 查看计划 → 依据与边界 → 切回计划 | 通过；仅 SQL_QUERY / PYTHON_ANALYSIS 规划 |
| 本地导出 | 点击导出 → 实际下载 Markdown → 检查范围、预算、“尚未执行” | 通过 |
| 抽屉 | 1115px 打开画布时焦点在画布中、背景 inert；Esc 关闭后背景恢复 | 通过 |
| 窄屏画布 | 390px 打开/关闭计划画布 | 通过 |
| 方案画布 | 切 AI 模式 → 点结果区 → 说明变化；现状截图链接可访问 | 通过 |
| 密度 | 标准/紧凑切换 → 实测行高 52px / 44px | 通过 |
| 空错态 | 错误反馈 → 重试演示 → 加载 → 空状态 | 通过 |
| 响应布局 | 1440×900、1366×768、1115×838、390×844 | 已检查，无外层横向溢出；宽表局部横滚 |
| 移动导航 | 六页均从移动菜单进入 | 通过 |
| 新会话隔离 | 上轮华东仓/50000预算 → 新会话 → 全部仓库/空预算/未来30天 | 通过 |
| 固定单号 | 390px 表格横滚前后首列 x=21px | 通过；移动仅固定单号，操作列随中部滚动 |
| 字号下限 | 窄屏采购页可见元素 computed font-size | 最小 12px |
| 运行边界 | 全程监测 pageerror 与 `/api/` 请求 | 无未捕获异常，无业务 API 请求 |

主回归原始结果：[results.json](evidence/prototype/results.json)。补充结果：[supplemental.json](evidence/prototype/supplemental.json)。实际下载：[exported-plan.md](evidence/prototype/exported-plan.md)。

## 视觉证据

| 页面 | 桌面 | 窄屏 |
| --- | --- | --- |
| 工作台 | [1440](evidence/prototype/desktop-dashboard.png) | 已检查导航与溢出，未单列截图 |
| 采购 | [1440](evidence/prototype/desktop-purchase.png)、[1366](evidence/prototype/laptop-purchase.png)、[详情](evidence/prototype/desktop-order-detail.png) | [390](evidence/prototype/mobile-purchase.png) |
| 用户消息气泡 | [1440](evidence/prototype/desktop-user-bubble.png) | [390](evidence/prototype/mobile-user-bubble.png)、[长文本](evidence/prototype/mobile-long-user-bubble.png) |
| AI 计划 | [1440 并排画布](evidence/prototype/desktop-assistant-plan.png)、[1115 抽屉](evidence/prototype/tablet-plan-drawer.png) | [390 全宽画布](evidence/prototype/mobile-plan-drawer.png) |
| 方案画布 | [业务模式](evidence/prototype/desktop-canvas.png)、[AI 模式](evidence/prototype/desktop-canvas-ai.png) | [390](evidence/prototype/mobile-canvas.png) |
| 设计规范 | [1440](evidence/prototype/desktop-system.png) | [390](evidence/prototype/mobile-system.png) |

1366px 采购表头约 y=353.8；当前页面相同视口约 y=497.1，示例布局提前约 143px。两者展示列和摘要组织有所不同，此数值用于评估空间分配，不是同业务内容的性能基准。

## 独立审查及修正

独立审查 Agent 先确认流程覆盖，再对照现有代码核查报告事实，并实际查看桌面与窄屏原型截图。确认用户右侧气泡方向正确，整体布局无明显视觉阻塞问题。发现并修正：

- 窄屏计划画布补充焦点管理、背景 inert、Esc 和关闭后的焦点恢复；桌面保留普通并排区域。
- 采购状态改为现有“部分入库 / 已入库”，库存积压改为零库存，避免凭视觉提案发明业务枚举。
- 首页“采购申请”改“采购订单”；示例指标口径、历史对比、预计到货和动态流的后端需求在方案中单列。
- 新会话清空上轮时间、仓库与预算；明确演示历史刷新后清空。
- 用仓库已有 Lucide 官方图标节点替换原型自绘近似图标，附带许可。
- 截图等待短暂 toast 消失，避免通知遮挡底部动作。

最终独立复核已查看更新后的桌面计划、窄屏用户气泡和采购截图，逐项确认上述问题均已解决，**同意作为独立设计提案交付**。该结论不延伸为生产全功能验收。

机械设计检查曾因缺少 htmlparser2/css-tree 等模块退化为正则扫描，只指出计划说明的左色条；已去掉该色条。该扫描不具备完整计算样式或对比度校验能力，未将其描述为完整可访问性通过。

## 当前项目基线

- 前端 `all` 预检通过；没有修改生产业务文件。
- `npm run check:openapi` 通过。
- `npm run build` 通过；第三方 VueUse 注释位置警告未阻塞构建。
- 现有 AI 受控浏览器回归通过，覆盖提交锁、澄清、草稿恢复、失败保留、历史计划与响应式布局；没有调用模型或业务库。
- 真实开发前端登录请求返回 500；现状页面截图使用已有开发 Mock，未将该环境称为真实业务联调。

## 后续仍需验证

正式实现后，应重新验证真实角色权限、订单长明细及写操作、出入库确认、退货精度与来源、树选择、慢网与 401、AI 流式中断恢复、多会话并发、字体冷缓存加载、最终色彩对比度及完整键盘/读屏流程。预览没有接管原有 ERP 服务；本轮临时 5186 Mock 服务已停止，5188 独立演示服务保留供查看。
