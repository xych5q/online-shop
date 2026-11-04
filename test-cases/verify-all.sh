#!/usr/bin/env bash
# ============================================================================
# 在线购物系统 · 测试执行统一入口（verify-all）
# 一条命令跑完并汇总：
#   [1/4] cd server && mvn -B clean test          —— JUnit 全量单测
#   [2/4] 用干净库起服务（8090 端口，data-verify 独立数据目录）
#   [3/4] 依次跑 api-smoke-iter2.sh 与 admin-api-demo.sh
#   [4/4] 打印汇总表；任何一步失败退出码为 1，结束时关闭服务进程
#
# 用法（在仓库根目录执行）：
#   bash test-cases/verify-all.sh
# 可选环境变量：
#   PORT=8090      服务端口
#   SKIP_UNIT=1    跳过步骤 1（只做冒烟，须先自行打包出 server/target/online-shop.jar）
#
# 纪律：
#   - 不修改 api-smoke.sh / admin-api-demo.sh / api-smoke-iter2.sh 原件，只在入口调用
#   - 服务数据落在 server/data-verify/，每次运行前清空，不碰开发库
#   - 验收现场一条命令复现；嵇宇锋 J-5 录屏统一用本入口
# ============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8090}"
BASE="http://localhost:${PORT}"
DATA_URL="jdbc:h2:file:./data-verify/onlineshop;DB_CLOSE_ON_EXIT=FALSE"
UPLOAD_DIR="./data-verify/uploads"
JAR="$ROOT/server/target/online-shop.jar"
SRV_LOG="$ROOT/server/verify-server.log"
MVN_LOG="$ROOT/server/verify-mvn.log"

SRV_PID=""
UNIT_TOTAL=""; UNIT_FAIL=""; UNIT_OK=0
SRV_OK=0
P1_NAME="api-smoke-iter2.sh"; P1_PASS=""; P1_FAIL=""; P1_OK=0
P2_NAME="admin-api-demo.sh";  P2_PASS=""; P2_FAIL=""; P2_OK=0

cleanup() {
  if [ -n "$SRV_PID" ] && kill -0 "$SRV_PID" 2>/dev/null; then
    kill "$SRV_PID" 2>/dev/null
    wait "$SRV_PID" 2>/dev/null
    echo "[verify-all] 服务进程已关闭 (pid=$SRV_PID)"
  fi
}
trap cleanup EXIT INT TERM

# ---------- [1/4] JUnit 全量单测 ----------
if [ "${SKIP_UNIT:-0}" != "1" ]; then
  echo "==> [1/4] cd server && mvn -B clean test"
  ( cd "$ROOT/server" && mvn -B clean test ) > "$MVN_LOG" 2>&1
  unit_rc=$?
  agg="$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures' "$MVN_LOG" | tail -1)"
  UNIT_TOTAL="$(printf '%s' "$agg" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')"
  UNIT_FAIL="$(printf '%s' "$agg" | sed -E 's/.*Failures: ([0-9]+).*/\1/')"
  if [ "$unit_rc" -eq 0 ] && grep -q 'BUILD SUCCESS' "$MVN_LOG" && [ "${UNIT_FAIL:-1}" = "0" ]; then
    UNIT_OK=1
    echo "    Tests run: $UNIT_TOTAL, Failures: $UNIT_FAIL —— 通过"
  else
    echo "    Tests run: ${UNIT_TOTAL:-?}, Failures: ${UNIT_FAIL:-?} —— 失败（详见 $MVN_LOG）"
  fi

  # 单测不产 jar，补一次跳测试打包供步骤 2 起服务
  ( cd "$ROOT/server" && mvn -B -q -DskipTests package ) || { echo "[verify-all] 打包失败"; exit 1; }
else
  echo "==> [1/4] 跳过（SKIP_UNIT=1），沿用已有测试结论"
  UNIT_OK=1; UNIT_TOTAL="SKIP"; UNIT_FAIL="SKIP"
fi

[ -f "$JAR" ] || { echo "[verify-all] 找不到 $JAR，请先打包"; exit 1; }

