/* 我的历史页逻辑（US-19 / D-4 接口已交付，页面 J-3 补充）
   依赖接口：GET /api/buyers/history?page=n（买家登录态，X-Token）
   口径（接口设计 v2 §3.5）：按提交时间倒序、每页 10 条、结果三值、不含价格与图片、只读 */
const $ = (s) => document.querySelector(s);

const state = { page: 1, totalPages: 0 };

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/* 兼容 ISO 字符串与 [y,m,d,h,mi,s] 数组两种序列化结果 */
function fmtTime(v) {
  if (!v) return '—';
  const d = Array.isArray(v) ? new Date(v[0], v[1] - 1, v[2], v[3] || 0, v[4] || 0, v[5] || 0) : new Date(v);
  if (isNaN(d.getTime())) return '—';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function api(path) {
  const headers = {};
  const tk = buyerToken();
  if (tk) headers['X-Token'] = tk;
  const res = await fetch(path, { headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || '请求失败');
    err.status = res.status;
    throw err;
  }
  return data;
}

/* 交易结果三值 → 徽标配色（待交易 / 成功 / 失败） */
function resultBadge(r) {
  let cls = 'badge queued';
  if (r === '成功') cls = 'badge success';
  if (r === '失败') cls = 'badge failed';
  return `<span class="${cls}">${esc(r || '待交易')}</span>`;
}

/* 未登录 / token 失效：US-19 反向判据「未登录或登录已失效」 */
function renderAuthFail() {
  $('#histBody').innerHTML = `
    <div class="empty">未登录或登录已失效<br>
      <a class="back-link" href="/login.html?next=%2Fmy%2Fhistory.html">去登录</a>
    </div>`;
  $('#pager').innerHTML = '';
}

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

function row(it) {
  return `
    <tr>
      <td>${esc(it.productName || '—')}</td>
      <td>${fmtTime(it.submittedAt)}</td>
      <td>${resultBadge(it.result)}</td>
      <td>${fmtTime(it.dealTime)}</td>
    </tr>`;
}

async function loadHistory() {
  const body = $('#histBody');
  if (!buyerToken()) {
    renderAuthFail();
    return;
  }
  try {
    const data = await api('/api/buyers/history?page=' + state.page);
    state.totalPages = data.totalPages || 0;
    const items = data.items || [];
    if (!items.length) {
      body.innerHTML = '<div class="empty">暂无下单记录<br><a class="back-link" href="/">去首页挑选商品</a></div>';
    } else {
      body.innerHTML = `
        <table class="table">
          <thead>
            <tr><th>商品名称</th><th>下单时间</th><th>交易结果</th><th>交易时间</th></tr>
          </thead>
          <tbody>${items.map(row).join('')}</tbody>
        </table>`;
    }
    renderPager();
  } catch (e) {
    if (e.status === 401) {
      clearBuyerSession();
      renderAuthFail();
      return;
    }
    body.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
  }
}

$('#pager').addEventListener('click', (e) => {
  const btn = e.target.closest('.page-btn');
  if (!btn || btn.disabled) return;
  state.page = Number(btn.dataset.page);
  loadHistory();
});

loadHistory();
