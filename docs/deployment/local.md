# 本地开发环境

适用于隔离开发或演示环境。后端需要 Java 21、Maven 和项目依赖的 MySQL、Redis、RocketMQ；前端需要 Node.js、npm。容器方式和镜像配置见 [Docker 部署](docker.md)。

1. 准备独立的数据库和基础服务，确认版本、连接和账号权限。空测试库可参考[初始化文件](../database/init/README.md)。不要连接真实业务库进行演示或集成测试。
2. 后端以 `erp-server/erp-admin` 为启动入口。在自己的私有环境配置中设置数据库、Redis、MQ 与按需启用的 AI 服务；不要把账号、密码和私有地址复制到公开文档。`application.yml` 默认启用 `dev` 配置，启动前应核实当前 profile 的实际连接目标，必要时通过环境变量或私有配置覆盖。
3. 在 `erp-server/` 构建启动模块及其依赖，之后启动生成的 JAR：

   ```bash
   mvn -pl erp-admin -am package -DskipTests
   java -jar erp-admin/target/erp-admin-1.0.0-SNAPSHOT.jar
   ```

   JAR 名称中的版本以当前 POM 和实际构建产物为准。打包时跳过测试不代表测试通过。主配置中的后端默认端口为 `8081`，实际端口以启动配置为准。
4. 在 `erp-web/` 执行 `npm ci` 和 `npm run dev`。Vite 默认端口为 `5173`，`VITE_PROXY_TARGET` 可指定后端地址；默认代理目标为本机 `8080`，若后端使用 `8081` 应显式调整。前端 `/api` 前缀由代理去除。
5. 用隔离测试账号验证登录及相关业务；如导入模拟数据，按[模拟数据包说明](../database/demo-data/README.md)检查数据库版本、导入、校验及 Redis 序列同步。

本指南不提供固定密码或实际服务器地址。启动成功仅表示进程可访问，不代表业务测试通过。
