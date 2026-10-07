/* 买家端逻辑 */
const $ = (s) => document.querySelector(s);

let currentProduct = null;

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

/* ---------- 商品展示 ---------- */
function renderProduct(product) {
  currentProduct = product;
  const area = $('#productArea');
  if (!product) {
    area.innerHTML = '<div class="empty">暂无商品在售<br><span class="muted">稍后再来看看吧</span></div>';
    return;
  }
  const frozen = product.status === 'frozen';
  const pic = product.imageUrl
    ? `<img src="${esc(product.imageUrl)}" alt="${esc(product.name)}">`
    : `<svg width="90" height="90" viewBox="0 0 24 24" fill="none" stroke="#b8c1d4" stroke-width="1.4"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="M3.5 18l5-5 3.5 3.5L16 12l4.5 4.5"/></svg>`;
  area.innerHTML = `
    <div class="product">
      <div class="pic">${pic}</div>
      <div class="info">
        <div class="name">${esc(product.name)}</div>
        <div>${frozen ? '<span class="badge frozen">商品交易中</span>' : '<span class="badge on">在售中</span>'}</div>
        <div class="price"><small>￥</small>${fmtPrice(product.price)}</div>
        ${product.description ? `<div class="desc">${esc(product.description)}</div>` : ''}
        <div class="meta">发布时间：${fmtTime(product.publishedAt)}</div>
        <div class="actions">
          <button class="btn btn-primary" id="buyBtn" ${frozen ? 'disabled' : ''}>${frozen ? '商品交易中，暂停接收新意向' : '我要购买'}</button>
        </div>
      </div>
    </div>
    ${frozen ? '<div class="deal-banner">⏳ 商品交易中，暂停接收新意向。若前方交易未达成，队列将自动递补，请留意你的排队位次。</div>' : ''}
  `;
  if (!frozen) {
    $('#buyBtn').addEventListener('click', openBuyModal);
  }
}

async function loadProduct() {
  try {
    const data = await api('/api/product');
    renderProduct(data.product);
  } catch (e) {
    toast(e.message, 'err');
  }
}

/* ---------- 提交意向 ---------- */
function openBuyModal() {
  $('#buyName').value = '';
  $('#buyPhone').value = '';
  $('#buyError').textContent = '';
  $('#buyMask').classList.add('show');
}

async function submitIntent() {
  const name = $('#buyName').value.trim();
  const phone = $('#buyPhone').value.trim();
  const err = $('#buyError');
  err.textContent = '';
  if (!name || !phone) {
    err.textContent = '姓名与联系电话均为必填';
    return;
  }
  try {
    const data = await api('/api/intents', { method: 'POST', body: { name, phone } });
    $('#buyMask').classList.remove('show');
    showCode(data.code);
    loadProduct();
  } catch (e) {
    err.textContent = e.message;
    loadProduct(); // 状态可能已变化（如刚被冻结）
  }
}

function showCode(code) {
  $('#codeText').textContent = code;
  $('#codeMask').classList.add('show');
}

/* ---------- 口令码查询 ---------- */
async function lookup() {
  const code = $('#codeInput').value.trim().toUpperCase();
  const box = $('#lookupResult');
  if (!code) {
    box.innerHTML = '<div class="empty" style="padding:18px">请输入口令码</div>';
    return;
  }
  try {
    const data = await api('/api/intents/lookup/' + encodeURIComponent(code));
    renderLookup(data, code);
  } catch (e) {
    box.innerHTML = `<div class="empty" style="padding:18px">${esc(e.message)}<br><span class="muted">口令码可能在交易结束后失效，或你已撤销意向</span></div>`;
  }
}

function renderLookup(data, code) {
  const { intent, product } = data;
  const box = $('#lookupResult');
  const head = intent.inTransaction
    ? '<div class="position-big deal">🎉 已进入交易！卖家将与你线下联系完成交易</div>'
    : `<div class="position-big">你当前排第 ${intent.position} 位</div>`;
  box.innerHTML = `
    ${head}
    <div class="kv"><span class="k">商品</span><span>${esc(product ? product.name : '-')}</span></div>
    <div class="kv"><span class="k">提交时间</span><span>${fmtTime(intent.submittedAt)}</span></div>
    <div class="divider"></div>
    <div class="form">
      <div>
        <label>姓名</label>
        <input type="text" id="editName" value="${esc(intent.name)}" maxlength="50">
      </div>
      <div>
        <label>联系电话</label>
        <input type="text" id="editPhone" value="${esc(intent.phone)}" maxlength="30">
      </div>
      <div style="display:flex; gap:10px; flex-wrap:wrap">
        <button class="btn btn-primary btn-sm" id="saveInfo">保存修改</button>
        <button class="btn btn-danger btn-sm" id="cancelIntent">撤销我的意向</button>
      </div>
    </div>
  `;
  $('#saveInfo').addEventListener('click', async () => {
    const name = $('#editName').value.trim();
    const phone = $('#editPhone').value.trim();
    if (!name || !phone) return toast('姓名与联系电话均为必填', 'err');
    try {
      await api('/api/intents/lookup/' + encodeURIComponent(code), { method: 'PUT', body: { name, phone } });
      toast('联系信息已更新', 'ok');
    } catch (e) {
      toast(e.message, 'err');
      lookup();
    }
  });
  $('#cancelIntent').addEventListener('click', async () => {
    if (!confirm('确定撤销该购买意向吗？撤销后口令码将失效，且不可恢复。')) return;
    try {
      await api(`/api/intents/lookup/${encodeURIComponent(code)}/cancel`, { method: 'POST' });
      toast('意向已撤销', 'ok');
      box.innerHTML = '<div class="empty" style="padding:18px">该意向已撤销</div>';
    } catch (e) {
      toast(e.message, 'err');
      lookup();
    }
  });
}

/* ---------- 事件绑定 ---------- */
document.querySelectorAll('[data-close]').forEach((btn) =>
  btn.addEventListener('click', () => $('#' + btn.dataset.close).classList.remove('show'))
);
document.querySelectorAll('.modal-mask').forEach((mask) =>
  mask.addEventListener('click', (e) => {
    if (e.target === mask && mask.id !== 'codeMask') mask.classList.remove('show');
  })
);
$('#buySubmit').addEventListener('click', submitIntent);
$('#lookupBtn').addEventListener('click', lookup);
$('#codeInput').addEventListener('keydown', (e) => e.key === 'Enter' && lookup());
$('#copyCode').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($('#codeText').textContent);
    toast('已复制到剪贴板', 'ok');
  } catch (e) {
    toast('复制失败，请手动抄写', 'err');
  }
});
$('#codeDone').addEventListener('click', () => {
  $('#codeMask').classList.remove('show');
  $('#codeInput').value = $('#codeText').textContent;
  lookup();
});

loadProduct();
setInterval(loadProduct, 10000);
