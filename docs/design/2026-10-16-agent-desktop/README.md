# 云仓 ERP 桌面工作区设计提案

本目录包含独立设计原型与审阅证据，不修改生产业务页面，也不接业务数据库。

## 打开演示

在仓库根目录运行：

```powershell
node docs/design/2026-10-16-agent-desktop/serve.cjs
```

打开 [本地交互演示](http://127.0.0.1:5188/docs/design/2026-10-16-agent-desktop/)。服务仅监听本机；端口已占用时先检查是否本演示正在运行，不结束其他项目的进程。

演示引用仓库已有的 Logo 和 HarmonyOS Sans 字体，建议通过上述服务打开。无需安装额外依赖或启动 ERP 后端。

## 交付物

- [优化方案.md](优化方案.md)：现状问题、截图、参考来源、布局、字体配色、实施优先级、后端影响与验收条件。
- `index.html`、`prototype.css`、`prototype.js`：可点击原型。
- `icons.js`、`ICONS-LICENSE.txt`：从仓库已有 Lucide 包提取的图标及许可，`build-icons.cjs` 可重新生成。
- `evidence/`：本轮当前页面与公开产品参考截图；`evidence/prototype/` 为原型验证截图。
- [QA.md](QA.md)：实际交互、视口与独立审查结果，含验证限制。
- `qa-prototype.cjs`：原型浏览器验证脚本，依赖仓库 `erp-web/node_modules/playwright` 和已安装的 Edge。

## 推荐体验路径

工作台 → 采购订单（筛选、分页、详情）→ 请 AI 解读 → 发送需求 → 确认分析范围 → 生成计划 → 打开计划画布 → 查看依据 / 导出计划。

也可从库存管理制定分析计划，在方案画布点击布局分区，在设计规范切换密度和状态。`Ctrl+K` 仅用于导航，不是跨业务全文搜索。

用户消息在右侧，带浅蓝气泡；AI 回复和澄清在左侧。全部数字为演示值，AI 为固定交互模拟。计划不执行 SQL/Python，不自动创建或审核单据。
