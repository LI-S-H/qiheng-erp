# 本地图规划前后端联调

启动类为 `com.qiheng.erp.ai.dev.GraphUiDatabaseIntegrationApplication`，只存在于测试源码，不打入生产包。旧 `GraphUiIntegrationApplication.main` 委托到此启动器，其场景模型与登录端点继续复用。

它运行真实 HTTP Controller、GraphService、Mapper、MySQL 事务、节点记忆、生产三个 Node、官方 RedisSaver 和真实 Sa-Token 登录（包含现有权限提供器），只有模型输出采用确定性测试场景。没有 SQL、Python 或订单执行能力；工作台返回明确的空数据。

先准备本地隔离 MySQL `127.0.0.1:13316/erp` 和 Redis `127.0.0.1:16380` DB0。默认 MySQL 账号 root、测试口令 `ai-integration-only`，只用于独立测试容器；不要连接业务数据库。将 `docs/database/sql/006_mvp_ai.sql` 与 `018_ai_node_memory.sql` 导入该测试数据库。启动器不清空 Redis，不读取生产 `application.yml` 或私有模型密钥配置。

完成 Maven `test-compile`，并生成包含测试依赖的 classpath 后，通过 Java21 执行：

```powershell
java -cp "erp-ai/target/test-classes;erp-ai/target/classes;<依赖classpath>" com.qiheng.erp.ai.dev.GraphUiDatabaseIntegrationApplication
```

工作目录为 `erp-server`；classpath 中需要包含 `erp-common`、`erp-system`、`erp-security` 编译输出及其依赖，以及项目使用的 MySQL Connector/J 驱动。使用 Maven `-pl erp-ai dependency:build-classpath -Dmdep.includeScope=test` 生成依赖，不沿用旧 admin classpath。Windows Java @argfile 内的路径使用正斜线，避免反斜线被转义。端口默认 `18082`，仅监听 `127.0.0.1`，没有 `/api` context path。Vite 以环境变量 `VITE_PROXY_TARGET=http://127.0.0.1:18082` 启动，将 `/api` 前缀移除后代理到后端。

必要时可以用启动参数覆盖本地端口和 Redis 参数，例如 `--server.port=18082 --spring.data.redis.port=16380 --spring.data.redis.database=0`。请勿将此测试服务暴露到公网。

测试登录账号和密码均为 `graph-test`。登录后 `/auth/me`、`/ai/graph/stream`、`/ai/graph/resume/stream` 必须携带真实返回的 `satoken`；不要把实际 Token 写入说明或截图。

联调路径：

1. 提交“分析补货需求”，返回周期单选题和仓库/商品范围开放题。
2. 提交第一轮回答，返回补货目标或限制的开放题。
3. 提交第二轮回答，返回包含实际需求及回答的 SQL/Python 两步计划。
4. 提交“统计一号仓库最近30天各商品销量”，直接返回有效计划。
5. 可验证未登录请求、过期 pauseId、结束后的重复提交均由真实认证或图服务拒绝。

场景按当前任务提示词中的问答记录判断轮次，不以进程全局调用次数决定结果。关闭和重新启动测试服务后，已有图检查点仍由 Redis 保存；是否仍登录由真实 Sa-Token 会话决定。

回归命令（在 `erp-web` 下）：

- `npm run test:ai-graph`：受控接口浏览器回归，不依赖本启动器。
- `npm run test:ai-graph-real`：真实后端接口和数据库断言，要求上述测试容器，当前脚本使用容器名 `erp-ai-it-20261016`。
- `node scripts/test-ai-graph-real-browser.cjs`：真实浏览器联调，默认访问 Vite `127.0.0.1:5174`，通过 `AI_GRAPH_REAL_UI_URL` 覆盖；截图目录可由 `AI_GRAPH_SCREENSHOT_DIR` 指定。

受控异常关键词：`触发模型异常`、`触发格式修复`、`触发格式耗尽`、`慢任务`。测试不会调用外部模型；这些用例验证执行协议，不验证模型业务规划质量。
