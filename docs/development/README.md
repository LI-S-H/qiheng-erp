# 开发规范

面向当前代码的开发入口。项目为 Vue 3 + TypeScript 前端与 Java 21、Spring Boot 多模块后端；模块和依赖以各工程配置为准。

- [后端开发](backend.md)：模块边界、业务校验、接口和数据访问。
- [前端开发](frontend.md)：页面、接口适配、预检和测试。
- [数据库开发](database.md)：字段、迁移和模拟数据的处理边界。
- [接口开发](api.md)：契约、身份认证和兼容性。
- [Git 协作](git.md)：分支、提交和提交前检查。
- [测试入口](../testing/README.md)；[部署入口](../deployment/README.md)。

仓库中 [项目规范](../project-rules.md)、[后端规范](../backend-development-guide.md)、[前端规范](../frontend-development-guide.md)和[样式规范](../frontend-style-guide.md)仍保留在原位置；前端预检脚本会读取其中的前端规范与样式文件。规范需随代码和契约同步维护，文档整理不等于完成安全评审。
