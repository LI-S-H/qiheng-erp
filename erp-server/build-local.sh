#!/usr/bin/env bash
# 构建后端全部依赖模块并安装到本地仓库，供本地启动使用。
set -euo pipefail
cd "$(dirname "$0")"
MVN=${MVN:-mvn}
if ! command -v "$MVN" >/dev/null 2>&1; then
  echo "未找到 mvn，可通过 MVN=/path/to/mvn 指定 Maven 可执行文件。" >&2
  exit 1
fi
"$MVN" -DskipTests -pl erp-admin -am install
echo "BUILD_DONE"