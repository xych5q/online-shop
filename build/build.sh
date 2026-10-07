#!/usr/bin/env bash
# 一键构建：清理 -> 编译 -> 单元测试 -> 打包
# 用法：bash build/build.sh
set -e

echo "==> 1/4 clean"
mvn -B clean

echo "==> 2/4 compile"
mvn -B compile

echo "==> 3/4 test"
mvn -B test

echo "==> 4/4 package"
mvn -B package

echo "==> 构建完成，产物：target/*.jar"
ls -lh target/*.jar
