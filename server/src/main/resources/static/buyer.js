/* 买家端 · 商品列表页逻辑（US-16 / J-2）
   依赖接口：GET /api/products、GET /api/categories/tree（J-1 已交付）
   列表口径（US-16 验收判据 5 + 线框图脚注）：只显示「在售」商品，
   冻结中（交易中 / 卖家暂停接单）与已售罄都不出现在列表；
   两类冻结的文案区分只发生在详情页（接口设计 v2 §五 裁决 C11）。 */
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

function renderCard(p) {
  const pic = p.imageUrl
    ? `<img src="${esc(p.imageUrl)}" alt="${esc(p.name)}" loading="lazy">`
    : '<span class="pic-ph">占位图</span>';
  return `
    <div class="pcard" data-id="${esc(p.id)}" title="${esc(p.name)}">
      <div class="pic">${pic}</div>
      <div class="body">
        <div class="name">${esc(p.name)}</div>
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
  const q = new URLSearchParams();
  if (state.keyword) q.set('keyword', state.keyword);
  if (state.categoryId) q.set('categoryId', state.categoryId);
  q.set('page', state.page);
  q.set('pageSize', state.pageSize);
  try {
    const data = await api('/api/products?' + q.toString());
    state.totalPages = data.totalPages || 0;
    /* 后端 VISIBLE 含 FROZEN（接口设计 v2 §五：frozenBy 供详情页区分文案），
       US-16 要求列表不出现冻结商品，故在展示层只保留 on_sale */
    const items = (data.items || []).filter((p) => p.status === 'on_sale');
    const grid = $('#productGrid');
    const label = currentLabel();
    /* 提示文案对齐 US-16 线框图右侧注释「共 12 条 / 页 · 按发布时间倒序」：
       后端 total 含冻结商品，与展示层过滤后的条数不一致，故不展示总数，只说明每页条数与排序 */
    $('#listHint').textContent =
      (label ? `筛选：${label} · ` : '') + `每页 ${state.pageSize} 条 · 按发布时间倒序`;
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
  await loadCategories();
  restoreFromUrl();
  renderCatTabs();
  loadList();
})();
