#!/usr/bin/env bash
# ============================================================================
# 在线购物系统 · 接口冒烟测试脚本（迭代 2）
# 第 1 组（组长：陈星宇） | 对应《测试用例清单-迭代2》TC-A 段
#
# 编号说明：本脚本按 Wiki《接口设计.md》迭代二 27 个接口编写，TC-A 编号为
# 暂定编号；《测试用例清单-迭代2》交付后统一对齐编号，本脚本编号作为
# 对齐前的占位引用。迭代一口径的 TC-I01~I26 见 api-smoke.sh（保留不改动）。
#
# 与 api-smoke.sh（迭代一）的差异：
#   - 删除全部 8 处 /api/intents/lookup/{code} 调用——口令码三接口已随
#     A.2.2-6 废止（BuyerController:63-65 仅余废弃注释），调用必然 404
#   - /api/product 单数断言改为 /api/products 列表契约（items/page/pageSize/total）
#   - 新增迭代二覆盖：买家注册登录、登录态提交意向、首笔自动进交易（裁决 C11）、
#     卖家列表五状态筛选、成交扣件与售罄失效、买家历史三值、客户列表隐私等
#   - 保留原脚本两项设计：遗留商品清理逻辑、UTF-8 bodyfile 传中文请求体
#
# 用法：
#   1) 先起服务（建议用干净的数据目录，见下）
#      cd server
#      java -jar target/online-shop.jar --server.port=8090 \
#           --spring.datasource.url="jdbc:h2:file:./data-smoke/onlineshop;DB_CLOSE_ON_EXIT=FALSE" \
#           --app.upload.dir=./data-smoke/uploads
#   2) 回到仓库根目录执行本脚本
#      cd test-cases && BASE=http://localhost:8090 bash api-smoke-iter2.sh
#
# 环境变量：
#   BASE      服务地址，默认 http://localhost:8090
#   SELLER_USER  卖家账号，默认 admin（不用 USERNAME，避免与 Windows 环境变量冲突）
#   SELLER_PASS  卖家初始密码，默认 seller123（application.yml 的 app.seller.password）
#
# 说明：脚本自带状态清理（若检测到遗留商品会先标记成功下架），因此在有脏数据的
#       实例上也能跑通，但商品 id 会不连续属正常现象。买家账号带时间戳，
#       重复执行不会因用户名冲突失败。
# 退出码：全部通过 0，存在失败 1。
# ============================================================================

BASE="${BASE:-http://localhost:8090}"
SELLER_USER="${SELLER_USER:-admin}"
SELLER_PASS="${SELLER_PASS:-seller123}"
# 1x1 透明 PNG（多商品发布用例共用的合法图片 data URL）
IMG='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII='
# 买家账号后缀（用户名规则 ^[A-Za-z0-9_]{3,20}$，电话 11 位）
TS=$(date +%s)
TAIL="${TS: -6}"
U1="smka${TAIL}a"; U2="smka${TAIL}b"; U3="smka${TAIL}c"
P1="138${TS: -8}"; P2="139${TS: -8}"; P3="137${TS: -8}"

CODE=""
BODY=""
PASS=0
FAIL=0
FAILED_IDS=""

