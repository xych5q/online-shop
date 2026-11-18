#!/usr/bin/env bash
# ============================================================================
# 在线购物系统 · Docker 镜像一键构建 + 部署验证
# 第 1 组（组长：陈星宇） | 迭代 2 · 挑战需求 A（tag v0.2-iter2）
#
# 用途：在有 Docker 的机器上一键完成「构建镜像 → 起 MySQL → 起应用容器 → 等就绪 → 跑接口冒烟」，
#       并把产物 tar 导出作验收材料。全过程结果写屏，可直接截图作为部署证据。
#
# 迭代 3（J3-0）：数据库切 MySQL，本脚本起 mysql:8.0 容器与应用容器组同一网络，
#       app 通过容器名连接，不依赖宿主机任何 MySQL 实例；凭据为演示用固定值。
#
# 用法（仓库根目录执行）：
#   bash deploy/docker-verify.sh
# 可选环境变量：
#   IMAGE_TAG   镜像标签，默认 online-shop:0.2
#   HOST_PORT   宿主机映射端口，默认 8080
#   OUT_DIR     镜像 tar 输出目录，默认 ./dist
#   TAR_NAME    导出的 tar 文件名，默认 online-shop-0.2-iter2.tar
#
# 依赖：docker、curl。Windows 下用 Git Bash 执行。
# ============================================================================

set -u
IMAGE_TAG="${IMAGE_TAG:-online-shop:0.2}"
CONTAINER_NAME="${CONTAINER_NAME:-online-shop-verify}"
HOST_PORT="${HOST_PORT:-8080}"
OUT_DIR="${OUT_DIR:-./dist}"
TAR_NAME="${TAR_NAME:-online-shop-0.2-iter2.tar}"
SMOKE="test-cases/api-smoke-iter2.sh"

# 迭代 3（J3-0）：宿主机跑单测需要连本机 MySQL，凭据走环境变量（DB_PASSWORD 默认空）
export DB_USER="${DB_USER:-root}"
export DB_PASSWORD="${DB_PASSWORD:-}"

ok()   { printf '  [\033[32m OK \033[0m] %s\n' "$1"; }
info() { printf '  [....] %s\n' "$1"; }
die()  { printf '  [\033[31mFAIL\033[0m] %s\n' "$1"; exit 1; }

command -v docker >/dev/null 2>&1 || die "本机没有 docker 命令，请先安装 Docker Desktop 并启动"
command -v curl   >/dev/null 2>&1 || die "本机没有 curl 命令"

# 定位仓库根目录（本脚本在 deploy/ 下）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || die "进入仓库根目录失败：$ROOT"

echo "=============================================================="
echo " 在线购物系统 · Docker 部署验证"
echo " 镜像标签：$IMAGE_TAG   映射端口：$HOST_PORT"
echo "=============================================================="

# ---------- 0. 先跑一遍单测，保证进镜像的代码是绿的 ----------
if command -v mvn >/dev/null 2>&1; then
  info "先在宿主机跑 JUnit 单测（77 条）..."
  if (cd server && mvn -B -q test) >/tmp/_mvntest.log 2>&1; then
    ok "单测通过，继续构建镜像"
  else
    echo "---- mvn test 输出尾部 ----"; tail -20 /tmp/_mvntest.log; echo "--------------------------"
    die "单测未通过，拒绝构建镜像（先把代码修绿）"
  fi
else
  info "未检测到 mvn，跳过本地单测（镜像构建阶段也不跑测试）"
fi

# ---------- 1. 构建镜像 ----------
info "构建镜像：$IMAGE_TAG（首次会从 Maven 中央仓拉依赖，约 2 至 5 分钟）"
BUILD_START=$(date +%s)
docker build -f deploy/Dockerfile -t "$IMAGE_TAG" . 2>&1 | tail -20
BUILD_RC=${PIPESTATUS[0]}
BUILD_END=$(date +%s)
[ "$BUILD_RC" -eq 0 ] || die "镜像构建失败"
ok "镜像构建成功，耗时 $((BUILD_END - BUILD_START)) 秒"

