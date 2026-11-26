#!/usr/bin/env bash
# 本地启动后端（开发配置 + 本机密钥）。
# 关键点：spring.config.import 的 ./.env.ai.yaml 按进程工作目录解析，必须固定在 erp-server。
# RocketMQ 在 dev 配置指向云端地址，本机有 namesrv 时改成本机地址可避免启动失败。
# 启动前先安装依赖模块：spring-boot:run 只编译 erp-admin，其他模块会取本地仓库的 jar。
# 不预构建时改动 erp-product 等模块仍会加载旧包。
set -euo pipefail
cd "$(dirname "$0")"
MVN=${MVN:-mvn}
if ! command -v "$MVN" >/dev/null 2>&1; then
  echo "未找到 mvn，可通过 MVN=/path/to/mvn 指定 Maven 可执行文件。" >&2
  exit 1
fi
if command -v cygpath >/dev/null 2>&1; then
  SERVER_DIR=$(cygpath -w "$(pwd)")
else
  SERVER_DIR=$(pwd)
fi
# 先构建并安装 erp-admin 及其依赖模块，保证运行的是当前源码。
"$MVN" -q -pl erp-admin -am -DskipTests install
exec "$MVN" -pl erp-admin spring-boot:run -Dspring-boot.run.profiles=dev \
  "-Dspring-boot.run.workingDirectory=${SERVER_DIR}" \
  "-Dspring-boot.run.arguments=--rocketmq.name-server=127.0.0.1:9876 ${SERVER_PORT_ARG:-}"