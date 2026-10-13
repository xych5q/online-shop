#!/usr/bin/env bash
# ============================================================================
# 在线购物系统 · 接口冒烟测试脚本
# 第 1 组（组长：陈星宇） | 对应 test-cases/测试用例清单.md 第二节 TC-I01 ~ TC-I26
#
# 用法：
#   1) 先起服务（建议用干净的数据目录，见下）
#      cd server
#      java -jar target/online-shop.jar --server.port=8090 \
#           --spring.datasource.url="jdbc:h2:file:./data-smoke/onlineshop;DB_CLOSE_ON_EXIT=FALSE" \
#           --app.upload.dir=./data-smoke/uploads
#   2) 回到仓库根目录执行本脚本
#      cd test-cases && BASE=http://localhost:8090 bash api-smoke.sh
#
# 环境变量：
#   BASE      服务地址，默认 http://localhost:8090
#   SELLER_USER  卖家账号，默认 admin（不用 USERNAME，避免与 Windows 环境变量冲突）
#   SELLER_PASS  卖家初始密码，默认 seller123（application.yml 的 app.seller.password）
#
# 说明：脚本自带状态清理（若检测到遗留商品会先标记成功下架），因此在有脏数据的
#       实例上也能跑通，但商品 id 会不连续属正常现象。
# 退出码：全部通过 0，存在失败 1。
# ============================================================================

BASE="${BASE:-http://localhost:8090}"
SELLER_USER="${SELLER_USER:-admin}"
SELLER_PASS="${SELLER_PASS:-seller123}"

CODE=""
BODY=""
PASS=0
FAIL=0
FAILED_IDS=""