IMAGE_SIZE=$(docker image inspect "$IMAGE_TAG" --format '{{.Size}}' 2>/dev/null)
if [ -n "$IMAGE_SIZE" ]; then
  printf '  [INFO] 镜像体积：%s 字节（约 %s MB）\n' "$IMAGE_SIZE" "$((IMAGE_SIZE / 1024 / 1024))"
fi
docker images "$IMAGE_TAG"

# ---------- 2. 起 MySQL 与应用容器 ----------
info "起 MySQL 容器（shop-verify-net 网络内，服务名 shop-mysql）"
docker network create shop-verify-net >/dev/null 2>&1 || true
docker rm -f shop-mysql-verify "$CONTAINER_NAME" >/dev/null 2>&1
docker run -d --name shop-mysql-verify --network shop-verify-net \
  -e TZ=Asia/Shanghai \
  -e MYSQL_ROOT_PASSWORD=root_pwd \
  -e MYSQL_ROOT_HOST='%' \
  -e MYSQL_DATABASE=onlineshop \
  mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci >/dev/null \
  || die "MySQL 容器启动失败"

info "等待 MySQL 就绪（最多 120 秒）"
MYSQL_READY=0
for i in $(seq 1 60); do
  if docker exec shop-mysql-verify mysqladmin ping -h 127.0.0.1 -uroot -proot_pwd --silent >/dev/null 2>&1; then
    MYSQL_READY=1; break
  fi
  sleep 2
done
[ "$MYSQL_READY" -eq 1 ] || { docker logs --tail 30 shop-mysql-verify; die "MySQL 未在 120 秒内就绪"; }
ok "MySQL 已就绪"

info "启动应用容器（先清理同名旧容器）"
docker run -d --name "$CONTAINER_NAME" --network shop-verify-net -p "$HOST_PORT":8080 \
  -e TZ=Asia/Shanghai \
  -e DB_URL="jdbc:mysql://shop-mysql:3306/onlineshop?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true" \
  -e DB_USER=root \
  -e DB_PASSWORD=root_pwd \
  "$IMAGE_TAG" >/dev/null || die "容器启动失败"
ok "容器已启动：$CONTAINER_NAME"

# ---------- 3. 等就绪 ----------
info "等待服务就绪（最多 60 秒）"
READY=0
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://localhost:$HOST_PORT/api/products" 2>/dev/null)
  [ "$CODE" = "200" ] && { READY=1; break; }
  sleep 1
done
[ "$READY" -eq 1 ] || { docker logs --tail 30 "$CONTAINER_NAME"; die "服务未在 60 秒内就绪"; }
ok "服务已就绪：http://localhost:$HOST_PORT （启动耗时约 ${i} 秒）"

# ---------- 4. 冒烟 ----------
info "对容器实例跑接口冒烟测试"
if [ -f "$SMOKE" ]; then
  BASE="http://localhost:$HOST_PORT" bash "$SMOKE"
  SMOKE_RC=$?
else
  echo "  [WARN] 未找到 $SMOKE，跳过冒烟"
  SMOKE_RC=0
fi

# ---------- 5. 导出镜像 ----------
info "导出镜像到 $OUT_DIR"
mkdir -p "$OUT_DIR"
TAR="$OUT_DIR/$TAR_NAME"
docker save "$IMAGE_TAG" -o "$TAR" && ok "镜像已导出：$TAR（$(wc -c < "$TAR" 2>/dev/null | tr -d ' ') 字节）"

echo "=============================================================="
if [ "${SMOKE_RC:-0}" -eq 0 ]; then
  echo " 部署验证通过。容器仍在运行，可浏览器访问："
  echo "   买家首页  http://localhost:$HOST_PORT/"
  echo "   卖家后台  http://localhost:$HOST_PORT/admin"
  echo "   Navicat 看库  localhost:3306（容器网络内，未对外映射端口，可 docker exec 进容器查）"
  echo " 停止并清理：docker rm -f $CONTAINER_NAME shop-mysql-verify && docker network rm shop-verify-net"
  echo "=============================================================="
  exit 0
else
  echo " 镜像与部署本身没问题，但接口冒烟有失败项，请按上面输出修复。"
  echo "=============================================================="
  exit 1
fi
