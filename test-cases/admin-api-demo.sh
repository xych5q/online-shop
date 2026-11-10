#!/usr/bin/env bash
# ============================================================================
# 卖家后台管理 · 手动演示脚本（评审用）
# 用 curl 真实打 6 个新接口：类别增删改 / 改挂类别 / 调库存 / 上下架
#
# 用法：
#   1) 起服务（独立数据目录，不污染开发数据）
#      cd server
#      java -jar target/online-shop.jar --server.port=8090 \
#           --spring.datasource.url="jdbc:h2:file:./data-demo/onlineshop;DB_CLOSE_ON_EXIT=FALSE" \
#           --app.upload.dir=./data-demo/uploads
#   2) 跑本脚本
#      cd test-cases && BASE=http://localhost:8090 bash admin-api-demo.sh
#
# 环境变量：
#   BASE        服务地址，默认 http://localhost:8090
#   SELLER_PASS 卖家初始密码，默认 seller123
# ============================================================================

BASE="${BASE:-http://localhost:8090}"
SELLER_PASS="${SELLER_PASS:-seller123}"

CODE=""
BODY=""
PASS=0
FAIL=0

# call <方法> <路径> <token> <body>
# 请求体经 UTF-8 临时文件传参（Windows Git Bash 直接 -d 中文会乱码）
call() {
  local method="$1" path="$2" token="$3" body="$4" resp bodyfile=""
  local -a args=(-s --noproxy '*' -w $'\n%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "X-Token: $token")
  if [ -n "$body" ]; then
    bodyfile=$(mktemp ./demo-body.XXXXXX)
    printf '%s' "$body" > "$bodyfile"
    args+=(-H 'Content-Type: application/json' -d "@$bodyfile")
  fi
  resp=$(curl "${args[@]}")
  rm -f "$bodyfile"
  CODE="${resp##*$'\n'}"
  BODY="${resp%$'\n'*}"
}

expect() { # expect <编号> <描述> <期望状态码> [响应必须包含的字符串]
  local id="$1" desc="$2" want="$3" wantstr="$4"
  if [ "$CODE" = "$want" ] && { [ -z "$wantstr" ] || printf '%s' "$BODY" | grep -qF -- "$wantstr"; }; then
    PASS=$((PASS + 1)); printf '  [\033[32mPASS\033[0m] %-7s %s\n' "$id" "$desc"
  else
    FAIL=$((FAIL + 1)); printf '  [\033[31mFAIL\033[0m] %-7s %s\n' "$id" "$desc"
    printf '           期望 %s%s，实际 HTTP %s，响应：%s\n' "$want" "$wantstr" "$CODE" "$BODY"
  fi
}

json_str() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
json_num() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\([0-9]\{1,\}\).*/\1/p"; }

echo "=============================================================="
echo " 卖家后台管理 · 手动演示（6 个新接口逐条真实请求）"
echo " 服务地址： $BASE"
echo "=============================================================="

# ---------- 登录 ----------
call POST /api/seller/login "" "{\"username\":\"admin\",\"password\":\"$SELLER_PASS\"}"
expect AD-01 "卖家登录" 200 '"token"'
TOKEN=$(json_str token)

# ---------- 类别管理 ----------
call POST /api/seller/categories "$TOKEN" '{"name":"图书"}'
expect AD-02 "新增一级类别「图书」" 200 '"ok":true'
ROOT_ID=$(json_num id)

call POST /api/seller/categories "$TOKEN" "{\"name\":\"小说\",\"parentId\":$ROOT_ID}"
expect AD-03 "新增二级类别「小说」" 200 '"ok":true'
CHILD_ID=$(json_num id)

call POST /api/seller/categories "$TOKEN" '{"name":"图书"}'
expect AD-04 "同级重名被拒" 400 '同级类别名称已存在'

call PUT "/api/seller/categories/$CHILD_ID" "$TOKEN" '{"name":"文学"}'
expect AD-05 "改名「小说」→「文学」" 200 '"ok":true'

# ---------- 发布商品（挂二级类别，不传图） ----------
call POST /api/seller/products "$TOKEN" "{\"name\":\"演示商品\",\"description\":\"演示\",\"price\":19.9,\"stock\":3,\"categoryId\":$CHILD_ID}"
expect AD-06 "发布商品（挂二级类别、无图）" 200 '"ok":true'
PID=$(curl -s --noproxy '*' "$BASE/api/products" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')

# ---------- 改挂类别 ----------
call POST /api/seller/categories "$TOKEN" "{\"name\":\"工具书\",\"parentId\":$ROOT_ID}"
expect AD-07 "新增第二个二级类别「工具书」" 200 '"ok":true'
OTHER_ID=$(json_num id)

call PUT "/api/seller/products/$PID/category" "$TOKEN" "{\"categoryId\":$OTHER_ID}"
expect AD-08 "商品改挂到「工具书」" 200 '"ok":true'

call PUT "/api/seller/products/$PID/category" "$TOKEN" "{\"categoryId\":$ROOT_ID}"
expect AD-09 "挂一级类别被拒" 400 '商品必须挂到二级类别'

# ---------- 手动调库存 ----------
call PUT "/api/seller/products/$PID/stock" "$TOKEN" '{"stock":20,"reason":"补货"}'
expect AD-10 "调库存 3→20（写流水）" 200 '"stock":20'

call PUT "/api/seller/products/$PID/stock" "$TOKEN" '{"stock":-1}'
expect AD-11 "负库存被拒" 400 '库存不能为负数'

call PUT "/api/seller/products/$PID/stock" "$TOKEN" '{"stock":0}'
expect AD-12 "调 0 自动转售罄" 200 '"status":"sold_out"'

call PUT "/api/seller/products/$PID/stock" "$TOKEN" '{"stock":5,"reason":"补货"}'
expect AD-13 "补货 5 自动回在售" 200 '"status":"on_sale"'

# ---------- 上下架 ----------
call POST "/api/seller/products/$PID/onshelf" "$TOKEN" ""
expect AD-14 "在售商品上架被拒" 400 '仅「已下架」商品可上架'

call POST "/api/seller/products/$PID/offshelf" "$TOKEN" ""
expect AD-15 "手动下架" 200 '"ok":true'

call POST "/api/seller/products/$PID/onshelf" "$TOKEN" ""
expect AD-16 "下架后重新上架" 200 '"ok":true'

# ---------- 删除类别（有商品不可删） ----------
call DELETE "/api/seller/categories/$OTHER_ID" "$TOKEN" ""
expect AD-17 "挂着商品的类别不可删" 400 '该类别下还有商品，不能删除'

call PUT "/api/seller/products/$PID/category" "$TOKEN" "{\"categoryId\":$CHILD_ID}"
call DELETE "/api/seller/categories/$OTHER_ID" "$TOKEN" ""
expect AD-18 "改挂后原类别可删" 200 '"ok":true'

# ---------- 汇总 ----------
echo "=============================================================="
echo " 通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -eq 0 ]; then
  echo " 结果：全部通过"
else
  echo " 结果：存在失败，请检查上方红色行"
fi
echo "=============================================================="