# ---------- 工具函数 ----------
# 响应 body 与状态码用换行符分隔一次性取回，不落临时文件（跨平台更干净）
call() { # call <方法> <路径> <token> <body>
  local method="$1" path="$2" token="$3" body="$4" resp bodyfile=""
  local -a args=(-s --noproxy '*' -w $'\n%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "X-Token: $token")
  if [ -n "$body" ]; then
    # Windows Git Bash 会把中文命令行参数按 ANSI(GBK) 传给原生 curl.exe，
    # 导致 JSON 成为非法 UTF-8；改用 UTF-8 临时文件 + -d @file 传请求体，保证中文不乱码
    bodyfile=$(mktemp ./smoke-body.XXXXXX)
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
# 多商品发布后可能残留多件在售/冻结商品：循环清理，直到 overview 无 detail 为止。
# 售罄（sold_out）残留不阻塞发布（canPublish=true）：deal/result 与 offshelf 均会
# 拒绝它，检测到同一商品处理不动时直接退出，不再空转。
cleanup_state() {
  local token="$1" pid last="" n=0
  while [ "$n" -lt 10 ]; do
    call GET /api/seller/overview "$token" ""
    pid=$(printf '%s' "$BODY" | sed -n 's/.*"detail":{"product":{"id":\([0-9]*\).*/\1/p')
    [ -z "$pid" ] && return 0
    [ "$pid" = "$last" ] && return 0
    # 交易中的先标记成功；普通下架的直接下架
    call POST "/api/seller/products/$pid/deal/result" "$token" '{"result":"success"}'
    if [ "$CODE" != "200" ]; then
      call POST "/api/seller/products/$pid/offshelf" "$token" ""
    fi
    last="$pid"
    n=$((n + 1))
  done
}

echo "=============================================================="
echo " 在线购物系统 · 接口冒烟测试（迭代 2 · TC-A）"
echo " 服务地址： $BASE     卖家账号： $SELLER_USER"
echo "=============================================================="

# ---------- TC-A01 ~ A04：卖家登录与鉴权 ----------
call POST /api/seller/login "" "{\"username\":\"$SELLER_USER\",\"password\":\"$SELLER_PASS\"}"
expect TC-A01 "卖家正确账密登录成功" 200 '"token"'
TOKEN=$(json_str token)

call POST /api/seller/login "" '{"username":"admin","password":"wrong-password"}'
expect TC-A02 "卖家错误密码登录被拒" 401 '用户名或密码错误'

call GET /api/seller/overview "" ""
expect TC-A03 "未登录访问卖家接口被拒" 401 '未登录或登录已失效'

cleanup_state "$TOKEN"
call GET /api/seller/overview "$TOKEN" ""
expect TC-A04 "后台总览可读且可发布（无在售商品）" 200 '"canPublish":true'

# ---------- TC-A05 ~ A08：类别管理（迭代二新增） ----------
call POST /api/seller/categories "$TOKEN" "{\"name\":\"冒烟一级$TAIL\"}"
expect TC-A05 "新建一级类别成功" 200 '"ok":true'
CAT1=$(json_num id)

call POST /api/seller/categories "$TOKEN" "{\"name\":\"冒烟二级$TAIL\",\"parentId\":$CAT1}"
expect TC-A06 "一级类别下新建二级类别成功" 200 '"ok":true'
CAT2=$(json_num id)

call POST /api/seller/categories "$TOKEN" "{\"name\":\"冒烟二级$TAIL\",\"parentId\":$CAT1}"
expect TC-A07 "同级重名类别被拒" 400 '同级类别名称已存在'

call DELETE "/api/seller/categories/$CAT1" "$TOKEN" ""
expect TC-A08 "删除含子类别的一级类别被拒" 400 '请先删除该类别下的子类别'

# ---------- TC-A09 ~ A16：发布与校验 ----------
# 发布必须挂二级类别（A.1.2-2）；用上面自建的二级类别跑通「建类别 → 发布」链路。
call POST /api/seller/products "$TOKEN" "{\"name\":\"负价格\",\"categoryId\":$CAT2,\"description\":\"x\",\"price\":-1,\"stock\":5,\"images\":[\"$IMG\"]}"
expect TC-A09 "负价格发布被拒" 400 '价格必须为大于 0 的数字'

call POST /api/seller/products "$TOKEN" "{\"name\":\"冒烟测试商品A\",\"categoryId\":$CAT2,\"description\":\"<p>接口冒烟</p>\",\"richDescription\":\"<b>富文本详情</b>\",\"price\":19.9,\"stock\":2,\"images\":[\"$IMG\",\"$IMG\"]}"
expect TC-A10 "发布商品成功（类别/库存/双图/富文本）" 200 '"ok":true'
PID1=$(json_num id)
if [ -z "$PID1" ]; then
  PID1=$(printf '%s' "$BODY" | sed -n 's/.*"product":{"id":\([0-9]*\).*/\1/p')
fi

call POST /api/seller/products "$TOKEN" '{"name":"缺类别","description":"x","price":19.9,"stock":5,"images":["'"$IMG"'"]}'
expect TC-A11 "类别缺失被拒" 400 '商品类别必填'

call POST /api/seller/products "$TOKEN" "{\"name\":\"类别不存在\",\"categoryId\":999,\"description\":\"x\",\"price\":19.9,\"stock\":5,\"images\":[\"$IMG\"]}"
expect TC-A12 "不存在的类别被拒" 400 '类别不存在'

call POST /api/seller/products "$TOKEN" "{\"name\":\"挂一级类别\",\"categoryId\":$CAT1,\"description\":\"x\",\"price\":19.9,\"stock\":5,\"images\":[\"$IMG\"]}"
expect TC-A13 "挂一级类别被拒" 400 '商品必须挂到二级类别'

call POST /api/seller/products "$TOKEN" "{\"name\":\"零库存\",\"categoryId\":$CAT2,\"description\":\"x\",\"price\":19.9,\"stock\":0,\"images\":[\"$IMG\"]}"
expect TC-A14 "库存为 0 被拒" 400 '库存必须大于或等于 1'

call POST /api/seller/products "$TOKEN" "{\"name\":\"负库存\",\"categoryId\":$CAT2,\"description\":\"x\",\"price\":19.9,\"stock\":-3,\"images\":[\"$IMG\"]}"
expect TC-A15 "库存为负被拒" 400 '库存必须大于或等于 1'

call POST /api/seller/products "$TOKEN" "{\"name\":\"十张图\",\"categoryId\":$CAT2,\"description\":\"x\",\"price\":19.9,\"stock\":5,\"images\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\"]}"
expect TC-A16 "图片超过 9 张被拒" 400 '图片数量不能超过 9 张'

# ---------- TC-A17 ~ A24：列表契约与五状态筛选（迭代二新增） ----------
call POST /api/seller/products "$TOKEN" "{\"name\":\"筛选下架商品\",\"categoryId\":$CAT2,\"description\":\"x\",\"price\":9.9,\"stock\":1,\"images\":[\"$IMG\"]}"
expect TC-A17 "发布第二个商品成功" 200 '"ok":true'
PID2=$(json_num id)
if [ -z "$PID2" ]; then
  PID2=$(printf '%s' "$BODY" | sed -n 's/.*"product":{"id":\([0-9]*\).*/\1/p')
fi

call GET "/api/seller/products?status=all" "$TOKEN" ""
expect TC-A18 "卖家列表 status=all 返回分页结构" 200 '"pageSize"'

call GET "/api/seller/products?status=on_sale" "$TOKEN" ""
expect TC-A19 "on_sale 筛选含新发布商品" 200 "\"id\":$PID1"

call POST "/api/seller/products/$PID1/freeze" "$TOKEN" ""
expect TC-A20 "手动冻结成功" 200 '"ok":true'

call GET "/api/seller/products?status=frozen" "$TOKEN" ""
expect TC-A21 "frozen 筛选含被冻结商品" 200 "\"id\":$PID1"

call POST "/api/seller/products/$PID1/unfreeze" "$TOKEN" ""
expect TC-A22 "手动解冻成功" 200 '"ok":true'

call POST "/api/seller/products/$PID2/offshelf" "$TOKEN" ""
expect TC-A23 "手动下架成功" 200 '"ok":true'

call GET "/api/seller/products?status=off_shelf" "$TOKEN" ""
expect TC-A24 "off_shelf 筛选含已下架商品" 200 "\"id\":$PID2"

# ---------- TC-A25 ~ A32：买家注册与登录（迭代二新增） ----------
call POST /api/buyers/register "" "{\"username\":\"$U1\",\"password\":\"smoke1234\",\"phone\":\"$P1\",\"phoneConfirm\":\"$P1\"}"
expect TC-A25 "买家 1 注册成功" 200 '"ok":true'

call POST /api/buyers/register "" "{\"username\":\"$U2\",\"password\":\"smoke1234\",\"phone\":\"$P2\",\"phoneConfirm\":\"$P2\"}"
expect TC-A26 "买家 2 注册成功" 200 '"ok":true'

call POST /api/buyers/register "" "{\"username\":\"$U3\",\"password\":\"smoke1234\",\"phone\":\"$P3\",\"phoneConfirm\":\"$P3\"}"
expect TC-A27 "买家 3 注册成功" 200 '"ok":true'

call POST /api/buyers/register "" "{\"username\":\"smka${TAIL}d\",\"password\":\"short1\",\"phone\":\"136${TS: -8}\",\"phoneConfirm\":\"136${TS: -8}\"}"
expect TC-A28 "密码过短注册被拒" 400 '密码'

call POST /api/buyers/login "" "{\"username\":\"$U1\",\"password\":\"smoke1234\"}"
expect TC-A29 "买家 1 登录成功" 200 '"token"'
BT1=$(json_str token)

call POST /api/buyers/login "" "{\"username\":\"$U2\",\"password\":\"smoke1234\"}"
expect TC-A30 "买家 2 登录成功" 200 '"token"'
BT2=$(json_str token)

call POST /api/buyers/login "" "{\"username\":\"$U3\",\"password\":\"smoke1234\"}"
expect TC-A31 "买家 3 登录成功" 200 '"token"'
BT3=$(json_str token)

call POST /api/intents "" "{\"productId\":$PID1}"
expect TC-A32 "未登录提交意向被拒" 401 '未登录或登录已失效'

# ---------- TC-A33 ~ A38：登录态提交意向与首笔自动进交易（裁决 C11） ----------
call POST /api/intents "$BT1" "{\"productId\":$PID1}"
expect TC-A33 "首笔提交自动进入交易（in_transaction）" 200 '"status":"in_transaction"'

call GET "/api/products/$PID1" ""
expect TC-A34 "首笔后商品自动冻结（frozenBy=auto）" 200 '"frozenBy":"auto"'

call POST /api/intents "$BT2" "{\"productId\":$PID1}"
expect TC-A35 "自动冻结期间买家 2 提交正常排队" 200 '"status":"queued"'

call POST /api/intents "$BT3" "{\"productId\":$PID1}"
expect TC-A36 "自动冻结期间买家 3 提交正常排队" 200 '"status":"queued"'

call GET "/api/seller/products/$PID1/queue" "$TOKEN" ""
expect TC-A37 "队列明细返回当前交易与排队位次" 200 '"position":1'

call POST "/api/seller/products/$PID1/unfreeze" "$TOKEN" ""
expect TC-A38 "交易中手动解冻被拒" 400 '交易中的商品不能手动解冻'

# ---------- TC-A39 ~ A46：交易结果 · 递补 · 售罄失效 ----------
# 流程（stock=2）：买家1 交易中 → fail 转待处置、买家2 递补 → dispose requeue 把
# 买家1 重新排队 → success（买家2 成交，stock 2→1，买家3 递补）→ success（买家3
# 成交，stock 1→0 售罄，队列剩余的买家1 未成交意向置 SOLD_OUT 失效）。
call POST "/api/seller/products/$PID1/deal/result" "$TOKEN" '{"result":"fail"}'
expect TC-A39 "标记交易失败成功" 200 '"ok":true'

call GET /api/seller/overview "$TOKEN" ""
FID=$(printf '%s' "$BODY" | sed -n 's/.*"pendingFailed":\[{"id":\([0-9]*\).*/\1/p')
if [ -n "$FID" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-A40 失败意向进入待处置列表'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-A40"
  echo '  [FAIL] TC-A40 待处置列表中没有失败意向'
fi

call POST "/api/seller/intents/$FID/dispose" "$TOKEN" '{"action":"requeue"}'
expect TC-A41 "重新排队处置成功" 200 '"ok":true'

call POST "/api/seller/products/$PID1/deal/result" "$TOKEN" '{"result":"success"}'
expect TC-A42 "买家 2 成交成功" 200 '"ok":true'
call GET "/api/products/$PID1" ""
expect TC-A43 "成交扣 1 件（stock 2→1）且回收后自动冻结" 200 '"frozenBy":"auto"'
STOCK1=$(printf '%s' "$BODY" | sed -n 's/.*"stock":\([0-9]*\).*/\1/p')
if [ "$STOCK1" = "1" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-A43b 库存扣减后剩余 1 件'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-A43b"
  echo "  [FAIL] TC-A43b 库存应为 1，实际 $STOCK1"
fi

call POST "/api/seller/products/$PID1/deal/result" "$TOKEN" '{"result":"success"}'
expect TC-A44 "买家 3 成交成功、库存归零" 200 '"ok":true'

call GET "/api/products/$PID1" ""
expect TC-A45 "库存归零转售罄，买家端不可见（404）" 404

call GET "/api/seller/products?status=sold_out" "$TOKEN" ""
expect TC-A46 "sold_out 筛选含售罄商品" 200 "\"id\":$PID1"

# ---------- TC-A47 ~ A49：买家历史三值（迭代二新增） ----------
call GET "/api/buyers/history" "$BT2" ""
expect TC-A47 "买家 2 历史显示「成功」" 200 '"result":"成功"'
if printf '%s' "$BODY" | grep -q '"dealTime":"2'; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-A47b 成交记录带交易时间（dealtAt）'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-A47b"
  echo '  [FAIL] TC-A47b 成交记录缺少交易时间'
fi

call GET "/api/buyers/history" "$BT1" ""
expect TC-A48 "买家 1 历史显示「失败」（售罄失效）" 200 '"result":"失败"'

call GET "/api/buyers/history" "$BT3" ""
expect TC-A49 "买家 3 历史显示「成功」且分页结构完整" 200 '"totalPages"'

# ---------- TC-A50 ~ A52：客户列表字段级隐私（迭代二新增） ----------
call GET /api/seller/customers "$TOKEN" ""
expect TC-A50 "客户列表可读且含注册买家" 200 "\"username\":\"$U1\""
if printf '%s' "$BODY" | grep -q 'passwordHash\|"token"'; then
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-A50b"
  echo '  [FAIL] TC-A50b 客户列表泄露密码哈希或 token'
else
  PASS=$((PASS + 1)); echo '  [PASS] TC-A50b 客户列表未泄露密码哈希与 token'
fi

BID=$(printf '%s' "$BODY" | sed -n "s/.*\"id\":\([0-9]*\),\"username\":\"$U1\".*/\1/p")
if [ -z "$BID" ]; then
  BID=$(printf '%s' "$BODY" | sed -n "s/.*\"username\":\"$U1\"[^{]*\"id\":\([0-9]*\).*/\1/p")
fi
call GET "/api/seller/customers/$BID/history" "$TOKEN" ""
expect TC-A52 "按客户看购买历史可读" 200 '"items":['

# ---------- TC-A53 ~ A58：修改密码（沿用迭代一，接口未变） ----------
call POST /api/seller/password "$TOKEN" '{"oldPassword":"bad-password","newPassword":"Smoke@Test123"}'
expect TC-A53 "原密码错误改密被拒" 400 '原密码错误'
call POST /api/seller/password "$TOKEN" '{"oldPassword":"'"$SELLER_PASS"'","newPassword":"123"}'
expect TC-A54 "新密码不足 8 位被拒" 400 '新密码长度不能少于 8 位'

call POST /api/seller/password "$TOKEN" '{"oldPassword":"'"$SELLER_PASS"'","newPassword":"Smoke@Test123"}'
expect TC-A55 "合法改密成功" 200
call POST /api/seller/login "" '{"username":"'"$SELLER_USER"'","password":"Smoke@Test123"}'
expect TC-A56 "新密码可以登录" 200 '"token"'
NEWTOKEN=$(json_str token)
call POST /api/seller/login "" '{"username":"'"$SELLER_USER"'","password":"'"$SELLER_PASS"'"}'
expect TC-A57 "旧密码已失效" 401 '用户名或密码错误'
# 改回原密码，避免污染后续调试环境
call POST /api/seller/password "$NEWTOKEN" '{"oldPassword":"Smoke@Test123","newPassword":"'"$SELLER_PASS"'"}'
if [ "$CODE" = "200" ]; then
  PASS=$((PASS + 1)); echo '  [PASS] TC-A58 已还原初始密码'
else
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS TC-A58"
  echo "  [FAIL] TC-A58 还原初始密码失败：$BODY"
fi

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
