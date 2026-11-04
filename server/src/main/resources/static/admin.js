/* 卖家后台逻辑 */
const $ = (s) => document.querySelector(s);

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
}

function fmtTime(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
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

let token = localStorage.getItem('seller_token') || '';

async function api(path, opts) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json', 'X-Token': token },
    ...opts,
    body: opts && opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || '请求失败'), { status: res.status });
  return data;
}

/* ---------- 登录 ---------- */
async function login() {
  const username = $('#loginUser').value.trim();
  const password = $('#loginPass').value;
  $('#loginError').textContent = '';
  if (!username || !password) {
    $('#loginError').textContent = '请输入用户名与密码';
    return;
  }
  try {
    const data = await api('/api/seller/login', { method: 'POST', body: { username, password } });
    token = data.token;
    localStorage.setItem('seller_token', token);
    enterAdmin();
  } catch (e) {
    $('#loginError').textContent = e.message;
  }
}

async function enterAdmin() {
  $('#loginCard').style.display = 'none';
  $('#adminBody').style.display = '';
  // 发布表单、改挂类别下拉都依赖类别树，先备好再渲染工作台
  await loadCategories();
  loadProducts(true);
}

function showLogin() {
  $('#loginCard').style.display = '';
  $('#adminBody').style.display = 'none';
}

/* ---------- 类别树（后台共用：发布表单 / 改挂类别 / 类别管理） ---------- */
let categoryTree = [];
let categoryLoaded = false;

/** 拉取两级类别树（GET /api/categories/tree，买家侧同一接口，无需鉴权） */
async function loadCategories(force) {
  if (categoryLoaded && !force) return categoryTree;
  try {
    const data = await api('/api/categories/tree');
    categoryTree = data.categories || [];
    categoryLoaded = true;
  } catch (e) {
    categoryTree = [];
    toast(e.message, 'err');
  }
  return categoryTree;
}

/** 二级类别下拉项（按一级分组），value 为二级类别 id */
function leafOptionsHtml(selectedId, placeholder) {
  const groups = categoryTree
    .map((c) => {
      const kids = c.children || [];
      if (!kids.length) return '';
      const opts = kids
        .map((k) => `<option value="${k.id}"${String(k.id) === String(selectedId) ? ' selected' : ''}>${esc(k.name)}</option>`)
        .join('');
      return `<optgroup label="${esc(c.name)}">${opts}</optgroup>`;
    })
    .join('');
  return `<option value="">${esc(placeholder || '请选择二级类别')}</option>${groups}`;
}

/** 商品已挂类别的可读名称：一级 / 二级 */
function categoryLabel(categoryId) {
  for (const c of categoryTree) {
    for (const k of (c.children || [])) {
      if (String(k.id) === String(categoryId)) return `${c.name} / ${k.name}`;
    }
  }
  return categoryId ? `未知类别（id=${categoryId}）` : '未设置';
}

/** 是否存在可用二级类别（发布商品必须有） */
function hasLeafCategory() {
  return categoryTree.some((c) => (c.children || []).length > 0);
}

/* ---------- 当前商品：商品列表 + 详情 ---------- */
/**
 * 数据来源（接口见 docs/design/接口设计-卖家后台管理.md）：
 *   列表 GET /api/seller/products?status=all&page=1&size=10
 *        → { ok, page, totalPages, total, items:[{id,name,status,stock,price,pendingCount,
 *            inTransaction,frozenBy,imageUrl,description,categoryId,createdAt}] }
 *   详情 = 列表项（名称 / 价格 / 库存 / 状态 / 冻结类型 / 主图 / 描述 / 类别）+ 该商品全部意向 GET .../{id}/intents
 *        图集另外解析：列表项带 images / imageUrls 就直接用；只带主图时，对在售 / 冻结商品
 *        补一次公开详情 GET /api/products/{id}（只有它返回 images）——售罄 / 已下架必然 404，
 *        退化为单张主图，见 resolveProductImages()。
 *   待处理失败意向 = 该商品 intents 中 status=failed 且 disposed 不为 true 的（不依赖 overview）。
 */
let productList = { page: 1, size: 10, status: 'all', items: [], total: 0, totalPages: 1 };
let selectedProductId = null;
let selectedDetail = null;
let publishOpen = false;
let lastListJson = '';
// 详情数据的快照：轮询每 5 秒 loadDetail 一次，数据没变就不重绘，
// 否则买家缩略图选中态会被打回第一张、图片也会闪一下
let lastDetailJson = null;

/**
 * 详情图集（发表单支持 9 张，后台要能看到 9 张，否则等于只有主图）。
 * 优先用列表项里下发的 images / imageUrls；老版本列表接口只给主图 imageUrl，
 * 此时对买家可见的商品（在售 / 冻结）补一次公开详情 GET /api/products/{id} 拿 images，
 * 售罄 / 已下架的公开详情必然 404，退化为只显示主图。
 * 结果按商品 id 缓存：图片发布后不可改，避免 5 秒轮询反复请求。
 */
const imageCache = new Map();
async function resolveProductImages(row) {
  const own = Array.isArray(row.images) ? row.images
    : Array.isArray(row.imageUrls) ? row.imageUrls : null;
  if (own && own.length) return own.slice();
  if (imageCache.has(row.id)) return imageCache.get(row.id);
  let list = row.imageUrl ? [row.imageUrl] : [];
  if (row.status === 'on_sale' || row.status === 'frozen') {
    try {
      const d = await api(`/api/products/${row.id}`);
      const imgs = d && d.product && d.product.images;
      if (Array.isArray(imgs) && imgs.length) list = imgs.slice();
    } catch (e) {
      // 拿不到图集不影响详情其余部分，保留主图
    }
  }
  imageCache.set(row.id, list);
  return list;
}

/** 上一次点开的缩略图下标：轮询重绘后接着显示同一张，不被打回第一张 */
const picIndexById = new Map();

/* 列表请求序号：5 秒轮询与手动翻页 / 切筛选可能并发，只认最后一次 */
let listSeq = 0;

const STATUS_FILTERS = [
  ['all', '全部'],
  ['on_sale', '在售'],
  ['frozen', '冻结 / 交易中'],
  ['sold_out', '售罄'],
  ['off_shelf', '已下架'],
];

/**
 * 商品状态徽标（status 为小写下划线）。
 * 冻结态只能由 frozenBy 区分：auto = 交易中，manual = 卖家暂停接单。
 * 不能用 inTransaction 反推 —— 手动冻结时同样可能存在交易中的意向，两者不可互推。
 * frozenBy 未下发时给中性「已冻结」，不臆断成其中一种。
 */
function productBadge(p) {
  if (p.status === 'on_sale') return '<span class="badge on">在售</span>';
  if (p.status === 'frozen') {
    if (p.frozenBy === 'auto') return '<span class="badge deal">交易中</span>';
    if (p.frozenBy === 'manual') return '<span class="badge frozen">已冻结（手动）</span>';
    return '<span class="badge frozen">已冻结</span>';
  }
  if (p.status === 'sold_out') return '<span class="badge off">已售罄</span>';
  return '<span class="badge off">已下架</span>';
}