# ---------- [2/4] 干净库起服务 ----------
echo "==> [2/4] 干净库起服务（端口 $PORT，数据目录 server/data-verify/）"
rm -rf "$ROOT/server/data-verify"
cd "$ROOT/server"
java -jar target/online-shop.jar \
  --server.port="$PORT" \
  --spring.datasource.url="$DATA_URL" \
  --app.upload.dir="$UPLOAD_DIR" > "$SRV_LOG" 2>&1 &
SRV_PID=$!

srv_ready=0
for _ in $(seq 1 60); do
  sleep 1
  if curl -sf -o /dev/null "$BASE/api/product"; then srv_ready=1; break; fi
  kill -0 "$SRV_PID" 2>/dev/null || break
done
if [ "$srv_ready" -eq 1 ]; then
  SRV_OK=1
  echo "    服务就绪：$BASE（pid=$SRV_PID）"
else
  echo "    服务 60 秒内未就绪或启动失败（详见 $SRV_LOG）"
fi

# ---------- [3/4] 接口脚本 ----------
run_smoke() { # $1=脚本名  $2=结果变量前缀
  local script="$1" pre="$2" out rc pass fail
  if [ "$SRV_OK" -ne 1 ]; then
    eval "${pre}_PASS=\"-\"; ${pre}_FAIL=\"-\""; return
  fi
  if [ ! -f "$ROOT/test-cases/$script" ]; then
    echo "    [缺失] $script 不存在（develop 上尚未合入），记为失败"
    eval "${pre}_PASS=\"缺失\"; ${pre}_FAIL=\"缺失\""; return
  fi
  out="$( cd "$ROOT/test-cases" && BASE="$BASE" bash "$script" 2>&1 )"
  rc=$?
  pass="$(printf '%s\n' "$out" | grep -oE '通过 [0-9]+ 项' | tail -1 | grep -oE '[0-9]+')"
  fail="$(printf '%s\n' "$out" | grep -oE '失败 [0-9]+ 项' | tail -1 | grep -oE '[0-9]+')"
  eval "${pre}_PASS=\"${pass:-?}\"; ${pre}_FAIL=\"${fail:-?}\""
  if [ "$rc" -eq 0 ]; then
    eval "${pre}_OK=1"
    echo "    $script：通过 ${pass:-?} / 失败 ${fail:-?} —— 通过"
  else
    printf '%s\n' "$out" | tail -20
    echo "    $script：通过 ${pass:-?} / 失败 ${fail:-?} —— 失败"
  fi
}

echo "==> [3/4] 依次跑接口脚本（BASE=$BASE）"
run_smoke "$P1_NAME" "P1"
run_smoke "$P2_NAME" "P2"

# ---------- [4/4] 汇总 ----------
overall=0
[ "$UNIT_OK" -eq 1 ] || overall=1
[ "$SRV_OK"  -eq 1 ] || overall=1
[ "$P1_OK"   -eq 1 ] || overall=1
[ "$P2_OK"   -eq 1 ] || overall=1

echo ""
echo "=============================================================="
echo " verify-all 汇总"
echo "--------------------------------------------------------------"
printf ' %-4s %-28s %s\n' "步骤" "内容" "结果"
printf ' %-4s %-28s %s\n' "[1]" "JUnit 全量 mvn clean test" "Tests run: $UNIT_TOTAL, 失败 $UNIT_FAIL $([ "$UNIT_OK" -eq 1 ] && echo '√' || echo '×')"
printf ' %-4s %-28s %s\n' "[2]" "干净库起服务($PORT)" "$([ "$SRV_OK" -eq 1 ] && echo '就绪 √' || echo '未就绪 ×')"
printf ' %-4s %-28s %s\n' "[3]" "$P1_NAME" "通过 ${P1_PASS} / 失败 ${P1_FAIL} $([ "$P1_OK" -eq 1 ] && echo '√' || echo '×')"
printf ' %-4s %-28s %s\n' "[3]" "$P2_NAME" "通过 ${P2_PASS} / 失败 ${P2_FAIL} $([ "$P2_OK" -eq 1 ] && echo '√' || echo '×')"
echo "--------------------------------------------------------------"
if [ "$overall" -eq 0 ]; then
  echo " 结果：全部通过"
else
  echo " 结果：存在失败步骤，退出码 1"
fi
echo "=============================================================="
exit "$overall"
