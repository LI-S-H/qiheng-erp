# 前端开发规范

前端位于 `erp-web/`，使用 Vue 3、TypeScript、Vite、Pinia 和项目内组件；依赖和可用脚本以 `package.json` 与锁文件为准。

## 开发流程

- 改页面前先阅读对应模块的 API 适配、类型、已有页面与公共组件；需要接口或字段变更时同步核对后端和 [OpenAPI](../api/erp-openapi.yaml)。
- 分类、部门等树形数据使用稳定 ID，前端展示名称或路径；商品按上级分类筛选的范围由后端解析。BIGINT ID 按字符串处理，避免 JavaScript 精度丢失。
- 接口适配层负责识别响应类型和异常；表单只提交用户可修改的字段。业务权限、状态和并发仍由后端校验。
- 复用已有组件、加载与错误提示、无障碍标签和响应式样式；模拟 API 仅用于开发和测试，不作为生产接口的证明。
- 修改前按范围执行 `npm run preflight:frontend -- <scope>`；可用范围见 `erp-web/scripts/frontend-preflight.cjs`。共享代码使用 `shared`，范围不明使用 `all`。预检读取当前数据库结构、原位置的前端规范及 OpenAPI。
- 修改后按影响运行 `npm run build` 和相关测试。测试模式、环境和限制见 [前端测试](../testing/frontend.md)。

详细约定和视觉规范暂保留在 [前端开发规范](../frontend-development-guide.md)与[前端样式规范](../frontend-style-guide.md)，以免破坏现有预检脚本。表结构以当前初始化 SQL 和数据库结构说明为准。