/* ---------- 商品列表 ---------- */
async function loadProducts(force) {
  const seq = ++listSeq;
  try {
    const data = await api(`/api/seller/products?status=${productList.status}&page=${productList.page}&size=${productList.size}`);
    if (seq !== listSeq) return;    // 期间又发出了新请求，本次结果作废
    productList.page = data.page || 1;
    productList.totalPages = data.totalPages || 1;
    productList.total = data.total || 0;
    productList.items = data.items || [];

    // 页码越界（筛选后总数变少 / 本页商品被下架）→ 回到最后一页，不停在空页
    if (productList.totalPages >= 1 && productList.page > productList.totalPages) {
      productList.page = productList.totalPages;
      lastListJson = '';
      return loadProducts(force);
    }

    // 选中项不在本页了（被筛掉 / 页码越界 / 刚下架）→ 退回第一件
    if (!productList.items.some((p) => p.id === selectedProductId)) {
      selectedProductId = productList.items.length ? productList.items[0].id : null;
    }

    const json = JSON.stringify(productList.items) + '|' + selectedProductId + '|' + productList.page + '|' + productList.status;
    if (json !== lastListJson || force) {
      lastListJson = json;
      renderProductList();
    }
    await loadDetail();
  } catch (e) {
    if (e.status === 401) {
      token = '';
      localStorage.removeItem('seller_token');
      showLogin();
    } else {
      toast(e.message, 'err');
    }
  }
}

function renderProductList() {
  const card = $('#productListCard');
  if (!card) return;
  const items = productList.items;

  const rows = items.map((p) => `
    <div class="pl-row${p.id === selectedProductId ? ' active' : ''}" data-pid="${p.id}">
      <div class="pl-main">
        <div class="pl-name">${esc(p.name)}</div>
        <div class="pl-meta">库存 ${p.stock == null ? 0 : p.stock} 件 · 排队 ${p.pendingCount || 0} 人 · 发布于 ${fmtTime(p.createdAt)}</div>
      </div>
      <div class="pl-num"><div class="pl-price">￥${fmtPrice(p.price)}</div></div>
      <div class="pl-state">${productBadge(p)}</div>
    </div>`).join('');

  card.innerHTML = `
    <h2>商品列表 <span class="muted">共 ${productList.total} 件 · 按发布时间倒序</span>
      <button class="btn btn-primary btn-sm" id="openPublish" style="margin-left:auto">＋ 发布新商品</button>
    </h2>
    <div class="pl-filter">
      ${STATUS_FILTERS.map(([v, label]) =>
        `<button data-status="${v}" class="${productList.status === v ? 'active' : ''}">${label}</button>`).join('')}
    </div>
    ${items.length
      ? `<div class="pl-list">${rows}</div>`
      : '<div class="empty" style="padding:18px">没有符合条件的商品</div>'}
    <div class="pager">
      <span>共 ${productList.total} 件 · 每页 ${productList.size} 条 · 第 ${productList.page} / ${productList.totalPages} 页</span>
      <button class="btn btn-ghost btn-sm" id="plPrev" ${productList.page <= 1 ? 'disabled' : ''}>上一页</button>
      <button class="btn btn-ghost btn-sm" id="plNext" ${productList.page >= productList.totalPages ? 'disabled' : ''}>下一页</button>
    </div>
  `;

  $('#openPublish').addEventListener('click', openPublish);
  card.querySelectorAll('[data-pid]').forEach((el) =>
    el.addEventListener('click', () => selectProduct(Number(el.dataset.pid)))
  );
  card.querySelectorAll('[data-status]').forEach((b) =>
    b.addEventListener('click', () => {
      if (productList.status === b.dataset.status) return;
      productList.status = b.dataset.status;
      productList.page = 1;
      lastListJson = '';
      loadProducts(true);
    })
  );
  $('#plPrev').addEventListener('click', () => {
    productList.page = Math.max(1, productList.page - 1);
    lastListJson = '';
    loadProducts(true);
  });
  $('#plNext').addEventListener('click', () => {
    productList.page = Math.min(productList.totalPages, productList.page + 1);
    lastListJson = '';
    loadProducts(true);
  });
}

function selectProduct(id) {
  if (id === selectedProductId) return;
  selectedProductId = id;
  renderProductList(); // 只重画选中态，不发请求
  loadDetail();
}

/* ---------- 发布表单（多商品：随时可发布，不再依赖「无在售商品」） ---------- */
function openPublish() {
  publishOpen = true;
  renderPublishForm();
  const el = $('#pName');
  if (el) el.focus();
}

function renderPublishForm() {
  const card = $('#publishCard');
  if (!card) return;
  if (!publishOpen) {
    card.style.display = 'none';
    card.innerHTML = '';
    return;
  }
  card.style.display = '';
  const leafReady = hasLeafCategory();
  card.innerHTML = `
    <h2>发布新商品 <span class="muted">多件商品可同时发布，各自独立排队</span></h2>
    <div class="form" style="max-width:520px">
      <div>
        <label>商品名称<span class="req">*</span></label>
        <input type="text" id="pName" maxlength="100" placeholder="不超过 100 字">
      </div>
      <div>
        <label>商品类别<span class="req">*</span></label>
        <select id="pCategory" ${leafReady ? '' : 'disabled'}>${leafOptionsHtml('')}</select>
        <div class="hint">只能挂到二级类别${leafReady ? '' : '；当前还没有二级类别，请先到「类别管理」创建'}。</div>
      </div>
      <div>
        <label>库存（件）<span class="req">*</span></label>
        <input type="number" id="pStock" min="1" step="1" value="1">
      </div>
      <div>
        <label>商品描述</label>
        <textarea id="pDesc" maxlength="2000" placeholder="描述成色、交易方式等（选填）"></textarea>
      </div>
      <div>
        <label>价格（元）<span class="req">*</span></label>
        <input type="number" id="pPrice" min="0.01" step="0.01" placeholder="大于 0，保留两位小数">
      </div>
      <div>
        <label>商品图片（选填，最多 9 张，JPG / PNG，单张 ≤ 5MB，第一张为主图）</label>
        <input type="file" id="pImage" accept="image/jpeg,image/png" multiple style="padding:8px">
        <div class="hint">可一次多选；第一张作为列表与详情页主图，其余按选择顺序在买家详情页缩略图里展示。</div>
      </div>
      <div>
        <label>商品详情（富文本，选填，支持 HTML，≤ 8000 字）</label>
        <textarea id="pRich" maxlength="8000" placeholder="可写 HTML，例如 &lt;b&gt;九成新&lt;/b&gt;、&lt;p&gt;无划痕&lt;/p&gt;、&lt;table&gt;参数表&lt;/table&gt;"></textarea>
        <div class="hint">支持 p、b、img、ul、li、h1 至 h6、table 等标签，脚本与事件属性会被过滤；第一张图片请用上方商品图片上传。</div>
      </div>
      <div class="hint">发布后商品名称 / 价格 / 描述不可修改，库存与类别可在下方详情中调整。</div>
      <div class="error" id="pError"></div>
      <div class="actions">
        <button class="btn btn-primary" id="pSubmit" ${leafReady ? '' : 'disabled'}>发布商品</button>
        <button class="btn btn-ghost" id="publishCancel">取消</button>
      </div>
    </div>
  `;
  $('#pSubmit').addEventListener('click', publishProduct);
  $('#publishCancel').addEventListener('click', () => {
    publishOpen = false;
    renderPublishForm();
  });
  // 选完图立刻给数量反馈，别等点「发布商品」才知道超了
  const imgInput = $('#pImage');
  if (imgInput) {
    imgInput.addEventListener('change', () => {
      const n = imgInput.files.length;
      const box = $('#pError');
      if (box) box.textContent = n > 9 ? `已选 ${n} 张，图片最多 9 张` : '';
    });
  }
}