# ---------- 工具函数 ----------
# 响应 body 与状态码用换行符分隔一次性取回，不落临时文件（跨平台更干净）
call() { # call <方法> <路径> <token> <body>
  local method="$1" path="$2" token="$3" body="$4" resp
  local -a args=(-s --noproxy '*' -w $'\n%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "X-Token: $token")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  resp=$(curl "${args[@]}")
  CODE="${resp##*$'\n'}"
  BODY="${resp%$'\n'*}"
}

expect() { # expect <编号> <描述> <期望状态码> [响应必须包含的字符串]
  local id="$1" desc="$2" want="$3" wantstr="$4"
  local detail=""
  [ -n "$wantstr" ] && detail=" 且响应含 \"$wantstr\""
  if [ "$CODE" = "$want" ] && { [ -z "$wantstr" ] || printf '%s' "$BODY" | grep -qF -- "$wantstr"; }; then
    PASS=$((PASS + 1))
    printf '  [\033[32mPASS\033[0m] %-7s %s\n' "$id" "$desc"
  else
    FAIL=$((FAIL + 1))
    FAILED_IDS="$FAILED_IDS $id"
    printf '  [\033[31mFAIL\033[0m] %-7s %s\n' "$id" "$desc"
    printf '           期望 %s%s，实际 HTTP %s，响应：%s\n' "$want" "$detail" "$CODE" "$BODY"
  fi
}

json_str() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
json_num() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\([0-9.-]\{1,\}\).*/\1/p"; }

# ---------- 状态清理：把遗留商品处理掉，保证能发布新品 ----------
cleanup_state() {
  local token="$1" pid
  call GET /api/seller/overview "$token" ""
  pid=$(printf '%s' "$BODY" | sed -n 's/.*"detail":{"product":{"id":\([0-9]*\).*/\1/p')
  [ -z "$pid" ] && return 0
  # 交易中的先标记成功；普通下架的直接下架
  call POST "/api/seller/products/$pid/deal/result" "$token" '{"result":"success"}'
  if [ "$CODE" != "200" ]; then
    call POST "/api/seller/products/$pid/offshelf" "$token" ""
  fi
}

echo "=============================================================="
echo " 在线购物系统 · 接口冒烟测试"
echo " 服务地址： $BASE     账号： $SELLER_USER"
echo "=============================================================="

# ---------- TC-I01 ~ I04：登录与鉴权 ----------
call POST /api/seller/login "" "{\"username\":\"$SELLER_USER\",\"password\":\"$SELLER_PASS\"}"
expect TC-I01 "正确账密登录成功" 200 '"token"'
TOKEN=$(json_str token)

call POST /api/seller/login "" '{"username":"admin","password":"wrong-password"}'
expect TC-I02 "错误密码登录被拒" 401 '用户名或密码错误'

call GET /api/seller/overview "" ""
expect TC-I03 "不带 token 访问后台被拒" 401 '未登录或登录已失效'

cleanup_state "$TOKEN"
call GET /api/seller/overview "$TOKEN" ""
expect TC-I04 "后台总览可读（无在售商品）" 200 '"canPublish":true'

# ---------- TC-I05 ~ I08：发布与商品查询 ----------
# 注意：负价格校验必须先于成功发布执行。单品约束（已有在售商品）的检查优先于
# 价格校验，一旦先发布了商品，负价格用例会先被单品约束拦下，拿不到价格错误信息。
call POST /api/seller/products "$TOKEN" '{"name":"负价格","description":"x","price":-1}'
expect TC-I06 "负价格发布被拒" 400 '价格必须为大于 0 的数字'

call POST /api/seller/products "$TOKEN" '{"name":"冒烟测试商品","description":"接口冒烟","price":19.9}'
expect TC-I05 "发布商品成功" 200 '"ok":true'

call POST /api/seller/products "$TOKEN" '{"name":"重复发布","description":"x","price":29.9}'
expect TC-I07 "已有在售商品再次发布被拒" 400 '已有商品在售或交易中，需先下架'

call GET /api/product "" ""
expect TC-I08 "买家查询在售商品" 200 '"status":"on_sale"'
PID=$(printf '%s' "$BODY" | sed -n 's/.*"product":{"id":\([0-9]*\).*/\1/p')
if printf '%s' "$BODY" | grep -q '"code"'; then
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I08b"
  echo '  [FAIL] TC-I08b /api/product 不得泄露口令码'
else
  PASS=$((PASS + 1)); echo '  [PASS] TC-I08b /api/product 未泄露口令码'
fi

# ---------- TC-I09 ~ I13：买家侧 ----------
call POST /api/intents "" '{"name":"张三","phone":"13800000001"}'
expect TC-I09 "买家 1 提交意向拿到口令码" 200 '"code"'
CODE1=$(json_str code)
call POST /api/intents "" '{"name":"李四","phone":"13800000002"}'
CODE2=$(json_str code)
call POST /api/intents "" '{"name":"王五","phone":"13800000003"}'
CODE3=$(json_str code)
if [ -n "$CODE1" ] && [ -n "$CODE2" ] && [ -n "$CODE3" ]; then
  PASS=$((PASS + 1)); echo "  [PASS] TC-I09b 3 人依次入队拿到不同口令码：$CODE1 $CODE2 $CODE3"
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I09b"
  echo '  [FAIL] TC-I09b 3 人入队未全部拿到口令码'
fi

call POST /api/intents "" '{"name":"   ","phone":"13800000009"}'
expect TC-I10 "姓名为空提交被拒" 400 '姓名与联系电话均为必填'

call GET "/api/intents/lookup/$CODE1" "" ""
expect TC-I11 "凭有效口令码查到位次 1" 200 '"position":1'
call GET "/api/intents/lookup/$CODE2" "" ""
POS2=$(json_num position)
if [ "$POS2" = "2" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-I11b 第二个买家位次为 2'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I11b"
  echo "  [FAIL] TC-I11b 第二个买家位次应为 2，实际 $POS2"
fi

call GET /api/intents/lookup/NOTEXIST "" ""
expect TC-I12 "无效口令码查询返回 404" 404 '口令码无效或已失效'

call PUT "/api/intents/lookup/$CODE2" "" '{"name":"李四","phone":"13900000009"}'
expect TC-I13 "凭码修改电话成功" 200 '"ok":true'
call GET "/api/intents/lookup/$CODE2" "" ""
POS2B=$(json_num position)
PHONE2=$(json_str phone)
if [ "$POS2B" = "2" ] && [ "$PHONE2" = "13900000009" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-I13b 改电话后位次不变且信息已更新'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I13b"
  echo "  [FAIL] TC-I13b 改电话后位次或电话不符合预期（position=$POS2B phone=$PHONE2）"
fi

# ---------- TC-I14 ~ I18：冻结 / 解冻 / 交易 ----------
call POST "/api/seller/products/$PID/freeze" "$TOKEN" ""
expect TC-I14 "手动冻结成功" 200 '"ok":true'
call GET /api/seller/overview "$TOKEN" ""
expect TC-I14b "冻结后商品状态 frozen 且来源 manual" 200 '"frozenBy":"manual"'

call POST "/api/seller/products/$PID/unfreeze" "$TOKEN" ""
expect TC-I15 "手动解冻成功" 200 '"ok":true'

call POST "/api/seller/products/$PID/deal" "$TOKEN" ""
expect TC-I16 "与队首进入交易成功" 200 '"ok":true'
call GET /api/seller/overview "$TOKEN" ""
expect TC-I16b "进入交易后商品自动冻结(frozenBy=auto)" 200 '"frozenBy":"auto"'

call POST "/api/seller/products/$PID/unfreeze" "$TOKEN" ""
expect TC-I17 "交易中手动解冻被拒" 400 '交易中的商品不能手动解冻'

call GET "/api/intents/lookup/$CODE1" "" ""
expect TC-I18 "队首已进入交易（position 为 null）" 200 '"inTransaction":true'

call POST "/api/seller/products/$PID/deal/result" "$TOKEN" '{"result":"fail"}'
expect TC-I18b "标记交易失败成功" 200 '"ok":true'
call GET /api/seller/overview "$TOKEN" ""
if printf '%s' "$BODY" | grep -q '"pendingFailed":\[{"id"'; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-I18c 失败意向进入待处置列表'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I18c"
  echo '  [FAIL] TC-I18c 待处置列表中没有失败意向'
fi
FID=$(printf '%s' "$BODY" | sed -n 's/.*"pendingFailed":\[{"id":\([0-9]*\).*/\1/p')

# ---------- TC-I19 ~ I20：处置失败意向 ----------
call POST "/api/seller/intents/$FID/dispose" "$TOKEN" '{"action":"requeue"}'
expect TC-I19 "重新排队处置成功" 200 '"ok":true'
call GET "/api/intents/lookup/$CODE1" "" ""
if printf '%s' "$BODY" | grep -q '"status":"queued"'; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-I19b 重排后原口令码仍有效且回到排队中'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I19b"
  echo "  [FAIL] TC-I19b 重排后原口令码查询异常：$BODY"
fi

call POST "/api/seller/products/$PID/deal/result" "$TOKEN" '{"result":"fail"}'
call GET /api/seller/overview "$TOKEN" ""
FID2=$(printf '%s' "$BODY" | sed -n 's/.*"pendingFailed":\[{"id":\([0-9]*\).*/\1/p')
call POST "/api/seller/intents/$FID2/dispose" "$TOKEN" '{"action":"void"}'
expect TC-I20 "作废处置成功" 200 '"ok":true'

# ---------- TC-I21 ~ I23：标记成功与历史 ----------
call POST "/api/seller/products/$PID/deal/result" "$TOKEN" '{"result":"success"}'
expect TC-I21 "标记交易成功" 200 '"ok":true'
call GET /api/product "" ""
expect TC-I21b "成功后商品下架，买家端无商品" 200 '"product":null'

call GET /api/seller/history "$TOKEN" ""
expect TC-I22 "历史商品列表可读" 200 '"total"'

call GET "/api/seller/history/$PID" "$TOKEN" ""
expect TC-I23 "历史详情含意向流水" 200 '"intents":['
if printf '%s' "$BODY" | grep -q '"code"'; then
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I23b"
  echo '  [FAIL] TC-I23b 历史流水泄露了口令码'
else
  PASS=$((PASS + 1)); echo '  [PASS] TC-I23b 历史流水不含口令码'
fi

# ---------- TC-I24 ~ I25：修改密码 ----------
call POST /api/seller/password "$TOKEN" '{"oldPassword":"bad-password","newPassword":"Smoke@Test123"}'
expect TC-I24 "原密码错误改密被拒" 400 '原密码错误'
call POST /api/seller/password "$TOKEN" '{"oldPassword":"'"$SELLER_PASS"'","newPassword":"123"}'
expect TC-I24b "新密码不足 8 位被拒" 400 '新密码长度不能少于 8 位'

call POST /api/seller/password "$TOKEN" '{"oldPassword":"'"$SELLER_PASS"'","newPassword":"Smoke@Test123"}'
expect TC-I25 "合法改密成功" 200
call POST /api/seller/login "" '{"username":"'"$SELLER_USER"'","password":"Smoke@Test123"}'
expect TC-I25b "新密码可以登录" 200 '"token"'
NEWTOKEN=$(json_str token)
call POST /api/seller/login "" '{"username":"'"$SELLER_USER"'","password":"'"$SELLER_PASS"'"}'
expect TC-I25c "旧密码已失效" 401 '用户名或密码错误'
# 改回原密码，避免污染后续调试环境
call POST /api/seller/password "$NEWTOKEN" '{"oldPassword":"Smoke@Test123","newPassword":"'"$SELLER_PASS"'"}'
if [ "$CODE" = "200" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-I25d 已还原初始密码'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-I25d"
  echo "  [FAIL] TC-I25d 还原初始密码失败：$BODY"
fi
TOKEN=$(json_str token)
call GET /api/seller/overview "" ""
expect TC-I03b "改密后旧 token 失效" 401

# ---------- TC-I26：手动下架 ----------
call POST /api/seller/login "" "{\"username\":\"$SELLER_USER\",\"password\":\"$SELLER_PASS\"}"
TOKEN=$(json_str token)
call POST /api/seller/products "$TOKEN" '{"name":"下架测试商品","description":"x","price":9.9}'
expect TC-I26 "发布第二个商品成功" 200 '"ok":true'
PID2=$(curl -s --noproxy '*' "$BASE/api/product" | sed -n 's/.*"product":{"id":\([0-9]*\).*/\1/p')
call POST /api/intents "" '{"name":"买家甲","phone":"13700000001"}'
CODE_A=$(json_str code)
call POST "/api/seller/products/$PID2/offshelf" "$TOKEN" ""
expect TC-I26b "手动下架成功" 200 '"ok":true'
call GET "/api/intents/lookup/$CODE_A" "" ""
expect TC-I26c "下架后队列意向口令码全部失效" 404 '口令码无效或已失效'

# ---------- 汇总 ----------
echo "=============================================================="
echo " 通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -eq 0 ]; then
  echo " 结果：全部通过"
  echo "=============================================================="
  exit 0
else
  echo " 失败编号：$FAILED_IDS"
  echo "=============================================================="
  exit 1
fi
