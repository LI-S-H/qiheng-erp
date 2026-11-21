# 配置与敏感信息

以下只列配置名称和用途，实际值应通过环境变量或目标环境私有配置提供；不要提交真实值。配置优先级以 Spring Boot、Compose 和具体部署脚本为准。

| 范围 | 配置示例 | 用途 |
|---|---|---|
| 前端 | `VITE_PROXY_TARGET` | 本地 Vite 代理的后端地址 |
| 后端 | `SPRING_PROFILES_ACTIVE` | 选择运行配置 |
| 数据库 | `MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD` | 应用连接业务数据库 |
| Redis | `REDIS_PASSWORD` | Redis 认证；数据库序列与应用环境要对应 |
| 发布 | `DEPLOY_REPO`、`DEPLOY_BRANCH`、`GITCODE_USERNAME` | 受控部署源和分支；令牌通过独立私有文件提供 |
| AI | `ERP_AI_ENABLED`、`ERP_AI_SQL_USERNAME`、`ERP_AI_SQL_PASSWORD`、`DEEPSEEK_API_KEY` | AI 开关、只读数据库账号与模型凭据 |
| 容器 | `DOCKER_SOCKET_GID` | Docker Socket 授权组；仅适用于已评估风险的部署 |

容器实际配置见 `deploy/docker/.env.example`、`compose.infra.yaml`、`compose.app.yaml` 和 `application-container.yaml`。模板中的示例值不应当作真实环境凭据；对外发布前检查文件、截图、日志和数据库导出。发现已泄露凭据时，先撤销或轮换，再单独评估 Git 历史清理。