/* ---------- 选中商品的详情 ---------- */
/** 数据没变就不重绘：避免 5 秒轮询把详情卡整块重建（图片闪烁、缩略图选中态被重置） */
function renderDetailIfChanged() {
  const sig = JSON.stringify(selectedDetail);
  if (sig === lastDetailJson) return;
  lastDetailJson = sig;
  renderDetail();
}

async function loadDetail() {
  if (selectedProductId == null) {
    selectedDetail = null;
    renderDetailIfChanged();
    return;
  }
  const id = selectedProductId;
  const row = productList.items.find((p) => p.id === id);
  if (!row) {
    selectedDetail = null;
    renderDetailIfChanged();
    return;
  }

  let intents = [];
  let intentsError = false;
  try {
    const r = await api(`/api/seller/products/${id}/intents`);
    intents = r.items || [];
  } catch (e) {
    // 已经切到别的商品时，别用这次失败覆盖新详情
    if (id !== selectedProductId) return;
    // 不能静默：否则请求失败会被渲染成「暂无买家提交意向」，卖家会误判没人排队
    intentsError = true;
  }
  if (id !== selectedProductId) return;   // 等待期间用户已切换商品，丢弃旧结果

  const images = await resolveProductImages(row);
  if (id !== selectedProductId) return;

  const queue = intents.filter((i) => i.status === 'queued');
  const currentDeal = intents.find((i) => i.status === 'in_transaction') || null;
  // 待处理 = 交易失败且未处置。disposed 由 .../{id}/intents 下发；
  // 字段缺失（未 true）时按未处置展示，重复点由后端「该意向无需处理」拦下，不会写坏数据。
  const pendingFailed = intents.filter((i) => i.status === 'failed' && i.disposed !== true);

  selectedDetail = {
    product: {
      id: row.id,
      name: row.name,
      price: row.price,
      stock: row.stock,
      status: row.status,
      // 冻结类型只认接口下发的 frozenBy，缺失时为 null（徽标降级为「已冻结」）
      frozenBy: row.frozenBy == null ? null : row.frozenBy,
      imageUrl: row.imageUrl || null,
      images,
      description: row.description || '',
      categoryId: row.categoryId == null ? null : row.categoryId,
      publishedAt: row.createdAt,
    },
    queue,
    currentDeal,
    pendingFailed,
    intentsError,
  };
  renderDetailIfChanged();
}

