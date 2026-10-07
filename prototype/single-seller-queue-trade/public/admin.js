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

function enterAdmin() {
  $('#loginCard').style.display = 'none';
  $('#adminBody').style.display = '';
  loadOverview();
}

function showLogin() {
  $('#loginCard').style.display = '';
  $('#adminBody').style.display = 'none';
}

/* ---------- 当前商品 ---------- */
let overview = null;
let lastOverviewJson = '';

/** 发布表单是否正在填写（有内容时避免自动刷新清空输入） */
function publishFormDirty() {
  const name = $('#pName');
  if (!name) return false;
  return !!(name.value.trim() || ($('#pDesc') && $('#pDesc').value.trim())
    || ($('#pPrice') && $('#pPrice').value) || ($('#pImage') && $('#pImage').files.length));
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
    card.innerHTML = `
      <h2>发布新商品 <span class="muted">同一时刻仅可有一件在售</span></h2>
      <div class="form" style="max-width:520px">
        <div>
          <label>商品名称<span class="req">*</span></label>
          <input type="text" id="pName" maxlength="100" placeholder="不超过 100 字">
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
          <div class="hint" id="imgHint">发布后商品信息不可修改，如需变更须下架重新发布。</div>
        </div>
        <div class="error" id="pError"></div>
        <button class="btn btn-primary" id="pSubmit">发布商品</button>
      </div>
    `;
    $('#pSubmit').addEventListener('click', publishProduct);
    queueCard.innerHTML = '<h2>意向购买人</h2><div class="empty" style="padding:18px">暂无在售商品</div>';
    pendingCard.style.display = 'none';
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
        <button class="btn btn-primary" id="markSuccess">✅ 标记交易成功（下架进历史）</button>
        <button class="btn btn-danger" id="markFail">❌ 标记交易失败（自动递补）</button>
      </div>
      <div class="hint" style="margin-top:10px;color:var(--text-2);font-size:12px">提示：交易标记后不可回退，请线下确认后再操作。</div>
    `;
  } else if (p.status === 'on_sale') {
    dealInfo = `
      <div class="actions">
        <button class="btn btn-primary" id="enterDeal" ${d.queue.length ? '' : 'disabled'}>与队首进入交易</button>
        <button class="btn btn-outline" id="freezeBtn">手动冻结</button>
        <button class="btn btn-ghost" id="offshelfBtn">手动下架</button>
      </div>
      ${d.queue.length ? '' : '<div class="hint" style="margin-top:10px;color:var(--text-2);font-size:12px">尚无买家排队，暂不能进入交易。</div>'}
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
  bind('#enterDeal', async () => {
    if (!confirm('确认与队首买家进入交易？商品将自动冻结、停止接收新意向。')) return;
    await act(`/api/seller/products/${p.id}/deal`, {}, '已进入交易，商品已冻结');
  });
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
  const err = $('#pError');
  err.textContent = '';
  if (!name) return (err.textContent = '商品名称必填');
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
    await api('/api/seller/products', { method: 'POST', body: { name, description, price: priceNum, image } });
    toast('商品已发布，进入在售', 'ok');
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
    <h2>历史商品 <span class="muted">已下架档案 · 只读，不可修改删除</span></h2>
    <table class="table">
      <thead><tr><th>名称</th><th>价格</th><th>发布时间</th><th>交易时间</th><th>意向数</th><th></th></tr></thead>
      <tbody>
        ${data.items.map((h) => `<tr class="clickable" data-hist="${h.id}">
          <td>${esc(h.name)}</td>
          <td>￥${fmtPrice(h.price)}</td>
          <td>${fmtTime(h.publishedAt)}</td>
          <td>${fmtTime(h.closedAt)}</td>
          <td>${h.intentCount}</td>
          <td><button class="btn btn-outline btn-sm">查看意向</button></td>
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
document.querySelectorAll('.tabs button').forEach((b) =>
  b.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    $('#tab-current').style.display = b.dataset.tab === 'current' ? '' : 'none';
    $('#tab-history').style.display = b.dataset.tab === 'history' ? '' : 'none';
    if (b.dataset.tab === 'history') loadHistory();
  })
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
  if (token && $('#adminBody').style.display !== 'none' && $('#tab-current').style.display !== 'none') {
    loadOverview();
  }
}, 5000);
