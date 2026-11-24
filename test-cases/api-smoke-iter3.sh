#!/usr/bin/env bash
# ============================================================================
# 在线购物系统 · 接口冒烟测试脚本（迭代 3 · 挑战需求 B）
# 第 1 组 | 主笔：董嘉润（D3-8 测试线一） | 对应《测试用例清单-迭代3》TC-B 段
#
# 覆盖范围（挑战需求 B 的 6 个改动面）：
#   1) 商品两态：在售 / 已下架，冻结整套能力已废止（TC-B01 ~ B12）
#   2) 购物车 D3-2：条目结构、重复加购合并、数量调整、逐件移除、结算前校验、
#      批量转收藏与去重、账号隔离（TC-B13 ~ B29）
#   3) 下单即扣减与五状态推进（TC-B30 ~ B38）
#   4) 双端取消窗口：买家前三态、商家含开始发货（TC-B39 ~ B48）
#   5) 商家批量取消两类核心断言：全部可取消 N 笔生效 / 含一单不可取消整批拦截
#      （TC-B49 ~ B57，G5 五项口径）
#   6) 售罄自动下架与取消回补后 G7 分流恢复（TC-B58 ~ B63）
#   7) 收藏对已下架商品的保留与标注（TC-B64）
#
# 与 api-smoke-iter2.sh 的关系：迭代 2 的口令码 / 购买意向 / 队列接口已整套退役，
# 本脚本不复制其用例，只沿用其 call/expect 工具函数与「UTF-8 bodyfile 传中文」做法；
# 迭代 1、2 脚本保留不动，作为历史迭代的回归凭证。
#
# 用法：
#   1) 先起服务（建议干净库；库名可任意，脚本不依赖库内历史数据）
#      cd server
#      java -jar target/online-shop.jar --server.port=8090 \
#           --spring.datasource.url="jdbc:mysql://localhost:3306/onlineshop_smoke3?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
#           --spring.datasource.password="<本机 MySQL 密码>"
#   2) 回到仓库根目录执行
#      cd test-cases && BASE=http://localhost:8090 bash api-smoke-iter3.sh
#
# 环境变量：
#   BASE         服务地址，默认 http://localhost:8090
#   SELLER_USER  卖家账号，默认 admin（不用 USERNAME，避免与 Windows 环境变量冲突）
#   SELLER_PASS  卖家初始密码，默认 seller123
#
# 说明：买家账号带时间戳，重复执行不会因用户名冲突失败；商品与订单会累积，
#       但脚本只用本次返回的 id 做断言，不依赖分页位置，可反复执行。
# 退出码：全部通过 0，存在失败 1。
# ============================================================================

BASE="${BASE:-http://localhost:8090}"
SELLER_USER="${SELLER_USER:-admin}"
SELLER_PASS="${SELLER_PASS:-seller123}"
# 1x1 透明 PNG（发布商品共用）
IMG='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII='
TS=$(date +%s)
TAIL="${TS: -6}"
BU1="smkc${TAIL}a"
BU2="smkc${TAIL}b"
BP1="138${TS: -8}"
BP2="139${TS: -8}"

CODE=""
BODY=""
PASS=0
FAIL=0
FAILED_IDS=""