function renderDetail() {
  const card = $('#currentProductCard');
  const queueCard = $('#queueCard');
  const pendingCard = $('#pendingCard');
  const opsCard = $('#opsCard');

  if (!selectedDetail) {
    card.innerHTML = `<h2>商品详情</h2><div class="empty" style="padding:18px">${productList.items.length
      ? '从上方列表选择一件商品查看详情'
      : '还没有商品，点上方「＋ 发布新商品」发布第一件'}</div>`;
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px">暂无在售商品</div>';
    pendingCard.style.display = 'none';
    opsCard.style.display = 'none';
    opsCard.innerHTML = '';
    return;
  }

  const d = selectedDetail;
  const p = d.product;

  // 图集：接口没给 images 时退化为单张主图；无图仍用占位图标
  const pics = Array.isArray(p.images) && p.images.length
    ? p.images
    : (p.imageUrl ? [p.imageUrl] : []);
  const pic = pics.length
    ? `<img src="${esc(pics[0])}" alt="">`
    : `<svg width="70" height="70" viewBox="0 0 24 24" fill="none" stroke="#b8c1d4" stroke-width="1.4"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="M3.5 18l5-5 3.5 3.5L16 12l4.5 4.5"/></svg>`;
  const thumbs = pics.length > 1
    ? `<div class="thumbs">${pics.map((src, i) =>
        `<button type="button" class="thumb${i === 0 ? ' active' : ''}" data-src="${esc(src)}" title="第 ${i + 1} 张 · 共 ${pics.length} 张"><img src="${esc(src)}" alt="第 ${i + 1} 张"><span class="thumb-no">${i + 1}</span></button>`).join('')}</div>`
    : '';
  const picTip = pics.length > 1
    ? `<div class="pic-tip">共 ${pics.length} 张 · 第 1 张为主图</div>`
    : '';

  let dealInfo = '';
  if (d.currentDeal) {
    dealInfo = `
      <div class="deal-banner">
        🤝 当前交易对象（队首）：${esc(d.currentDeal.username || d.currentDeal.name || '—')} · ${esc(d.currentDeal.phone || '—')}
        　<span class="muted">意向提交于 ${fmtTime(d.currentDeal.submittedAt)}</span>
      </div>
      <div class="actions">
        <button class="btn btn-primary" id="markSuccess">✅ 标记交易成功（扣 1 件，有剩继续卖）</button>
        <button class="btn btn-danger" id="markFail">❌ 标记交易失败（自动递补）</button>
      </div>
      <div class="hint" style="margin-top:10px;color:var(--text-2);font-size:12px">提示：交易标记后不可回退，请线下确认后再操作。</div>
    `;
  } else if (p.status === 'on_sale') {
    dealInfo = `
      <div class="actions">
        <button class="btn btn-outline" id="freezeBtn">手动冻结</button>
        <button class="btn btn-ghost" id="offshelfBtn">手动下架</button>
      </div>
      <div class="hint" style="margin-top:10px;color:var(--text-2);font-size:12px">买家提交意向后，系统自动与队首进入交易，无需手动操作。</div>
    `;
  } else if (p.status === 'frozen' && p.frozenBy !== 'auto') {
    // manual（卖家暂停接单），以及 frozenBy 未下发时的降级：都按手动冻结处理，
    // 因为这个分支只在「没有交易中意向」时才会到达（有交易中意向走上面的 currentDeal 分支）
    dealInfo = `
      <div class="actions">
        <button class="btn btn-primary" id="unfreezeBtn">手动解冻（恢复在售）</button>
        <button class="btn btn-ghost" id="offshelfBtn">手动下架</button>
      </div>
    `;
  } else if (p.status === 'sold_out') {
    dealInfo = `
      <div class="hint-banner">商品已售罄：仍留在本列表中，补货 ≥ 1 件即自动恢复在售（下方「库存与类别」可直接补货）。</div>
    `;
  } else if (p.status === 'off_shelf') {
    dealInfo = `
      <div class="actions">
        <button class="btn btn-primary" id="onshelfBtn">重新上架</button>
      </div>
      <div class="hint" style="margin-top:10px;color:var(--text-2);font-size:12px">已下架商品进入历史记录，重新上架后回到在售列表。</div>
    `;
  }

  card.innerHTML = `
    <h2>商品详情 ${productBadge(p)}
      <span class="muted" style="margin-left:auto;font-size:12px;font-weight:400">#${p.id}</span>
    </h2>
    ${dealInfo}
    <div class="divider"></div>
    <div class="product">
      <div class="gallery">
        <div class="pic">${pic}</div>
        ${thumbs}
        ${picTip}
      </div>
      <div class="info">
        <div class="name">${esc(p.name)}</div>
        <div class="price"><small>￥</small>${fmtPrice(p.price)}</div>
        <div class="desc" style="font-size:13px;color:var(--text-2)">库存 ${p.stock == null ? 0 : p.stock} 件　类别：${p.categoryId ? esc(categoryLabel(p.categoryId)) : '—'}</div>
        ${p.description ? `<div class="desc">${esc(p.description)}</div>` : ''}
        <div class="meta">发布时间：${fmtTime(p.publishedAt)}</div>
      </div>
    </div>
  `;

  // 主图加载失败（文件缺失 / 服务端未映射上传目录）降级为文字，不显示浏览器破图图标；
  // 缩略图加载失败只隐藏该张，其余仍可点
  const gallery = card.querySelector('.gallery');
  const picBox = gallery && gallery.querySelector('.pic');
  const bindMainPicError = () => {
    const img = picBox && picBox.querySelector('img');
    if (!img) return;
    img.addEventListener('error', () => {
      picBox.innerHTML = '<span class="muted" style="font-size:12px">图片加载失败</span>';
    });
  };
  bindMainPicError();

  if (gallery && pics.length > 1) {
    const thumbBtns = Array.from(gallery.querySelectorAll('.thumb'));
    const showPic = (idx) => {
      const btn = thumbBtns[idx];
      if (!btn) return;
      thumbBtns.forEach((b, i) => b.classList.toggle('active', i === idx));
      picIndexById.set(p.id, idx);
      picBox.innerHTML = `<img src="${esc(btn.dataset.src)}" alt="">`;
      bindMainPicError();
    };
    thumbBtns.forEach((b, i) => {
      const im = b.querySelector('img');
      if (im) im.addEventListener('error', () => { b.style.visibility = 'hidden'; });
      b.addEventListener('click', () => showPic(i));
    });
    // 5 秒轮询重绘后接着显示上次看的那张，不被打回第一张
    const keep = picIndexById.get(p.id) || 0;
    if (keep > 0) showPic(keep);
  }

  const bind = (sel, fn) => { const el = $(sel); if (el) el.addEventListener('click', fn); };
  bind('#freezeBtn', async () => {
    if (!confirm('确认手动冻结商品？冻结期间不接收新意向，已排队买家保留。')) return;
    await act(`/api/seller/products/${p.id}/freeze`, {}, '商品已手动冻结');
  });
  bind('#unfreezeBtn', () => act(`/api/seller/products/${p.id}/unfreeze`, {}, '商品已恢复在售'));
  bind('#offshelfBtn', async () => {
    if (!confirm('确认手动下架？下架后商品进入历史，队列中意向全部转为失败，不可恢复。')) return;
    await act(`/api/seller/products/${p.id}/offshelf`, {}, '商品已下架');
  });
  bind('#onshelfBtn', async () => {
    if (!confirm('确认重新上架？上架后回到在售列表，可继续接受买家意向。')) return;
    await act(`/api/seller/products/${p.id}/onshelf`, {}, '商品已重新上架');
  });
  bind('#markSuccess', async () => {
    if (!confirm('确认交易成功？扣减 1 件库存，库存归零则转售罄并下架，队列剩余意向自动转为失败。')) return;
    await act(`/api/seller/products/${p.id}/deal/result`, { result: 'success' }, '交易成功');
  });
  bind('#markFail', async () => {
    if (!confirm('确认交易失败？商品将自动恢复，下一位排队买家自动递补进入交易。')) return;
    await act(`/api/seller/products/${p.id}/deal/result`, { result: 'fail' }, '交易失败，已自动递补');
  });

  // 意向队列
  if (d.queue.length || d.currentDeal) {
    const rows = d.queue
      .map((i, idx) => `<tr>
        <td>${i.position || idx + 1}</td><td>${esc(i.username || i.name || '—')}</td><td>${esc(i.phone || '—')}</td>
        <td>${fmtTime(i.submittedAt)}</td><td><span class="badge queued">排队中</span></td>
      </tr>`)
      .join('');
    const dealRow = d.currentDeal
      ? `<tr style="background:#fdf9f0">
          <td>—</td><td>${esc(d.currentDeal.username || d.currentDeal.name || '—')}</td><td>${esc(d.currentDeal.phone || '—')}</td>
          <td>${fmtTime(d.currentDeal.submittedAt)}</td><td><span class="badge deal">交易中</span></td>
        </tr>`
      : '';
    queueCard.innerHTML = `
      <h2>意向购买人 <span class="muted">按提交时间正序 · 先到先得 · 卖家不可自选交易对象</span></h2>
      <table class="table">
        <thead><tr><th>位次</th><th>意向人</th><th>联系电话</th><th>意向提交时间</th><th>状态</th></tr></thead>
        <tbody>${dealRow}${rows}</tbody>
      </table>
    `;
  } else if (d.intentsError) {
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px;color:var(--danger)">意向加载失败，请刷新重试</div>';
  } else {
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px">暂无买家提交意向</div>';
  }

  // 待处理失败意向：该商品有 failed 且未 disposed 的意向就显示。
  // 处置接口是幂等的（ProductService.dispose 对非 FAILED 或已处置直接抛「该意向无需处理」），
  // 所以允许重复点，由后端拦，前端不做二次判定，避免漏掉其他商品的失败意向。
  if (d.pendingFailed.length) {
    pendingCard.style.display = '';
    pendingCard.innerHTML = `
      <h2>待处理：交易失败的买家 <span class="muted">确认「作废」或「重新排队」（重新排队将排到队尾）</span></h2>
      <table class="table">
        <thead><tr><th>意向人</th><th>联系电话</th><th>原提交时间</th><th>操作</th></tr></thead>
        <tbody>
          ${d.pendingFailed.map((i) => `<tr>
            <td>${esc(i.username || i.name || '—')}</td><td>${esc(i.phone || '—')}</td><td>${fmtTime(i.submittedAt)}</td>
            <td style="white-space:nowrap">
              <button class="btn btn-outline btn-sm" data-requeue="${i.id != null ? i.id : i.intentId}">重新排队</button>
              <button class="btn btn-ghost btn-sm" data-void="${i.id != null ? i.id : i.intentId}">作废</button>
            </td>
          </tr>`).join('')}
        </tbody>
      </table>
    `;
    pendingCard.querySelectorAll('[data-requeue]').forEach((b) =>
      b.addEventListener('click', async () => {
        if (!confirm('确认让该买家重新排队？重新提交时间，排到队尾。')) return;
        await act(`/api/seller/intents/${b.dataset.requeue}/dispose`, { action: 'requeue' }, '已重新排队');
      })
    );
    pendingCard.querySelectorAll('[data-void]').forEach((b) =>
      b.addEventListener('click', async () => {
        if (!confirm('确认作废该意向？作废后买家需重新提交。')) return;
        await act(`/api/seller/intents/${b.dataset.void}/dispose`, { action: 'void' }, '意向已作废');
      })
    );
  } else {
    pendingCard.style.display = 'none';
  }

  // 库存 / 改挂类别 / 全部意向
  renderOps({ detail: d });
}

