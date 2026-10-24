/* 商品详情页逻辑（US-17 / J-3）
   依赖接口：GET /api/products/{id}（已稳定）
   本次范围：主图取第一张、缩略图切换、无图占位、价格两位小数
   未纳入：立即购买（提交意向）—— 依赖的意向接口契约未定稿，本次不做；
           富文本渲染 —— 本版将 richDescription 先转义为纯文本展示，富文本留到后续 */
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

function detailPlaceholder() {
  return '<svg width="96" height="96" viewBox="0 0 24 24" fill="none" stroke="#b8c1d4" stroke-width="1.4"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="M3.5 18l5-5 3.5 3.5L16 12l4.5 4.5"/></svg>';
}

function renderDetail(p) {
  const area = $('#detailArea');
  /* 主图取第一张：优先 images[0]，其次列表主图 imageUrl，都没有则占位图 */
  const imgs = (p.images && p.images.length) ? p.images.slice() : (p.imageUrl ? [p.imageUrl] : []);
  const mainPic = imgs.length
    ? `<img src="${esc(imgs[0])}" alt="${esc(p.name)}">`
    : detailPlaceholder();
  const thumbs = imgs.length > 1
    ? `<div class="thumbs">${imgs.map((src, i) =>
        `<button type="button" class="thumb${i === 0 ? ' active' : ''}" data-src="${esc(src)}"><img src="${esc(src)}" alt="第 ${i + 1} 张"></button>`
      ).join('')}</div>`
    : '';
  const badge = p.status === 'frozen'
    ? '<span class="badge frozen">商品交易中</span>'
    : '<span class="badge on">在售中</span>';
  const cat = p.categoryName ? `<span class="cat">${esc(p.categoryName)}</span>` : '';

  area.innerHTML = `
    <div class="detail">
      <div class="gallery">
        <div class="main-pic" id="mainPic">${mainPic}</div>
        ${thumbs}
      </div>
      <div class="info">
        <div class="name">${esc(p.name)}</div>
        <div class="status-row">${badge}${cat}</div>
        <div class="price"><small>￥</small>${fmtPrice(p.price)}</div>
        ${p.description ? `<div class="desc">${esc(p.description)}</div>` : ''}
        <div class="meta">发布时间：${fmtTime(p.publishedAt)}</div>
      </div>
    </div>
    ${p.richDescription ? `<div class="divider"></div><div class="section-title">商品详情</div><div class="rich" id="rich"></div>` : ''}
  `;

  /* 富文本先按纯文本展示：用 textContent 赋值，不解析任何标签（XSS 安全，富文本渲染留到后续） */
  const rich = $('#rich');
  if (rich) rich.textContent = p.richDescription;

  const thumbsBox = area.querySelector('.thumbs');
  if (thumbsBox) {
    thumbsBox.addEventListener('click', (e) => {
      const btn = e.target.closest('.thumb');
      if (!btn) return;
      area.querySelectorAll('.thumb').forEach((b) => b.classList.toggle('active', b === btn));
      $('#mainPic').innerHTML = `<img src="${esc(btn.dataset.src)}" alt="${esc(p.name)}">`;
    });
  }
}

async function loadDetail() {
  const area = $('#detailArea');
  const id = new URLSearchParams(location.search).get('id');
  if (!id) {
    area.innerHTML = '<div class="empty">缺少商品编号<br><span class="muted">请从列表页点选商品进入</span></div>';
    return;
  }
  try {
    const data = await api('/api/products/' + encodeURIComponent(id));
    renderDetail(data.product);
  } catch (e) {
    area.innerHTML = `<div class="empty">${esc(e.message)}<br><span class="muted">商品可能已下架或售罄</span></div>`;
  }
}

loadDetail();
