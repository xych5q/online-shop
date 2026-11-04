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
  loadOverview();
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

/* ---------- 当前商品 ---------- */
let overview = null;
let lastOverviewJson = '';

/** 发布表单是否正在填写（有内容时避免自动刷新清空输入） */
function publishFormDirty() {
  const name = $('#pName');
  if (!name) return false;
  return !!(name.value.trim() || ($('#pDesc') && $('#pDesc').value.trim())
    || ($('#pPrice') && $('#pPrice').value) || ($('#pImage') && $('#pImage').files.length)
    || ($('#pCategory') && $('#pCategory').value) || ($('#pStock') && $('#pStock').value !== '1'));
}

async function loadOverview() {
  try {
    overview = await api('/api/seller/overview');
    const json = JSON.stringify(overview);
    if (json === lastOverviewJson) return; // 数据无变化，不重渲染（保护表单输入）
    lastOverviewJson = json;
    renderOverview(overview);
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

function renderOverview(data) {
  const card = $('#currentProductCard');
  const queueCard = $('#queueCard');
  const pendingCard = $('#pendingCard');

  if (!data.detail) {
    // 发布表单正在填写时，不要因轮询刷新而清空输入
    if (publishFormDirty()) return;
    const leafReady = hasLeafCategory();
    card.innerHTML = `
      <h2>发布新商品 <span class="muted">同一时刻仅可有一件在售</span></h2>
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
          <label>商品图片（选填，最多 1 张，JPG / PNG，≤ 5MB）</label>
          <input type="file" id="pImage" accept="image/jpeg,image/png" style="padding:8px">
          <div class="hint" id="imgHint">发布后商品名称/价格/描述不可修改，库存与类别可在下方卡片中调整。</div>
        </div>
        <div class="error" id="pError"></div>
        <button class="btn btn-primary" id="pSubmit" ${leafReady ? '' : 'disabled'}>发布商品</button>
      </div>
    `;
    $('#pSubmit').addEventListener('click', publishProduct);
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px">暂无在售商品</div>';
    pendingCard.style.display = 'none';
    renderOps(data);
    return;
  }

  const d = data.detail;
  const p = d.product;
  const statusBadge = p.status === 'on_sale'
    ? '<span class="badge on">在售</span>'
    : p.frozenBy === 'manual'
      ? '<span class="badge frozen">已冻结（手动）</span>'
      : '<span class="badge deal">已冻结（交易中）</span>';

  const pic = p.imageUrl
    ? `<img src="${esc(p.imageUrl)}" alt="">`
    : `<svg width="70" height="70" viewBox="0 0 24 24" fill="none" stroke="#b8c1d4" stroke-width="1.4"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="M3.5 18l5-5 3.5 3.5L16 12l4.5 4.5"/></svg>`;

  let dealInfo = '';
  if (d.currentDeal) {
    dealInfo = `
      <div class="deal-banner">
        🤝 当前交易对象（队首）：${esc(d.currentDeal.name)} · ${esc(d.currentDeal.phone)}
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
  } else if (p.frozenBy === 'manual') {
    dealInfo = `
      <div class="actions">
        <button class="btn btn-primary" id="unfreezeBtn">手动解冻（恢复在售）</button>
        <button class="btn btn-ghost" id="offshelfBtn">手动下架</button>
      </div>
    `;
  }

  card.innerHTML = `
    <h2>当前商品 ${statusBadge}</h2>
    ${dealInfo}
    <div class="divider"></div>
    <div class="product">
      <div class="pic">${pic}</div>
      <div class="info">
        <div class="name">${esc(p.name)}</div>
        <div class="price"><small>￥</small>${fmtPrice(p.price)}</div>
        ${p.description ? `<div class="desc">${esc(p.description)}</div>` : ''}
        <div class="meta">发布时间：${fmtTime(p.publishedAt)}${p.frozenAt ? ' · 冻结时间：' + fmtTime(p.frozenAt) : ''}</div>
      </div>
    </div>
  `;

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
  bind('#markSuccess', async () => {
    if (!confirm('确认交易成功？商品将永久下架进入历史，队列剩余意向自动转为失败。此操作不可回退。')) return;
    await act(`/api/seller/products/${p.id}/deal/result`, { result: 'success' }, '交易成功，商品已下架');
  });
  bind('#markFail', async () => {
    if (!confirm('确认交易失败？商品将自动恢复，下一位排队买家自动递补进入交易。')) return;
    await act(`/api/seller/products/${p.id}/deal/result`, { result: 'fail' }, '交易失败，已自动递补');
  });

  // 意向队列
  if (d.queue.length || d.currentDeal) {
    const rows = d.queue
      .map((i) => `<tr>
        <td>${i.position}</td><td>${esc(i.name)}</td><td>${esc(i.phone)}</td>
        <td>${fmtTime(i.submittedAt)}</td><td><span class="badge queued">排队中</span></td>
      </tr>`)
      .join('');
    const dealRow = d.currentDeal
      ? `<tr style="background:#fdf9f0">
          <td>—</td><td>${esc(d.currentDeal.name)}</td><td>${esc(d.currentDeal.phone)}</td>
          <td>${fmtTime(d.currentDeal.submittedAt)}</td><td><span class="badge deal">交易中</span></td>
        </tr>`
      : '';
    queueCard.innerHTML = `
      <h2>意向购买人 <span class="muted">按提交时间正序 · 先到先得 · 卖家不可自选交易对象</span></h2>
      <table class="table">
        <thead><tr><th>位次</th><th>姓名</th><th>联系电话</th><th>意向提交时间</th><th>状态</th></tr></thead>
        <tbody>${dealRow}${rows}</tbody>
      </table>
    `;
  } else {
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px">暂无买家提交意向</div>';
  }

  // 待处理失败意向
  if (d.pendingFailed.length) {
    pendingCard.style.display = '';
    pendingCard.innerHTML = `
      <h2>待处理：交易失败的买家 <span class="muted">确认「作废」或「重新排队」（重新排队将排到队尾，口令码不变）</span></h2>
      <table class="table">
        <thead><tr><th>姓名</th><th>联系电话</th><th>原提交时间</th><th>操作</th></tr></thead>
        <tbody>
          ${d.pendingFailed.map((i) => `<tr>
            <td>${esc(i.name)}</td><td>${esc(i.phone)}</td><td>${fmtTime(i.submittedAt)}</td>
            <td style="white-space:nowrap">
              <button class="btn btn-outline btn-sm" data-requeue="${i.id}">重新排队</button>
              <button class="btn btn-ghost btn-sm" data-void="${i.id}">作废</button>
            </td>
          </tr>`).join('')}
        </tbody>
      </table>
    `;
    pendingCard.querySelectorAll('[data-requeue]').forEach((b) =>
      b.addEventListener('click', async () => {
        if (!confirm('确认让该买家重新排队？其口令码继续有效，位次刷新到队尾。')) return;
        await act(`/api/seller/intents/${b.dataset.requeue}/dispose`, { action: 'requeue' }, '已重新排队');
      })
    );
    pendingCard.querySelectorAll('[data-void]').forEach((b) =>
      b.addEventListener('click', async () => {
        if (!confirm('确认作废该意向？作废后其口令码失效。')) return;
        await act(`/api/seller/intents/${b.dataset.void}/dispose`, { action: 'void' }, '意向已作废');
      })
    );
  } else {
    pendingCard.style.display = 'none';
  }

  // 库存调整 / 改挂类别 / 全部意向（A.1.3-5、A.1.2-4）
  renderOps(data);
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
  if (card.contains(document.activeElement)) return;

  const p = data.detail.product;
  card.style.display = '';
  card.innerHTML = `
    <h2>库存与类别 <span class="muted">调整即时生效，可随时改回</span></h2>
    <div class="ops">
      <div class="ops-field">
        <label>库存（当前 ${p.stock == null ? 0 : p.stock} 件）</label>
        <div class="ops-line">
          <input type="number" id="stockInput" min="0" step="1" value="${p.stock == null ? 0 : p.stock}">
          <button class="btn btn-outline btn-sm" id="stockSave">保存库存</button>
        </div>
        <input type="text" id="stockReason" maxlength="200" placeholder="调整原因（选填，如：补货 / 盘点）"
               style="width:100%;padding:9px 12px;border:1px solid var(--border);border-radius:10px;font-size:13px;font-family:inherit;background:#fbfcfe;outline:none;color:var(--text)">
        <div class="ops-hint">调到 0 → 商品转为「售罄」，仍留在当前商品卡片；补货 ≥ 1 自动恢复在售。</div>
      </div>
      <div class="ops-field">
        <label>所属类别（当前 ${esc(categoryLabel(p.categoryId))}）</label>
        <div class="ops-line">
          <select id="catSelect">${leafOptionsHtml(p.categoryId)}</select>
          <button class="btn btn-outline btn-sm" id="catSave">保存类别</button>
        </div>
        <div class="ops-hint">只能挂到二级类别；新增类别请到「类别管理」。</div>
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
    lastOverviewJson = '';
    loadOverview();
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
    lastOverviewJson = '';
    loadOverview();
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
    loadOverview();
    if (path.includes('offshelf') || path.includes('result')) loadHistory();
  } catch (e) {
    toast(e.message, 'err');
    if (e.status === 401) {
      token = '';
      localStorage.removeItem('seller_token');
      showLogin();
    } else {
      loadOverview();
    }
  }
}

/* ---------- 发布商品 ---------- */
async function publishProduct() {
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

  let image = null;
  const file = $('#pImage').files[0];
  if (file) {
    if (!['image/jpeg', 'image/png'].includes(file.type)) return (err.textContent = '图片格式仅支持 JPG / PNG');
    if (file.size > 5 * 1024 * 1024) return (err.textContent = '图片大小不能超过 5MB');
    image = await new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = reject;
      r.readAsDataURL(file);
    });
  }
  try {
    // 契约（ProductPublishRequest）：categoryId 必填二级类别、stock ≥1、images 为 base64 数组
    await api('/api/seller/products', {
      method: 'POST',
      body: {
        name,
        description,
        price: priceNum,
        stock: stockNum,
        categoryId: Number(categoryVal),
        images: image ? [image] : [],
      },
    });
    toast('商品已发布，进入在售', 'ok');
    lastOverviewJson = '';
    loadOverview();
  } catch (e) {
    err.textContent = e.message;
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
    lastOverviewJson = '';
    loadHistory();
    loadOverview();
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
    lastOverviewJson = '';
    loadOverview();
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
    lastOverviewJson = '';
    loadOverview();
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
    lastOverviewJson = '';
    loadOverview();
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
    loadOverview();
  }
}, 5000);