/* ---------- 当前商品：库存 / 类别 / 全部意向 ---------- */
/**
 * 渲染「库存与类别」卡片。
 * 输入框正在编辑时不重绘（轮询每 5 秒一次，避免吞掉用户输入）。
 */
function renderOps(data) {
  const card = $('#opsCard');
  if (!data.detail) {
    card.style.display = 'none';
    card.innerHTML = '';
    return;
  }
  // 只保护「正在输入的控件」：若焦点停在「保存库存 / 保存类别」按钮上，保存后仍需重绘，
  // 否则卡片会一直显示旧库存/旧类别，直到用户点别处触发下一次轮询
  const ae = document.activeElement;
  if (ae && card.contains(ae) && /^(INPUT|SELECT|TEXTAREA)$/.test(ae.tagName)) return;

  const p = data.detail.product;
  // 已下架商品：后端拒绝调库存 / 改类别，界面同步锁住
  const locked = p.status === 'off_shelf';
  // 当前类别取自列表项 categoryId，保存后重拉列表即刷新；
  // 老数据没挂类别时（categoryId 为 null）显示「—」，下拉从「请选择」开始
  const catKnown = p.categoryId != null;
  card.style.display = '';
  card.innerHTML = `
    <h2>库存与类别 <span class="muted">调整即时生效，可随时改回</span></h2>
    <div class="ops">
      <div class="ops-field">
        <label>库存（当前 ${p.stock == null ? 0 : p.stock} 件）</label>
        <div class="ops-line">
          <input type="number" id="stockInput" min="0" step="1" value="${p.stock == null ? 0 : p.stock}" ${locked ? 'disabled' : ''}>
          <button class="btn btn-outline btn-sm" id="stockSave" ${locked ? 'disabled' : ''}>保存库存</button>
        </div>
        <input type="text" id="stockReason" maxlength="200" placeholder="调整原因（选填，如：补货 / 盘点）"
               ${locked ? 'disabled' : ''}
               style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:10px;font-size:13px;font-family:inherit;background:#fbfcfe;outline:none;color:var(--text)">
        <div class="ops-hint">${locked
          ? '已下架商品不可调整库存，请先在「历史记录」中重新上架。'
          : '调到 0 → 商品转为「售罄」，仍留在商品列表；补货 ≥ 1 自动恢复在售。'}</div>
      </div>
      <div class="ops-field">
        <label>所属类别（当前 ${catKnown ? esc(categoryLabel(p.categoryId)) : '—'}）</label>
        <div class="ops-line">
          <select id="catSelect" ${locked ? 'disabled' : ''}>${leafOptionsHtml(catKnown ? p.categoryId : '')}</select>
          <button class="btn btn-outline btn-sm" id="catSave" ${locked ? 'disabled' : ''}>保存类别</button>
        </div>
        <div class="ops-hint">${catKnown
          ? '只能挂到二级类别；新增类别请到「类别管理」。'
          : (locked
            ? '已下架商品不可改类别，请先在「历史记录」中重新上架。'
            : '该商品尚未挂类别；选择一个二级类别并保存即可。')}</div>
      </div>
    </div>
    <div class="actions">
      <button class="btn btn-ghost btn-sm" id="allIntentsBtn">查看该商品全部意向（含终态）</button>
    </div>
  `;

  $('#stockSave').addEventListener('click', () => saveStock(p));
  $('#catSave').addEventListener('click', () => saveCategory(p));
  $('#allIntentsBtn').addEventListener('click', () => showProductIntents(p));
}

