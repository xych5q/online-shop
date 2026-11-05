#!/usr/bin/env bash
# 一键构建：清理 -> 编译 -> 单元测试 -> 打包
# 用法：bash build/build.sh（在仓库根目录执行）
# 注意：Maven 工程在 server/ 子目录，pom.xml 不在仓库根目录，故脚本内部切到 server/。
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/server"

echo "==> 工作目录：$(pwd)"
echo "==> 1/4 clean"
mvn -B clean

echo "==> 2/4 compile"
mvn -B compile

echo "==> 3/4 test"
mvn -B test

echo "==> 4/4 package（测试已在第 3 步跑过，此处跳过以免重复执行）"
mvn -B package -DskipTests

echo "==> 构建完成，产物："
ls -lh target/*.jar
