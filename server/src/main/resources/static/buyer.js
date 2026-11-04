/* 买家端 · 商品列表页逻辑（US-16 / J-2）
   依赖接口：GET /api/products、GET /api/categories/tree（J-1 已交付）
   列表口径（接口设计 v2 §五 裁决 C11）：
     ON_SALE           → 显示，卡片无徽标，可提交
     FROZEN + auto     → 显示，卡片标「交易中 · 仍可提交意向」，可提交
     FROZEN + manual   → 不显示（卖家暂停接单）
     SOLD_OUT / OFF_SHELF 后端不返回，前端不处理。 */
const $ = (s) => document.querySelector(s);

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
}

/* 兼容 ISO 字符串与 [y,m,d,h,mi,s] 数组两种序列化结果；展示到分钟（US-16 线框图 2026-10-21 15:01） */
function fmtTime(iso) {
  if (!iso) return '-';
  const d = Array.isArray(iso) ? new Date(iso[0], iso[1] - 1, iso[2], iso[3] || 0, iso[4] || 0) : new Date(iso);
  if (isNaN(d.getTime())) return '-';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fmtPrice(v) {
  return Number(v).toFixed(2);
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

async function api(path, opts) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts && opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '请求失败');
  return data;
}

/* ---------- 列表状态（与页面 URL 同步） ---------- */
const state = { keyword: '', categoryId: null, page: 1, pageSize: 12, totalPages: 0 };

/* 列表请求序号：快速切类别 / 翻页时只认最后一次请求的结果，避免旧响应覆盖新结果 */
let listSeq = 0;

let categoryTree = [];
let openMenuId = null;

/* 当前选中：一级 parent / 二级 child，用于标签高亮与文案 */
function currentSelection() {
  const id = state.categoryId;
  if (!id) return { parent: null, child: null };
  const asParent = categoryTree.find((c) => c.id === id);
  if (asParent) return { parent: asParent, child: null };
  const parent = categoryTree.find((c) => c.children.some((k) => k.id === id));
  return parent ? { parent, child: parent.children.find((k) => k.id === id) } : { parent: null, child: null };
}

function currentLabel() {
  const sel = currentSelection();
  if (!sel.parent) return '';
  return sel.child ? `${sel.parent.name} / ${sel.child.name}` : sel.parent.name;
}

/* 类别条：全部 + 各一级类别；点一级展开二级菜单 */
function renderCatTabs() {
  const sel = currentSelection();
  const head = `<button type="button" class="cat-tab${state.categoryId ? '' : ' active'}" data-cat="">全部</button>`;
  const tabs = categoryTree.map((c) => {
    const active = sel.parent && sel.parent.id === c.id;
    const label = active && sel.child ? `${esc(c.name)} · ${esc(sel.child.name)}` : esc(c.name);
    const open = openMenuId === c.id;
    const menu = c.children.length
      ? `<div class="cat-menu"${open ? '' : ' hidden'}>
          <button type="button" class="cat-menu-item" data-cat="${c.id}">全部${esc(c.name)}</button>
          ${c.children.map((k) => `<button type="button" class="cat-menu-item" data-cat="${k.id}">${esc(k.name)}</button>`).join('')}
        </div>`
      : '';
    return `<div class="cat-tab-wrap">
        <button type="button" class="cat-tab${active ? ' active' : ''}" data-tab="${c.id}">${label} <span class="caret">▾</span></button>
        ${menu}
      </div>`;
  });
  $('#catTabs').innerHTML = head + tabs.join('');
}

/* 页码序列：首尾固定，当前页左右各 2 页，间隔用省略号 */
function pageSeq(cur, total) {
  const keep = new Set([1, total]);
  for (let i = cur - 2; i <= cur + 2; i++) if (i >= 1 && i <= total) keep.add(i);
  const nums = Array.from(keep).sort((a, b) => a - b);
  const out = [];
  nums.forEach((n, i) => {
    if (i && n - nums[i - 1] > 1) out.push('...');
    out.push(n);
  });
  return out;
}

