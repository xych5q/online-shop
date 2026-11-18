/* 我的收藏页逻辑（J3-1 · 迭代 3 · 挑战需求 B）
   依赖接口（林初俊 L3-5，已就绪）：
     GET    /api/favorites                  → {ok,total,items[{productId,name,price,imageUrl,status,offShelfReason,available,favoritedAt}]}
     DELETE /api/favorites/{productId}      → {ok}（幂等，未收藏也成功）
   鉴权：买家 X-Token；未登录/token 失效 401「未登录或登录已失效」

   口径（FR-D9 / G9 / G15）：
   - 收藏按账号存储、独立列表，不并入订单历史；按收藏时间倒序
   - 商品下架 / 售罄后【保留并标注】，不自动消失
   - 只展示名称、价格、主图；点击整卡进商品详情；不提供直接下单或加购入口
   - 置灰与按钮显隐统一以后端 available 为准（不要自己用 status 推导，售罄=已下架+原因 SOLD_OUT）
   - status 大小写兼容：后端当前返回小写（on_sale / off_shelf），枚举口径若改为大写也能正确处理 */
const $ = (s) => document.querySelector(s);

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
}

/* 兼容 ISO 字符串与 [y,m,d,h,mi,s] 数组两种序列化结果 */
function fmtTime(v) {
  if (!v) return '—';
  const d = Array.isArray(v) ? new Date(v[0], v[1] - 1, v[2], v[3] || 0, v[4] || 0, v[5] || 0) : new Date(v);
  if (isNaN(d.getTime())) return '—';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fmtPrice(v) {
  return Number(v).toFixed(2);
}

/* 时间戳：兼容 ISO 字符串与 [y,m,d,h,mi,s] 数组（与 fmtTime 同一套解析） */
function tsOf(v) {
  if (!v) return 0;
  const d = Array.isArray(v) ? new Date(v[0], v[1] - 1, v[2], v[3] || 0, v[4] || 0, v[5] || 0) : new Date(v);
  return isNaN(d.getTime()) ? 0 : d.getTime();
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

async function api(path, opts) {
  const headers = {};
  const tk = buyerToken();
  if (tk) headers['X-Token'] = tk;
  if (opts && opts.body) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    headers,
    method: (opts && opts.method) || 'GET',
    body: opts && opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || '请求失败');
    err.status = res.status;
    throw err;
  }
  return data;
}

/* 不可购买原因的展示文案：
   后端两态口径下 售罄 = status=OFF_SHELF + offShelfReason=SOLD_OUT；
   兼容迭代 2 遗留数据（SOLD_OUT 独立状态、DEAL_SUCCESS 成交下架、FROZEN 冻结）。
   返回 null 表示在售、无需标注。 */
function unavailableLabel(it) {
  const status = String(it.status || '').toUpperCase();
  const reason = String(it.offShelfReason || '').toUpperCase();
  if (status === 'SOLD_OUT' || (status === 'OFF_SHELF' && reason === 'SOLD_OUT')) return '已售罄';
  if (status === 'FROZEN') return '已下架';
  if (status === 'OFF_SHELF') return '已下架';
  /* 状态取值出现未预期值时，只要后端判定不可购买，仍按「已下架」标注，避免卡片无提示 */
  return it.available ? null : '已下架';
}

function renderCard(it) {
  const label = unavailableLabel(it);
  const pic = it.imageUrl
    ? `<img src="${esc(it.imageUrl)}" alt="${esc(it.name)}" loading="lazy">`
    : '<span class="pic-ph">占位图</span>';
  return `
    <div class="pcard${it.available ? '' : ' unavailable'}" data-id="${esc(it.productId)}" title="${esc(it.name)}">
      <div class="pic">${pic}</div>
      <div class="body">
        <div class="name">${esc(it.name)}</div>
        ${label ? `<div class="pcard-badge"><span class="badge ${label === '已售罄' ? 'soldout' : 'off'}">${label}</span></div>` : ''}
        <div class="price"><small>￥</small>${fmtPrice(it.price)}</div>
        <div class="time">收藏于 ${fmtTime(it.favoritedAt)}</div>
        <div class="fav-ops">
          <button type="button" class="link-btn" data-remove="${esc(it.productId)}">取消收藏</button>
        </div>
      </div>
    </div>
  `;
}

function renderAuthFail() {
  $('#favGrid').innerHTML = '';
  $('#favNote').style.display = 'none';
  const box = $('#favEmpty');
  box.style.display = '';
  box.innerHTML = `未登录或登录已失效<br>
    <a class="back-link" href="/login.html?next=%2Ffavorites.html">去登录</a>`;
}

function renderEmpty() {
  $('#favGrid').innerHTML = '';
  const box = $('#favEmpty');
  box.style.display = '';
  box.innerHTML = `还没有收藏的商品<br>
    <a class="back-link" href="/">去首页挑选商品</a>`;
}

function renderList(items) {
  const grid = $('#favGrid');
  const box = $('#favEmpty');
  if (!items.length) {
    renderEmpty();
    return;
  }
  box.style.display = 'none';
  $('#favNote').style.display = '';
  grid.innerHTML = items.map(renderCard).join('');
}

let items = [];

async function loadFavorites() {
  if (!buyerToken()) {
    renderAuthFail();
    return;
  }
  try {
    const data = await api('/api/favorites');
    items = (data.items || []).slice();
    /* 后端已按收藏时间倒序；这里再兜一次序，避免同毫秒记录顺序漂移 */
    items.sort((a, b) => tsOf(b.favoritedAt) - tsOf(a.favoritedAt));
    renderList(items);
    const unavailable = items.filter((it) => !it.available).length;
    $('#favNote').textContent = unavailable
      ? `共 ${items.length} 件收藏，其中 ${unavailable} 件已下架或售罄（保留在列表中并标注，不可购买）。收藏页只用于回看与进入详情，不提供直接下单或加入购物车入口。`
      : `共 ${items.length} 件收藏。收藏页只用于回看与进入详情，不提供直接下单或加入购物车入口。`;
  } catch (e) {
    if (e.status === 401) {
      clearBuyerSession();
      renderAuthFail();
      return;
    }
    $('#favGrid').innerHTML = '';
    const box = $('#favEmpty');
    box.style.display = '';
    box.innerHTML = esc(e.message);
  }
}

/* 取消收藏：DELETE 幂等，成功后就地移除卡片，不整页刷新 */
async function removeFavorite(productId, btn) {
  btn.disabled = true;
  try {
    await api('/api/favorites/' + encodeURIComponent(productId), { method: 'DELETE' });
    items = items.filter((it) => String(it.productId) !== String(productId));
    renderList(items);
    toast('已取消收藏', 'ok');
  } catch (e) {
    btn.disabled = false;
    if (e.status === 401) {
      clearBuyerSession();
      renderAuthFail();
      toast('登录已失效，请重新登录', 'err');
      return;
    }
    toast(e.message, 'err');
  }
}

/* 事件委托：取消收藏按钮优先，其余区域点击进详情（下架商品也允许进入，详情页会给出下架提示） */
$('#favGrid').addEventListener('click', (e) => {
  const rm = e.target.closest('[data-remove]');
  if (rm) {
    e.stopPropagation();
    removeFavorite(rm.dataset.remove, rm);
    return;
  }
  const card = e.target.closest('.pcard');
  if (card) location.href = '/product.html?id=' + encodeURIComponent(card.dataset.id);
});

loadFavorites();