/** PUT /api/seller/products/{id}/stock —— 传目标库存（非增量） */
async function saveStock(product) {
  const raw = $('#stockInput').value;
  const stock = Number(raw);
  if (!Number.isInteger(stock) || stock < 0) return toast('库存必须是 0 或正整数', 'err');
  if (stock === product.stock) return toast('库存未变化', '');
  if (stock === 0) {
    const ok = confirm('库存调为 0 后商品将转为「售罄」并留在当前商品卡片（队列中的意向同时置为售罄），补货 ≥ 1 自动恢复在售。确认继续？');
    if (!ok) return;
  }
  const reason = ($('#stockReason') && $('#stockReason').value.trim()) || null;
  try {
    const data = await api(`/api/seller/products/${product.id}/stock`, {
      method: 'PUT',
      body: reason ? { stock, reason } : { stock },
    });
    toast(`库存已更新为 ${data.stock} 件`, 'ok');
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

/** PUT /api/seller/products/{id}/category —— 改挂二级类别 */
async function saveCategory(product) {
  const val = $('#catSelect').value;
  const categoryId = Number(val);
  if (!val || !categoryId) return toast('请选择二级类别', 'err');
  if (String(categoryId) === String(product.categoryId)) return toast('类别未变化', '');
  try {
    await api(`/api/seller/products/${product.id}/category`, { method: 'PUT', body: { categoryId } });
    toast('类别已更新', 'ok');
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

const INTENT_STATUS = {
  queued: ['排队中', 'queued'],
  in_transaction: ['交易中', 'deal'],
  success: ['成功', 'success'],
  failed: ['失败', 'failed'],
  sold_out: ['售罄', 'off'],
  cancelled: ['已撤销', 'cancelled'],
  archived: ['已归档', 'off'],
};

/** GET /api/seller/products/{id}/intents —— 该商品全部意向（含终态） */
async function showProductIntents(product) {
  try {
    const data = await api(`/api/seller/products/${product.id}/intents`);
    const rows = (data.items || []).map((i) => {
      const [txt, cls] = INTENT_STATUS[i.status] || [i.status, 'off'];
      return `<tr>
        <td>${i.position == null ? '—' : i.position}</td>
        <td>${esc(i.username || '—')}</td>
        <td>${esc(i.phone || '—')}</td>
        <td>${fmtTime(i.submittedAt)}</td>
        <td>${fmtTime(i.dealtAt)}</td>
        <td><span class="badge ${cls}">${txt}</span></td>
      </tr>`;
    }).join('');
    const body = data.total
      ? `<div class="kv"><span class="k">商品</span><span>${esc(product.name)}</span></div>
         <div class="divider"></div>
         <table class="table">
           <thead><tr><th>位次</th><th>买家</th><th>联系电话</th><th>提交时间</th><th>交易时间</th><th>状态</th></tr></thead>
           <tbody>${rows}</tbody>
         </table>`
      : '<div class="empty" style="padding:18px">该商品还没有任何意向</div>';
    openDetail(`「${product.name}」全部意向（共 ${data.total} 笔）`, body, '');
  } catch (e) {
    toast(e.message, 'err');
  }
}

/* ---------- 通用只读明细弹窗 ---------- */
function openDetail(title, bodyHtml, pagerHtml) {
  $('#detailTitle').textContent = title;
  $('#detailBody').innerHTML = bodyHtml;
  const pager = $('#detailPager');
  if (pagerHtml) {
    pager.style.display = '';
    pager.innerHTML = pagerHtml;
  } else {
    pager.style.display = 'none';
    pager.innerHTML = '';
  }
  $('#detailMask').classList.add('show');
}

async function act(path, body, okMsg) {
  try {
    await api(path, { method: 'POST', body });
    toast(okMsg, 'ok');
    loadProducts(true);
    if (path.includes('offshelf') || path.includes('result')) loadHistory();
  } catch (e) {
    toast(e.message, 'err');
    if (e.status === 401) {
      token = '';
      localStorage.removeItem('seller_token');
      showLogin();
    } else {
      loadProducts(true);
    }
  }
}

/* ---------- 发布商品 ---------- */
// 提交流程锁：9 张图读 base64 + 请求可能要好几秒，期间按钮不禁用会被连点发出多件重复商品
let publishing = false;

async function publishProduct() {
  if (publishing) return;
  const name = $('#pName').value.trim();
  const description = $('#pDesc').value.trim();
  const price = $('#pPrice').value;
  const categoryVal = $('#pCategory') ? $('#pCategory').value : '';
  const stockVal = $('#pStock') ? $('#pStock').value : '';
  const err = $('#pError');
  err.textContent = '';
  if (!name) return (err.textContent = '商品名称必填');
  if (!categoryVal) return (err.textContent = '商品类别必填（只能挂到二级类别）');
  const stockNum = Number(stockVal);
  if (!Number.isInteger(stockNum) || stockNum < 1) return (err.textContent = '库存必须大于或等于 1');
  const priceNum = Number(price);
  if (!Number.isFinite(priceNum) || priceNum <= 0) return (err.textContent = '价格必须为大于 0 的数字');

  // 多图：逐张校验后按选择顺序读成 base64 数组；数组顺序即 sortOrder，第 1 张为主图
  const files = Array.from($('#pImage').files || []);
  if (files.length > 9) return (err.textContent = `已选 ${files.length} 张，图片最多 9 张`);

  publishing = true;
  const submitBtn = $('#pSubmit');
  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = '发布中…';
  }
  try {
    const images = [];
    for (const f of files) {
      if (!['image/jpeg', 'image/png'].includes(f.type)) throw new Error(`第 ${images.length + 1} 张格式仅支持 JPG / PNG`);
      if (f.size > 5 * 1024 * 1024) throw new Error(`第 ${images.length + 1} 张超过 5MB`);
      images.push(await new Promise((resolve, reject) => {
        const r = new FileReader();
        r.onload = () => resolve(r.result);
        // FileReader 失败（文件被移走 / 权限）过去会变成 unhandled rejection，界面毫无反馈
        r.onerror = () => reject(new Error(`第 ${images.length + 1} 张读取失败，请重新选择`));
        r.readAsDataURL(f);
      }));
    }

    // 契约（ProductPublishRequest）：categoryId 必填二级类别、stock ≥1、images 为 base64 数组
    // 返回 {ok, id}，直接用 id 选中新商品，无需按第一行猜
    const created = await api('/api/seller/products', {
      method: 'POST',
      body: {
        name,
        description,
        price: priceNum,
        stock: stockNum,
        categoryId: Number(categoryVal),
        // 富文本详情：空串按 null 提交，后端 ProductService 会把 null 转成空串落库
        richDescription: (($('#pRich') && $('#pRich').value) || '').trim() || null,
        images,
      },
    });
    toast('商品已发布，进入在售', 'ok');
    publishOpen = false;
    renderPublishForm(); // 表单整块重建，下面的 finally 不再碰旧按钮
    productList.page = 1;
    productList.status = 'all';
    selectedProductId = created && created.id != null ? created.id : null;
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    err.textContent = e.message;
  } finally {
    publishing = false;
    if (submitBtn && submitBtn.isConnected) {
      submitBtn.disabled = false;
      submitBtn.textContent = '发布商品';
    }
  }
}

/* ---------- 历史 ---------- */
let historyPage = 1;

async function loadHistory() {
  try {
    const data = await api('/api/seller/history?page=' + historyPage);
    historyPage = data.page;
    renderHistory(data);
  } catch (e) {
    toast(e.message, 'err');
  }
}

function renderHistory(data) {
  const card = $('#historyCard');
  if (!data.items.length) {
    card.innerHTML = '<h2>历史商品</h2><div class="empty">暂无历史记录</div>';
    return;
  }
  card.innerHTML = `
    <h2>历史商品 <span class="muted">已下架档案 · 只读，不可修改；可「重新上架」恢复在售</span></h2>
    <table class="table">
      <thead><tr><th>名称</th><th>价格</th><th>发布时间</th><th>交易时间</th><th>意向数</th><th></th></tr></thead>
      <tbody>
        ${data.items.map((h) => `<tr class="clickable" data-hist="${h.id}">
          <td>${esc(h.name)}</td>
          <td>￥${fmtPrice(h.price)}</td>
          <td>${fmtTime(h.publishedAt)}</td>
          <td>${fmtTime(h.closedAt)}</td>
          <td>${h.intentCount}</td>
          <td style="white-space:nowrap">
            <button class="btn btn-outline btn-sm" data-view="${h.id}">查看意向</button>
            <button class="btn btn-ghost btn-sm" data-onshelf="${h.id}" data-name="${esc(h.name)}">重新上架</button>
          </td>
        </tr>`).join('')}
      </tbody>
    </table>
    <div class="pager">
      <span>共 ${data.total} 件 · 第 ${data.page} / ${data.totalPages} 页</span>
      <button class="btn btn-ghost btn-sm" id="prevPage" ${data.page <= 1 ? 'disabled' : ''}>上一页</button>
      <button class="btn btn-ghost btn-sm" id="nextPage" ${data.page >= data.totalPages ? 'disabled' : ''}>下一页</button>
    </div>
  `;
  card.querySelectorAll('[data-hist]').forEach((tr) =>
    tr.addEventListener('click', () => showHistoryDetail(Number(tr.dataset.hist)))
  );
  // 行内操作按钮：阻止冒泡，避免同时触发整行的「查看意向」
  card.querySelectorAll('[data-view]').forEach((b) =>
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      showHistoryDetail(Number(b.dataset.view));
    })
  );
  card.querySelectorAll('[data-onshelf]').forEach((b) =>
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      onshelfProduct(Number(b.dataset.onshelf), b.dataset.name);
    })
  );
  const prev = $('#prevPage');
  const next = $('#nextPage');
  if (prev) prev.addEventListener('click', () => { historyPage--; loadHistory(); });
  if (next) next.addEventListener('click', () => { historyPage++; loadHistory(); });
}

const STATUS_TEXT = {
  success: ['成功', 'success'],
  cancelled: ['已撤销', 'cancelled'],
  failed: ['失败', 'failed'],
};

async function showHistoryDetail(productId) {
  try {
    const data = await api('/api/seller/history/' + productId);
    $('#histTitle').textContent = `「${data.product.name}」意向购买人`;
    $('#histDetail').innerHTML = `
      <div class="kv"><span class="k">价格</span><span>￥${fmtPrice(data.product.price)}</span></div>
      <div class="kv"><span class="k">发布时间</span><span>${fmtTime(data.product.publishedAt)}</span></div>
      <div class="kv"><span class="k">交易时间</span><span>${fmtTime(data.product.closedAt)}</span></div>
      ${data.product.description ? `<div class="kv"><span class="k">描述</span><span>${esc(data.product.description)}</span></div>` : ''}
      <div class="divider"></div>
      ${data.intents.length ? `
        <table class="table">
          <thead><tr><th>姓名</th><th>联系电话</th><th>意向提交时间</th><th>终态</th></tr></thead>
          <tbody>
            ${data.intents.map((i) => {
              const [txt, cls] = STATUS_TEXT[i.status] || [i.status, 'off'];
              return `<tr>
                <td>${esc(i.name)}</td><td>${esc(i.phone)}</td><td>${fmtTime(i.submittedAt)}</td>
                <td><span class="badge ${cls}">${txt}</span></td>
              </tr>`;
            }).join('')}
          </tbody>
        </table>` : '<div class="empty" style="padding:18px">该商品无任何意向记录</div>'}
    `;
    $('#histMask').classList.add('show');
  } catch (e) {
    toast(e.message, 'err');
  }
}