function renderPager() {
  const box = $('#pager');
  const { page, totalPages } = state;
  if (!totalPages || totalPages <= 1) {
    box.innerHTML = '';
    return;
  }
  box.innerHTML = `
    <button class="page-btn" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>‹ 上一页</button>
    ${pageSeq(page, totalPages)
      .map((n) => (n === '...'
        ? '<span class="page-gap">…</span>'
        : `<button class="page-btn${n === page ? ' active' : ''}" data-page="${n}">${n}</button>`))
      .join('')}
    <button class="page-btn" data-page="${page + 1}" ${page >= totalPages ? 'disabled' : ''}>下一页 ›</button>
  `;
}

/* URL 与筛选条件同步（US-16 线框图：/?keyword=自行车&category=骑行），刷新 / 从详情页返回都能复原 */
function syncUrl() {
  const q = new URLSearchParams();
  if (state.keyword) q.set('keyword', state.keyword);
  const label = currentLabel();
  if (label) q.set('category', label);
  if (state.page > 1) q.set('page', state.page);
  const s = q.toString();
  history.replaceState(null, '', s ? '/?' + s : '/');
}

/* 卡片状态徽标位（裁决 C11）：在售不占位；交易中（frozenBy=auto）标注仍可提交；
   卖家暂停接单的不会进入列表，故此处不出现「暂停接单」 */
function cardBadge(p) {
  if (p.status === 'frozen' && p.frozenBy === 'auto') {
    return '<div class="pcard-badge"><span class="badge frozen">交易中 · 仍可提交意向</span></div>';
  }
  return '';
}

function renderCard(p) {
  const pic = p.imageUrl
    ? `<img src="${esc(p.imageUrl)}" alt="${esc(p.name)}" loading="lazy">`
    : '<span class="pic-ph">占位图</span>';
  return `
    <div class="pcard" data-id="${esc(p.id)}" title="${esc(p.name)}">
      <div class="pic">${pic}</div>
      <div class="body">
        <div class="name">${esc(p.name)}</div>
        ${cardBadge(p)}
        <div class="price"><small>￥</small>${fmtPrice(p.price)}</div>
        <div class="time">${fmtTime(p.publishedAt)}</div>
      </div>
    </div>
  `;
}

async function loadCategories() {
  try {
    const data = await api('/api/categories/tree');
    categoryTree = (data.categories || []).map((c) => ({
      id: c.id,
      name: c.name,
      children: (c.children || []).map((k) => ({ id: k.id, name: k.name })),
    }));
  } catch (e) {
    categoryTree = [];
    toast(e.message, 'err');
  }
}

async function loadList() {
  const seq = ++listSeq;
  const q = new URLSearchParams();
  if (state.keyword) q.set('keyword', state.keyword);
  if (state.categoryId) q.set('categoryId', state.categoryId);
  q.set('page', state.page);
  q.set('pageSize', state.pageSize);
  try {
    const data = await api('/api/products?' + q.toString());
    if (seq !== listSeq) return;    // 期间又发出了新请求，本次结果作废
    state.totalPages = data.totalPages || 0;
    // 页码越界（链接里带了旧页码 / 筛选后总数变少）→ 回退到最后一页，不停在空页
    if (state.totalPages > 0 && state.page > state.totalPages) {
      state.page = state.totalPages;
      syncUrl();
      return loadList();
    }
    /* 裁决 C11 的列表口径：后端 VISIBLE = ON_SALE + FROZEN，
       这里只剔除「卖家暂停接单」（FROZEN 且 frozenBy=manual）；
       交易中（FROZEN 且 frozenBy=auto）保留在列表，卡片标徽标、详情页可提交意向 */
    const items = (data.items || []).filter((p) => !(p.status === 'frozen' && p.frozenBy === 'manual'));
    const grid = $('#productGrid');
    const label = currentLabel();
    /* 提示文案（US-16 线框图右侧注释）：总数 + 每页条数 + 排序口径 */
    $('#listHint').textContent =
      (label ? `筛选：${label} · ` : '') + `共 ${data.total || 0} 条 · 每页 ${state.pageSize} 条 · 按发布时间倒序`;
    if (!items.length) {
      grid.innerHTML = '';
      $('#listEmpty').style.display = 'block';
    } else {
      $('#listEmpty').style.display = 'none';
      grid.innerHTML = items.map(renderCard).join('');
    }
    renderPager();
  } catch (e) {
    toast(e.message, 'err');
  }
}