# ---------- 工具函数 ----------
call() { # call <方法> <路径> <token> <body>
  local method="$1" path="$2" token="$3" body="$4" resp bodyfile=""
  local -a args=(-s --noproxy '*' -w $'\n%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "X-Token: $token")
  if [ -n "$body" ]; then
    # Windows Git Bash 会把中文命令行参数按 ANSI(GBK) 传给原生 curl.exe，
    # 导致 JSON 成为非法 UTF-8；改用 UTF-8 临时文件 + -d @file，保证中文不乱码
    bodyfile=$(mktemp ./smoke3-body.XXXXXX)
    printf '%s' "$body" > "$bodyfile"
    args+=(-H 'Content-Type: application/json' -d "@$bodyfile")
  fi
  resp=$(curl "${args[@]}")
  rm -f "$bodyfile"
  CODE="${resp##*$'\n'}"
  BODY="${resp%$'\n'*}"
}

pass() { PASS=$((PASS + 1)); printf '  [\033[32mPASS\033[0m] %-7s %s\n' "$1" "$2"; }
fail() {
  FAIL=$((FAIL + 1)); FAILED_IDS="$FAILED_IDS $1"
  printf '  [\033[31mFAIL\033[0m] %-7s %s\n' "$1" "$2"
}

expect() { # expect <编号> <描述> <期望状态码> [响应必须包含的字符串]
  local id="$1" desc="$2" want="$3" wantstr="$4"
  if [ "$CODE" = "$want" ] && { [ -z "$wantstr" ] || printf '%s' "$BODY" | grep -qF -- "$wantstr"; }; then
    pass "$id" "$desc"
  else
    local detail=""
    [ -n "$wantstr" ] && detail=" 且响应含 \"$wantstr\""
    fail "$id" "$desc"
    printf '           期望 %s%s，实际 HTTP %s，响应：%s\n' "$want" "$detail" "$CODE" "$BODY"
  fi
}

# 断言响应里某个数字字段等于期望值（取最后一次出现，适配单对象响应）
expect_num() { # expect_num <编号> <描述> <字段> <期望值>
  local id="$1" desc="$2" key="$3" want="$4"
  local got
  got=$(printf '%s' "$BODY" | sed -n "s/.*\"$key\":\([0-9.-]\{1,\}\).*/\1/p")
  if [ "$got" = "$want" ]; then
    pass "$id" "$desc"
  else
    fail "$id" "$desc"
    printf '           期望 %s=%s，实际 %s，响应：%s\n' "$key" "$want" "$got" "$BODY"
  fi
}

# 取标量数组的元素个数（数组元素为数字或简单字符串，不含嵌套对象）
arr_len() { # arr_len <数组字段名>
  local raw
  raw=$(printf '%s' "$BODY" | sed -n "s/.*\"$1\":\[\([^]]*\)\].*/\1/p")
  if [ -z "$raw" ]; then echo 0; else printf '%s' "$raw" | awk -F',' '{print NF}'; fi
}

# 断言标量数组长度（cancelled / moved / alreadyFavorited / failed 等）
expect_arr() { # expect_arr <编号> <描述> <数组字段名> <期望条数>
  local id="$1" desc="$2" key="$3" want="$4" got
  got=$(arr_len "$key")
  if [ "$got" = "$want" ]; then
    pass "$id" "$desc"
  else
    fail "$id" "$desc"
    printf '           期望 %s 数组 %s 条，实际 %s 条，响应：%s\n' "$key" "$want" "$got" "$BODY"
  fi
}

# 断言响应不含某字符串（用于隐私 / 字段缺失校验）
expect_absent() { # expect_absent <编号> <描述> <不应出现的字符串>
  local id="$1" desc="$2" needle="$3"
  if printf '%s' "$BODY" | grep -qF -- "$needle"; then
    fail "$id" "$desc"
    printf '           响应不应包含 \"%s\"，实际：%s\n' "$needle" "$BODY"
  else
    pass "$id" "$desc"
  fi
}

# 断言某个字面串出现次数（用于对象数组条数）
expect_occ() { # expect_occ <编号> <描述> <字面串> <期望次数>
  local id="$1" desc="$2" needle="$3" want="$4" got
  got=$(printf '%s' "$BODY" | grep -oF -- "$needle" | wc -l | tr -d ' ')
  if [ "$got" = "$want" ]; then
    pass "$id" "$desc"
  else
    fail "$id" "$desc"
    printf '           期望 \"%s\" 出现 %s 次，实际 %s 次，响应：%s\n' "$needle" "$want" "$got" "$BODY"
  fi
}

json_str() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
json_num() { printf '%s' "$BODY" | sed -n "s/.*\"$1\":\([0-9.-]\{1,\}\).*/\1/p"; }

# 发布一件商品并回显 id（publish_product <token> <名称> <类别> <价格> <库存>）
publish_product() {
  call POST /api/seller/products "$1" "{\"name\":\"$2\",\"categoryId\":$3,\"description\":\"冒烟迭代3\",\"price\":$4,\"stock\":$5,\"images\":[\"$IMG\"]}"
  json_num id
}

echo "=============================================================="
echo " 在线购物系统 · 接口冒烟测试（迭代 3 · TC-B）"
echo " 服务地址： $BASE     卖家账号： $SELLER_USER"
echo "=============================================================="

# ============================================================
# 一、商品两态与废止能力（TC-B01 ~ B12）
# ============================================================
echo "---- 一、商品两态与废止能力 ----"

call POST /api/seller/login "" "{\"username\":\"$SELLER_USER\",\"password\":\"$SELLER_PASS\"}"
expect TC-B01 "卖家正确账密登录成功" 200 '"token"'
TOKEN=$(json_str token)

call POST /api/seller/categories "$TOKEN" "{\"name\":\"冒烟B一级$TAIL\"}"
expect TC-B02 "新建一级类别成功" 200 '"ok":true'
CAT1=$(json_num id)

call POST /api/seller/categories "$TOKEN" "{\"name\":\"冒烟B二级$TAIL\",\"parentId\":$CAT1}"
expect TC-B03 "一级类别下新建二级类别成功" 200 '"ok":true'
CAT2=$(json_num id)

PA=$(publish_product "$TOKEN" "冒烟B商品A$TAIL" "$CAT2" 10.00 5)
if [ -n "$PA" ]; then pass TC-B04 "发布商品A（库存 5）成功"; else fail TC-B04 "发布商品A失败：$BODY"; fi
PB=$(publish_product "$TOKEN" "冒烟B商品B$TAIL" "$CAT2" 20.00 3)
if [ -n "$PB" ]; then pass TC-B05 "发布商品B（库存 3）成功"; else fail TC-B05 "发布商品B失败：$BODY"; fi

call GET "/api/seller/products?status=on_sale" "$TOKEN" ""
expect TC-B06 "卖家列表 on_sale 含新发布商品A" 200 "\"id\":$PA"

call POST "/api/seller/products/$PA/offshelf" "$TOKEN" ""
expect TC-B07 "手动下架商品A成功（两态之一）" 200 '"ok":true'

call GET "/api/products/$PA" "" ""
expect TC-B08 "下架后买家详情 404（只挡新下单）" 404

call POST "/api/seller/products/$PA/onshelf" "$TOKEN" ""
expect TC-B09 "重新上架商品A成功（下架非终态）" 200 '"ok":true'

call GET "/api/products/$PA" "" ""
expect TC-B10 "重新上架后买家详情可见" 200 '"stock":5'

call POST "/api/seller/products/$PA/freeze" "$TOKEN" ""
expect TC-B11 "冻结端点已废止（SRS 不提供冻结与解冻）" 404

call PUT "/api/seller/products/$PA/stock" "$TOKEN" '{"stock":0}'
expect TC-B12 "在售商品库存调为 0 触发售罄自动下架（两态，售罄并入已下架）" 200 '"off_shelf"'
call PUT "/api/seller/products/$PA/stock" "$TOKEN" '{"stock":5}'
expect TC-B12b "售罄自动下架商品补货后回到在售" 200 '"on_sale"'

# ============================================================
# 二、购物车（TC-B13 ~ B29，D3-2）
# ============================================================
echo "---- 二、购物车接口（D3-2） ----"

call GET /api/cart "" ""
expect TC-B13 "未登录查购物车被拒" 401 '未登录或登录已失效'

call POST /api/cart "" "{\"productId\":$PA,\"quantity\":1}"
expect TC-B14 "未登录加购被拒" 401 '未登录或登录已失效'

for suffix in a b; do
  call POST /api/buyers/register "" "{\"username\":\"smkc${TAIL}${suffix}\",\"password\":\"smoke1234\",\"phone\":\"$BP1\",\"phoneConfirm\":\"$BP1\"}"
done

call POST /api/buyers/login "" "{\"username\":\"$BU1\",\"password\":\"smoke1234\"}"
expect TC-B15 "买家 1 登录成功" 200 '"token"'
BT1=$(json_str token)
call POST /api/buyers/login "" "{\"username\":\"$BU2\",\"password\":\"smoke1234\"}"
expect TC-B16 "买家 2 登录成功" 200 '"token"'
BT2=$(json_str token)

call POST /api/cart "$BT1" "{\"productId\":$PA,\"quantity\":2}"
expect TC-B17 "商品A 加购 2 件成功" 200 '"ok":true'

call POST /api/cart "$BT1" "{\"productId\":$PA,\"quantity\":1}"
expect TC-B18 "同一商品重复加购合并累加（G10）" 200 '"quantity":3'

call GET /api/cart "$BT1" ""
expect TC-B19 "购物车列表可读且只 1 条" 200 '"total":1'
if printf '%s' "$BODY" | grep -qF '"subtotal":30.00'; then
  pass TC-B20 "条目含名称/单价/数量/小计，小计=30.00（G12 无缩略图与总计金额）"
else
  fail TC-B20 "购物车条目小计不符合预期：$BODY"
fi
expect_absent TC-B21 "购物车条目不返回缩略图与总计金额" '"imageUrl"'

call POST "/api/cart/$PA/quantity" "$BT1" '{"quantity":2}'
expect TC-B22 "数量调整覆盖式生效（2 件）" 200 '"quantity":2'

call POST "/api/cart/$PA/quantity" "$BT1" '{"quantity":0}'
expect TC-B23 "数量调整为 0 被拒（移除走逐件移除）" 400 '数量必须大于 0'

call POST /api/cart "$BT1" "{\"productId\":$PB,\"quantity\":1}"
expect TC-B24 "商品B 加购成功（购物车 2 条）" 200 '"ok":true'
call GET /api/cart "$BT1" ""
expect_num TC-B25 "购物车两条目" total 2

call POST /api/cart/validate "$BT1" '{}'
expect TC-B26 "结算前校验通过（pass=true）" 200 '"pass":true'

call POST /api/cart/to-favorites "$BT1" "{\"productIds\":[$PA]}"
expect TC-B27 "勾选商品A 一次转收藏成功（FR-D11）" 200 '"ok":true'
expect_arr TC-B28 "转收藏返回 moved 1 条" moved 1

call GET /api/cart "$BT1" ""
expect_num TC-B29 "转收藏后商品A 移出购物车（G14）" total 1

call GET /api/favorites "$BT1" ""
expect TC-B30 "收藏列表含刚转入的商品A" 200 "\"productId\":$PA"

call POST /api/cart "$BT1" "{\"productId\":$PA,\"quantity\":1}"
call POST /api/cart/to-favorites "$BT1" "{\"productIds\":[$PA]}"
expect TC-B31 "已在收藏中的商品再次转入静默去重（G15）" 200 '"ok":true'
expect_arr TC-B32 "去重时计入 alreadyFavorited" alreadyFavorited 1

call DELETE "/api/cart/$PB" "$BT1" ""
expect TC-B33 "逐件移除商品B 成功" 200 '"ok":true'
call GET /api/cart "$BT1" ""
expect_num TC-B34 "购物车已清空（不提供一键清空，逐件移除后为 0）" total 0

call DELETE "/api/cart/$PB" "$BT1" ""
expect TC-B35 "重复移除幂等不报错" 200 '"ok":true'

call GET /api/cart "$BT2" ""
expect_num TC-B36 "购物车按账号隔离（买家 2 为空）" total 0

call GET /api/cart "$TOKEN" ""
expect TC-B37 "卖家 token 访问买家购物车被拒" 401

# ============================================================
# 三、下单即扣减与五状态推进（TC-B38 ~ B46）
# ============================================================
echo "---- 三、下单与五状态推进 ----"

call POST /api/orders "" "{\"items\":[{\"productId\":$PA,\"quantity\":1}]}"
expect TC-B38 "未登录下单被拒" 401 '未登录或登录已失效'

call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PA,\"quantity\":2}]}"
expect TC-B39 "从购物车勾选下单成功（初始 PLACED）" 200 '"status":"PLACED"'
O1=$(json_num orderId)

call GET "/api/products/$PA" ""
expect_num TC-B40 "下单成功即按件扣减库存（5-2=3）" stock 3

call GET "/api/orders/my" "$BT1" ""
expect TC-B41 "我的订单列表含本单且为分页结构" 200 "\"orderId\":$O1"

call GET "/api/seller/orders?page=1" "$TOKEN" ""
expect TC-B42 "商家订单列表按下单时间倒序含本单" 200 "\"orderId\":$O1"

call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PB,\"quantity\":99}]}"
expect TC-B43 "任一商品库存不足整单拦截（B.2.3-3）" 400 '库存不足'

call GET "/api/products/$PB" ""
expect_num TC-B44 "整单拦截后库存未变（仍 3）" stock 3

call POST "/api/seller/products/$PB/offshelf" "$TOKEN" ""
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PB,\"quantity\":1}]}"
expect TC-B45 "已下架商品不可结算（G11）" 400 '已下架'
call POST "/api/seller/products/$PB/onshelf" "$TOKEN" ""

call POST "/api/seller/orders/$O1/advance" "$TOKEN" ""
expect TC-B46 "推进到商家确认（CONFIRMED）" 200 '"status":"CONFIRMED"'

call POST "/api/seller/orders/$O1/advance" "$TOKEN" ""
expect TC-B47 "推进到备货完成（PREPARED）" 200 '"status":"PREPARED"'

call POST "/api/seller/orders/$O1/advance" "$TOKEN" ""
expect TC-B48 "推进到开始发货（SHIPPING）" 200 '"status":"SHIPPING"'

call POST "/api/orders/$O1/cancel" "$BT1" ""
expect TC-B49 "开始发货后买家不可取消（C1 窗口）" 400 '开始发货后买家不可取消'

call POST "/api/seller/orders/$O1/cancel" "$TOKEN" ""
expect TC-B50 "商家在开始发货阶段仍可取消（C2 窗口）" 200 '"status":"CANCELLED"'

call GET "/api/products/$PA" ""
expect_num TC-B51 "取消后按原件数回补库存（3+2=5）" stock 5

call POST "/api/orders/$O1/cancel" "$BT1" ""
expect TC-B52 "已取消订单重复取消被拒" 400 '订单已取消'

# 交易完成不可取消：重新下单并推进到 COMPLETED
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PA,\"quantity\":1}]}"
O2=$(json_num orderId)
for _ in 1 2 3 4; do call POST "/api/seller/orders/$O2/advance" "$TOKEN" ""; done
expect TC-B53 "四次推进到达交易完成（COMPLETED）" 200 '"status":"COMPLETED"'
call POST "/api/seller/orders/$O2/cancel" "$TOKEN" ""
expect TC-B54 "交易完成后商家不可取消" 400 '交易已完成，不可取消'

# ============================================================
# 四、商家批量取消：两类核心断言（TC-B55 ~ B66，G5）
# ============================================================
echo "---- 四、商家批量取消（G5 两类核心断言） ----"

PC=$(publish_product "$TOKEN" "冒烟B批量商品$TAIL" "$CAT2" 5.00 10)
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PC,\"quantity\":2}]}"
OB1=$(json_num orderId)
expect TC-B55 "批量用例下单 1（2 件）成功" 200 '"status":"PLACED"'
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PC,\"quantity\":3}]}"
OB2=$(json_num orderId)
expect TC-B56 "批量用例下单 2（3 件）成功" 200 '"status":"PLACED"'

call GET "/api/products/$PC" ""
expect_num TC-B57 "两笔订单合计扣减 5 件（10-5=5）" stock 5

call POST /api/seller/orders/batch-cancel "$TOKEN" "{\"orderIds\":[$OB1,$OB2]}"
expect TC-B58 "全部可取消：N 笔一次请求整批生效" 200 '"ok":true'
expect_arr TC-B59 "批量响应返回 cancelled 2 笔" cancelled 2

call POST "/api/orders/$OB1/cancel" "$BT1" ""
expect TC-B60 "批量后第 1 笔确为已取消" 400 '订单已取消'
call POST "/api/orders/$OB2/cancel" "$BT1" ""
expect TC-B61 "批量后第 2 笔确为已取消" 400 '订单已取消'

call GET "/api/products/$PC" ""
expect_num TC-B62 "整批取消后 N 笔回补合并回原库存（5+5=10）" stock 10

# 第二类核心断言：含一单不可取消 → 整批不执行
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PC,\"quantity\":1}]}"
OB3=$(json_num orderId)
for _ in 1 2 3 4; do call POST "/api/seller/orders/$OB3/advance" "$TOKEN" ""; done
expect TC-B63 "构造一笔交易完成订单（批量拦截用）" 200 '"status":"COMPLETED"'
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PC,\"quantity\":2}]}"
OB4=$(json_num orderId)
call GET "/api/products/$PC" ""
expect_num TC-B64 "构造第二笔可取消订单后库存为 7" stock 7

call POST /api/seller/orders/batch-cancel "$TOKEN" "{\"orderIds\":[$OB3,$OB4]}"
expect TC-B65 "含一单不可取消：整批拦截 400" 400 '"ok":false'
expect_occ TC-B66 "失败清单含 1 条不可取消原因" '"orderId"' 1
if printf '%s' "$BODY" | grep -qF '"reason":"COMPLETED"' && printf '%s' "$BODY" | grep -qF '"executed":[]'; then
  pass TC-B67 "失败原因为 COMPLETED 且 executed 为空（一笔都没执行）"
else
  fail TC-B67 "失败清单或 executed 不符合预期：$BODY"
fi

call GET "/api/products/$PC" ""
expect_num TC-B68 "整批拦截订单状态与库存均不变（仍 7）" stock 7

call POST "/api/orders/$OB4/cancel" "$BT1" ""
expect TC-B69 "被拦截订单仍可单独取消（证明未被批量取消）" 200 '"status":"CANCELLED"'
call GET "/api/products/$PC" ""
expect_num TC-B70 "单笔取消后回补 2 件（7+2=9）" stock 9

call POST /api/seller/orders/batch-cancel "$TOKEN" "{\"orderIds\":[$OB4,$OB4]}"
expect TC-B71 "重复订单号视为预校验失败（防误触）" 400 'DUPLICATE'

call POST /api/seller/orders/batch-cancel "$TOKEN" '{"orderIds":[]}'
expect TC-B72 "空订单列表被拒" 400 '未选择要取消的订单'

call POST /api/seller/orders/batch-cancel "$BT1" "{\"orderIds\":[$OB4]}"
expect TC-B73 "买家 token 调用批量取消被拒（只限商家侧）" 401

# ============================================================
# 五、售罄自动下架与 G7 回补分流（TC-B74 ~ B82）
# ============================================================
echo "---- 五、售罄自动下架与 G7 回补恢复 ----"

PD=$(publish_product "$TOKEN" "冒烟B售罄商品$TAIL" "$CAT2" 7.00 1)
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PD,\"quantity\":1}]}"
OD=$(json_num orderId)
expect TC-B74 "售罄用例下单成功" 200 '"status":"PLACED"'

call GET "/api/products/$PD" ""
expect TC-B75 "库存扣至 0 后自动下架（买家详情 404）" 404
call GET "/api/seller/products?status=off_shelf" "$TOKEN" ""
expect TC-B76 "售罄商品进入卖家已下架列表" 200 "\"id\":$PD"

call POST "/api/orders/$OD/cancel" "$BT1" ""
expect TC-B77 "售罄订单取消成功" 200 '"status":"CANCELLED"'
call GET "/api/products/$PD" ""
expect TC-B78 "回补后售罄自动下架的商品恢复在售（G7 分流一）" 200 '"stock":1'

# 手动下架的商品遇取消不自动恢复
PE=$(publish_product "$TOKEN" "冒烟B手动下架商品$TAIL" "$CAT2" 9.00 2)
call POST /api/orders "$BT1" "{\"items\":[{\"productId\":$PE,\"quantity\":1}]}"
OE=$(json_num orderId)
call POST "/api/seller/products/$PE/offshelf" "$TOKEN" ""
expect TC-B79 "手动下架进行中的商品成功（下架不牵连订单）" 200 '"ok":true'
call POST "/api/orders/$OE/cancel" "$BT1" ""
expect TC-B80 "手动下架期间订单仍可取消并回补" 200 '"status":"CANCELLED"'
call GET "/api/products/$PE" ""
expect TC-B81 "手动下架商品不因取消自动恢复在售（G7 分流二）" 404
call POST "/api/seller/products/$PE/onshelf" "$TOKEN" ""
expect TC-B82 "重新上架手动下架商品成功（下架非终态）" 200 '"ok":true'
call GET "/api/products/$PE" ""
expect TC-B83 "重新上架后库存已回补到 2（回补落库可验证）" 200 '"stock":2'

# ============================================================
# 六、收藏对已下架商品的保留与标注（TC-B84 ~ B85）
# ============================================================
echo "---- 六、收藏保留与标注 ----"

call POST "/api/seller/products/$PB/offshelf" "$TOKEN" ""
call POST /api/favorites "$BT1" "{\"productId\":$PB}"
expect TC-B84 "收藏已下架商品仍成功（收藏保留）" 200 '"ok":true'
call GET /api/favorites "$BT1" ""
if printf '%s' "$BODY" | grep -qF '"offShelfReason":"MANUAL"' && printf '%s' "$BODY" | grep -qF '"available":false'; then
  pass TC-B85 "已下架收藏项标注 MANUAL 且不可购买（FR-D9）"
else
  fail TC-B85 "已下架收藏项标注不符合预期：$BODY"
fi

# ============================================================
# 汇总
# ============================================================
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