/** POST /api/seller/products/{id}/onshelf —— 仅手动下架（off_shelf）商品可重新上架 */
async function onshelfProduct(id, name) {
  if (!confirm(`确认重新上架「${name}」？上架后回到「当前商品」，可继续接受买家意向。`)) return;
  try {
    await api(`/api/seller/products/${id}/onshelf`, { method: 'POST', body: {} });
    toast('商品已重新上架', 'ok');
    lastListJson = '';
    loadHistory();
    loadProducts(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

/* ---------- 类别管理（A.1.2：两级类别的增 / 改名 / 删） ---------- */
async function loadCategoryTree() {
  await loadCategories(true);
  renderCategoryForm();
  renderCategoryTree();
}

function renderCategoryForm() {
  const card = $('#categoryFormCard');
  const parents = categoryTree.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
  card.innerHTML = `
    <h2>新增类别 <span class="muted">两级结构 · 同级不允许重名</span></h2>
    <div class="form" style="max-width:520px">
      <div>
        <label>类别名称<span class="req">*</span></label>
        <input type="text" id="catNewName" maxlength="50" placeholder="不超过 50 字">
      </div>
      <div>
        <label>父级类别</label>
        <select id="catNewParent">
          <option value="">（无 · 新建一级类别）</option>
          ${parents}
        </select>
        <div class="hint">选「（无）」建一级类别；选某个一级类别则在其下建二级类别。</div>
      </div>
      <div class="error" id="catNewError"></div>
      <button class="btn btn-primary" id="catNewSubmit">新增类别</button>
    </div>
  `;
  $('#catNewSubmit').addEventListener('click', createCategory);
  $('#catNewName').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') createCategory();
  });
}

function renderCategoryTree() {
  const card = $('#categoryTreeCard');
  if (!categoryTree.length) {
    card.innerHTML = '<h2>类别树</h2><div class="empty">暂无类别，请先新增一级类别</div>';
    return;
  }
  const leafCount = categoryTree.reduce((s, c) => s + (c.children || []).length, 0);
  const nodes = categoryTree
    .map((c) => {
      const kids = c.children || [];
      const childHtml = kids.length
        ? kids
            .map(
              (k) => `
        <div class="cat-child" data-id="${k.id}" data-name="${esc(k.name)}">
          <span class="cat-tag l2">二级</span>
          <span class="cat-title">${esc(k.name)}</span>
          <span class="cat-actions">
            <button class="btn btn-ghost btn-sm" data-act="rename">改名</button>
            <button class="btn btn-ghost btn-sm" data-act="del">删除</button>
          </span>
        </div>`
            )
            .join('')
        : '<div class="cat-child"><span class="cat-title muted">暂无二级类别</span></div>';
      return `
      <div class="cat-node" data-id="${c.id}" data-name="${esc(c.name)}">
        <div class="cat-line">
          <span class="cat-tag">一级</span>
          <span class="cat-title">${esc(c.name)}</span>
          <span class="cat-count">${kids.length} 个二级类别</span>
          <span class="cat-actions">
            <button class="btn btn-ghost btn-sm" data-act="rename">改名</button>
            <button class="btn btn-ghost btn-sm" data-act="del">删除</button>
          </span>
        </div>
        <div class="cat-children">${childHtml}</div>
      </div>`;
    })
    .join('');

  card.innerHTML = `
    <h2>类别树 <span class="muted">共 ${categoryTree.length} 个一级 · ${leafCount} 个二级 · 有子类别或已挂商品时后端会拒绝删除</span></h2>
    <div class="cat-tree">${nodes}</div>
  `;
  card.querySelectorAll('[data-act]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const holder = btn.closest('[data-id]');
      const id = Number(holder.dataset.id);
      const name = holder.dataset.name;
      if (btn.dataset.act === 'rename') startRenameCat(holder, id, name);
      else deleteCategory(id, name);
    });
  });
}

/** 行内改名：把该节点的名称换成输入框 */
function startRenameCat(holder, id, name) {
  const titleEl = holder.querySelector('.cat-title');
  if (!titleEl || holder.querySelector('.cat-rename-input')) return;
  titleEl.outerHTML = `<input class="cat-rename-input" value="${esc(name)}" maxlength="50">`;
  const input = holder.querySelector('.cat-rename-input');
  const actions = holder.querySelector('.cat-actions');
  actions.innerHTML = `
    <button class="btn btn-outline btn-sm" data-save>保存</button>
    <button class="btn btn-ghost btn-sm" data-cancel>取消</button>`;
  input.focus();
  input.select();
  const submit = () => renameCategory(id, input.value);
  actions.querySelector('[data-save]').addEventListener('click', submit);
  actions.querySelector('[data-cancel]').addEventListener('click', () => renderCategoryTree());
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submit();
    if (e.key === 'Escape') renderCategoryTree();
  });
}

/** POST /api/seller/categories —— parentId 为空建一级，非空建二级 */
async function createCategory() {
  const name = $('#catNewName').value.trim();
  const parentVal = $('#catNewParent').value;
  const err = $('#catNewError');
  err.textContent = '';
  if (!name) return (err.textContent = '类别名称必填');
  try {
    await api('/api/seller/categories', {
      method: 'POST',
      body: { name, parentId: parentVal ? Number(parentVal) : null },
    });
    toast(parentVal ? '二级类别已创建' : '一级类别已创建', 'ok');
    $('#catNewName').value = '';
    await loadCategoryTree();
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    err.textContent = e.message;
  }
}