function applyFilter() {
  state.keyword = $('#searchInput').value.trim();
  state.page = 1;
  syncUrl();
  loadList();
}

function setCategory(id) {
  state.categoryId = id;
  state.page = 1;
  renderCatTabs();
  syncUrl();
  loadList();
}

/* ---------- 事件 ---------- */
$('#searchBtn').addEventListener('click', applyFilter);
$('#searchInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') applyFilter();
});

$('#catTabs').addEventListener('click', (e) => {
  const item = e.target.closest('.cat-menu-item');
  if (item) {
    openMenuId = null;
    setCategory(Number(item.dataset.cat));
    return;
  }
  const tab = e.target.closest('.cat-tab');
  if (!tab) return;
  if (tab.dataset.cat === '') {
    openMenuId = null;
    setCategory(null);
    return;
  }
  const id = Number(tab.dataset.tab);
  const node = categoryTree.find((c) => c.id === id);
  if (node && !node.children.length) {
    openMenuId = null;
    setCategory(id);
    return;
  }
  openMenuId = openMenuId === id ? null : id;
  renderCatTabs();
});

/* 点空白处收起二级菜单 */
document.addEventListener('click', (e) => {
  if (openMenuId !== null && !e.target.closest('.cat-tab-wrap')) {
    openMenuId = null;
    renderCatTabs();
  }
});

$('#pager').addEventListener('click', (e) => {
  const btn = e.target.closest('.page-btn');
  if (!btn || btn.disabled) return;
  state.page = Number(btn.dataset.page);
  syncUrl();
  loadList();
});

$('#productGrid').addEventListener('click', (e) => {
  const card = e.target.closest('.pcard');
  if (card) location.href = '/product.html?id=' + encodeURIComponent(card.dataset.id);
});

/* ---------- 初始化：先取类别树，再按 URL 复原筛选条件 ---------- */
function restoreFromUrl() {
  const q = new URLSearchParams(location.search);
  state.keyword = (q.get('keyword') || '').trim();
  const page = Number(q.get('page'));
  state.page = page > 0 ? page : 1;

  const raw = (q.get('category') || '').trim();
  if (!raw) return;
  const parts = raw.split('/').map((s) => s.trim()).filter(Boolean);
  const childName = parts[parts.length - 1];
  const parentName = parts.length > 1 ? parts[0] : '';
  let hit = null;
  categoryTree.forEach((p) => {
    if (parentName && p.name !== parentName) return;
    if (!parentName && p.name === childName) { hit = p; return; }
    const kid = p.children.find((k) => k.name === childName);
    if (kid) hit = kid;
  });
  state.categoryId = hit ? hit.id : null;
}

(async function initList() {
  state.keyword = (new URLSearchParams(location.search).get('keyword') || '').trim();
  $('#searchInput').value = state.keyword;
  /* 卡片图片加载失败（文件缺失 / 服务端未映射上传目录）时降级为占位图，
     不把浏览器的破图图标露给买家；error 事件不冒泡，用捕获阶段统一接 */
  $('#productGrid').addEventListener('error', (e) => {
    const img = e.target;
    if (!img || img.tagName !== 'IMG') return;
    const ph = document.createElement('span');
    ph.className = 'pic-ph';
    ph.textContent = '图片加载失败';
    img.replaceWith(ph);
  }, true);
  await loadCategories();
  restoreFromUrl();
  renderCatTabs();
  loadList();
})();
