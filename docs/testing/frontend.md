# 前端测试

在 `erp-web/` 执行：

```bash
npm ci
npm run build
npm run smoke:product-categories
```

`build` 执行 TypeScript 检查与生产构建；`smoke:product-categories` 是分类页面浏览器冒烟检查。其他脚本包括 `smoke:system-depts`、`smoke:purchase`、`smoke:sales` 和 `smoke:warehouse-stocks`，完整列表以 `package.json` 为准。

现有冒烟辅助程序使用 Playwright，并配置 Windows 上的 Microsoft Edge 可执行文件；运行机器需要浏览器、依赖、端口及相应的测试环境。脚本可启动开发服务器，也可通过 `SMOKE_BASE_URL` 指向已有的测试服务；确认目标环境和测试数据后再运行。脚本可能创建截图或修改模拟数据。

`VITE_USE_MOCK_AUTH`、`VITE_USE_MOCK_API` 仅在开发模式下控制相应模块的模拟模式；并非所有模块都支持同一开关。模拟验证不能替代真实接口集成测试。

`npm run check:openapi` 用于检查接口契约及当前数据库结构；`check:current-schema` 核对初始化 SQL 和表结构说明，退货契约检查核对当前字段、索引及数量约束。这些静态检查不验证模拟数据内容；模拟数据须按[导入包说明](../database/demo-data/README.md)另行执行数据检查。分类页面冒烟测试已在模拟接口模式下通过；模拟模式不验证真实后端请求的加载提示和业务数据。执行前后应记录实际错误和环境，避免泄露测试数据。