/** PUT /api/seller/categories/{id} —— 仅改名 */
async function renameCategory(id, name) {
  const n = String(name || '').trim();
  if (!n) return toast('类别名称必填', 'err');
  try {
    await api(`/api/seller/categories/${id}`, { method: 'PUT', body: { name: n } });
    toast('类别已改名', 'ok');
    await loadCategoryTree();
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

/** DELETE /api/seller/categories/{id} —— 失败时原样提示后端文案 */
async function deleteCategory(id, name) {
  if (!confirm(`确认删除类别「${name}」？\n若该类下还有子类别或商品，后端会拒绝并给出原因。`)) return;
  try {
    await api(`/api/seller/categories/${id}`, { method: 'DELETE' });
    toast('类别已删除', 'ok');
    await loadCategoryTree();
    lastListJson = '';
    loadProducts(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

/* ---------- 客户（按客户看购买历史） ---------- */
let customers = [];
let customerPage = 1;
const CUSTOMER_PAGE_SIZE = 10;

/** GET /api/seller/customers —— 接口返回全量（无分页参数），此处按 10 条/页本地分页 */
async function loadCustomers() {
  try {
    const data = await api('/api/seller/customers');
    customers = data.items || [];
    customerPage = 1;
    renderCustomers();
  } catch (e) {
    toast(e.message, 'err');
  }
}

function renderCustomers() {
  const card = $('#customersCard');
  if (!customers.length) {
    card.innerHTML = '<h2>客户</h2><div class="empty">暂无注册买家</div>';
    return;
  }
  const totalPages = Math.max(1, Math.ceil(customers.length / CUSTOMER_PAGE_SIZE));
  customerPage = Math.min(Math.max(1, customerPage), totalPages);
  const start = (customerPage - 1) * CUSTOMER_PAGE_SIZE;
  const rows = customers
    .slice(start, start + CUSTOMER_PAGE_SIZE)
    .map(
      (b) => `<tr class="clickable" data-buyer="${b.id}" data-name="${esc(b.username)}">
        <td>${esc(b.username)}</td>
        <td>${esc(b.phone)}</td>
        <td>${b.defaultLocation ? esc(b.defaultLocation) : '<span class="muted">未填写</span>'}</td>
        <td>${fmtTime(b.createdAt)}</td>
        <td><button class="btn btn-outline btn-sm" data-detail="${b.id}">查看意向流水</button></td>
      </tr>`
    )
    .join('');

  card.innerHTML = `
    <h2>客户 <span class="muted">共 ${customers.length} 位 · 按注册时间倒序 · 每页 ${CUSTOMER_PAGE_SIZE} 位</span></h2>
    <table class="table">
      <thead><tr><th>用户名</th><th>联系电话</th><th>默认交易地点</th><th>注册时间</th><th></th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
    <div class="pager">
      <span>第 ${customerPage} / ${totalPages} 页</span>
      <button class="btn btn-ghost btn-sm" id="cusPrev" ${customerPage <= 1 ? 'disabled' : ''}>上一页</button>
      <button class="btn btn-ghost btn-sm" id="cusNext" ${customerPage >= totalPages ? 'disabled' : ''}>下一页</button>
    </div>
  `;

  const open = (id, name) => showCustomerHistory(id, name, 1);
  card.querySelectorAll('[data-buyer]').forEach((tr) =>
    tr.addEventListener('click', () => open(Number(tr.dataset.buyer), tr.dataset.name))
  );
  card.querySelectorAll('[data-detail]').forEach((b) =>
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      const tr = b.closest('[data-buyer]');
      open(Number(b.dataset.detail), tr.dataset.name);
    })
  );
  const prev = $('#cusPrev');
  const next = $('#cusNext');
  if (prev) prev.addEventListener('click', () => { customerPage--; renderCustomers(); });
  if (next) next.addEventListener('click', () => { customerPage++; renderCustomers(); });
}

/** GET /api/seller/customers/{id}/history?page=n —— 后端已分页，10 条/页 */
async function showCustomerHistory(buyerId, username, page) {
  try {
    const data = await api(`/api/seller/customers/${buyerId}/history?page=${page || 1}`);
    const rows = (data.items || [])
      .map((i) => {
        const [txt, cls] = INTENT_STATUS[i.status] || [i.status, 'off'];
        return `<tr>
          <td>${esc(i.productName || '—')}</td>
          <td>${fmtTime(i.submittedAt)}</td>
          <td>${fmtTime(i.dealtAt)}</td>
          <td><span class="badge ${cls}">${txt}</span></td>
        </tr>`;
      })
      .join('');
    const body = data.total
      ? `<table class="table">
           <thead><tr><th>商品</th><th>提交时间</th><th>交易时间</th><th>交易结果</th></tr></thead>
           <tbody>${rows}</tbody>
         </table>`
      : '<div class="empty" style="padding:18px">该客户还没有提交过意向</div>';
    const pagerHtml = data.totalPages > 1
      ? `<span>共 ${data.total} 条 · 第 ${data.page} / ${data.totalPages} 页</span>
         <button class="btn btn-ghost btn-sm" data-page="${data.page - 1}" ${data.page <= 1 ? 'disabled' : ''}>上一页</button>
         <button class="btn btn-ghost btn-sm" data-page="${data.page + 1}" ${data.page >= data.totalPages ? 'disabled' : ''}>下一页</button>`
      : '';
    openDetail(`「${username}」意向流水`, body, pagerHtml);
    $('#detailPager')
      .querySelectorAll('[data-page]')
      .forEach((b) =>
        b.addEventListener('click', () => showCustomerHistory(buyerId, username, Number(b.dataset.page)))
      );
  } catch (e) {
    toast(e.message, 'err');
  }
}

/* ---------- 修改密码 ---------- */
async function changePassword() {
  const oldPassword = $('#oldPwd').value;
  const newPassword = $('#newPwd').value;
  const newPassword2 = $('#newPwd2').value;
  const err = $('#pwdError');
  err.textContent = '';
  if (!oldPassword || !newPassword) return (err.textContent = '请填写完整');
  if (newPassword.length < 8) return (err.textContent = '新密码长度不能少于 8 位');
  if (newPassword !== newPassword2) return (err.textContent = '两次输入的新密码不一致');
  try {
    await api('/api/seller/password', { method: 'POST', body: { oldPassword, newPassword } });
    $('#pwdMask').classList.remove('show');
    toast('密码修改成功', 'ok');
  } catch (e) {
    err.textContent = e.message;
  }
}

/* ---------- Tab 切换 & 事件 ---------- */
const TABS = ['current', 'categories', 'customers', 'history'];
let currentTab = 'current';

function switchTab(name) {
  if (!TABS.includes(name)) return;
  currentTab = name;
  document.querySelectorAll('.tabs button').forEach((x) => x.classList.toggle('active', x.dataset.tab === name));
  TABS.forEach((t) => {
    const el = $('#tab-' + t);
    if (el) el.style.display = t === name ? '' : 'none';
  });
  if (name === 'current') loadProducts(false);
  if (name === 'history') loadHistory();
  if (name === 'categories') loadCategoryTree();
  if (name === 'customers') loadCustomers();
}

document.querySelectorAll('.tabs button').forEach((b) =>
  b.addEventListener('click', () => switchTab(b.dataset.tab))
);

$('#loginBtn').addEventListener('click', login);
$('#loginPass').addEventListener('keydown', (e) => e.key === 'Enter' && login());
$('#changePwdLink').addEventListener('click', () => {
  $('#oldPwd').value = '';
  $('#newPwd').value = '';
  $('#newPwd2').value = '';
  $('#pwdError').textContent = '';
  $('#pwdMask').classList.add('show');
});
$('#pwdSubmit').addEventListener('click', changePassword);
document.querySelectorAll('[data-close]').forEach((btn) =>
  btn.addEventListener('click', () => $('#' + btn.dataset.close).classList.remove('show'))
);
document.querySelectorAll('.modal-mask').forEach((mask) =>
  mask.addEventListener('click', (e) => {
    if (e.target === mask) mask.classList.remove('show');
  })
);

if (token) enterAdmin();
else showLogin();

setInterval(() => {
  if (token && $('#adminBody').style.display !== 'none' && currentTab === 'current') {
    loadProducts(false);
  }
}, 5000);
