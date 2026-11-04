/* 商品详情页逻辑（US-17 / J-3）
   依赖接口：GET /api/products/{id}、GET /api/categories/tree（J-1 已交付）、
             POST /api/intents（W-3 已交付：买家登录态，入参 {productId}）
   冻结语义（裁决 C11）：frozenBy = auto 交易中仍可提交意向；manual 卖家停售不可提交
   富文本：白名单净化后渲染（标签/属性白名单 + 危险协议过滤，见 sanitizeRich） */
const $ = (s) => document.querySelector(s);

let currentProduct = null;

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
}

/* 兼容 ISO 字符串与 [y,m,d,h,mi,s] 数组两种序列化结果 */
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
  const headers = { 'Content-Type': 'application/json' };
  const tk = buyerToken();
  if (tk) headers['X-Token'] = tk;
  const res = await fetch(path, {
    headers,
    ...opts,
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

/* ---------- 富文本净化（US-17 / J-3） ----------
   用 DOMParser 在惰性文档中解析：该文档无浏览上下文，脚本不会执行；
   随后按白名单重建：允许的标签保留并过滤属性，未知标签「拆壳留文本」，危险标签整段丢弃。
   白名单之外的属性（含全部 on* 事件、style、srcdoc 等）一律移除。 */
const RICH_TAGS = {
  p: [], br: [], div: [], span: [], hr: [],
  strong: [], b: [], em: [], i: [], u: [], s: [], del: [], ins: [], sub: [], sup: [],
  h1: [], h2: [], h3: [], h4: [], h5: [], h6: [],
  ul: [], ol: [], li: [], blockquote: [], pre: [], code: [],
  table: [], thead: [], tbody: [], tr: [], th: [], td: [],
  a: ['href', 'title', 'target'],
  img: ['src', 'alt', 'width', 'height'],
  /* A.1.5-1 要求详情能内嵌图片和视频：放行 video/source 只是加白名单条目，
     属性仍逐个校验（on* / style / srcdoc 一律不在白名单内，src 走 safeUrl） */
  video: ['src', 'controls', 'poster', 'width', 'height'],
  source: ['src', 'type'],
};
const RICH_DROP = new Set([
  'script', 'style', 'iframe', 'frame', 'frameset', 'object', 'embed', 'applet',
  'form', 'input', 'button', 'textarea', 'select', 'option', 'optgroup', 'label',
  'link', 'meta', 'base', 'svg', 'math', 'template', 'noscript', 'audio',
]);

/* 链接与图片地址：只放行 http/https/mailto、站内绝对路径与锚点；
   javascript: / data: / 协议相对 //host 一律丢弃（先去掉控制字符与空白再判断，防止 js\tcript: 绕过） */
function safeUrl(v) {
  const raw = String(v == null ? '' : v).trim();
  if (!raw) return false;
  const compact = raw.replace(/[\u0000-\u0020\u007f]/g, '').toLowerCase();
  if (compact.charAt(0) === '#') return true;
  if (compact.charAt(0) === '/') return compact.charAt(1) !== '/';
  return /^(https?:|mailto:)/.test(compact);
}

function cleanRich(node) {
  Array.prototype.slice.call(node.childNodes).forEach((child) => {
    if (child.nodeType === 8) { node.removeChild(child); return; }   /* 注释 */
    if (child.nodeType !== 1) return;                                /* 文本节点原样保留 */
    const tag = child.tagName.toLowerCase();
    if (RICH_DROP.has(tag)) { node.removeChild(child); return; }
    cleanRich(child);
    if (!Object.prototype.hasOwnProperty.call(RICH_TAGS, tag)) {
      /* 未知标签拆壳：子节点（已净化）上提，文本内容不丢 */
      while (child.firstChild) node.insertBefore(child.firstChild, child);
      node.removeChild(child);
      return;
    }
    const allowed = RICH_TAGS[tag];
    Array.prototype.slice.call(child.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase();
      const urlAttr = name === 'href' || name === 'src' || name === 'poster';
      const ok = allowed.indexOf(name) > -1 && (!urlAttr || safeUrl(attr.value));
      if (!ok) child.removeAttribute(attr.name);
    });
    if (tag === 'a') {
      /* 地址被判定为危险（javascript: / data: 等）时，保留文字、去掉假链接，避免看起来可点却没反应 */
      if (!child.getAttribute('href')) {
        while (child.firstChild) node.insertBefore(child.firstChild, child);
        node.removeChild(child);
        return;
      }
      /* 外链统一加 rel，target 只允许 _blank */
      child.setAttribute('rel', 'noopener noreferrer');
      if (child.getAttribute('target')) child.setAttribute('target', '_blank');
    }
    if (tag === 'img' && !child.getAttribute('src')) node.removeChild(child);
  });
}

function sanitizeRich(html) {
  if (!html) return '';
  const doc = new DOMParser().parseFromString(
    '<!DOCTYPE html><html><body><div id="__rich_root">' + String(html) + '</div></body></html>',
    'text/html'
  );
  const root = doc.getElementById('__rich_root');
  if (!root) return '';
  cleanRich(root);
  return root.innerHTML;
}

/* 类别全路径（一级 / 二级）；拿不到一级名时只显示二级名 */
let categoryTree = [];
function categoryPath(p) {
  if (!p.categoryName) return '未分类';
  if (!p.categoryId) return p.categoryName;
  const parent = categoryTree.find((c) => (c.children || []).some((k) => k.id === p.categoryId));
  return parent ? `${parent.name} / ${p.categoryName}` : p.categoryName;
}

/* 状态与可提交性：C11 两类冻结分开处理；在售不显示徽标（US-17 线框图标题下无状态行） */
function buyStateOf(p) {
  if (p.status === 'frozen') {
    if (p.frozenBy === 'manual') {
      return {
        badge: '<span class="badge paused">暂停接单</span>',
        btn: '卖家已暂停接单',
        disabled: true,
        note: '卖家已暂停接单，暂不接收新意向；可稍后再来查看。',
      };
    }
    return {
      badge: '<span class="badge frozen">交易中</span>',
      btn: '立即购买（提交意向）',
      disabled: false,
      note: '当前有正在进行的交易，仍可提交意向排队；若前方交易未达成，队列将自动递补。',
    };
  }
  return { badge: '', btn: '立即购买（提交意向）', disabled: false, note: '' };
}

/* 图片加载失败（上传文件缺失 / 服务端未映射上传目录）时降级为占位图，
   避免浏览器直接渲染破图图标；缩略图失败则隐藏该张，不影响其余几张切换 */
function attachImgFallback(img) {
  img.addEventListener('error', () => {
    if (img.closest('.thumb')) {
      img.style.visibility = 'hidden';
      return;
    }
    const ph = document.createElement('span');
    ph.className = 'pic-ph';
    ph.textContent = '图片加载失败';
    img.replaceWith(ph);
  });
}

function renderDetail(p) {
  currentProduct = p;
  const area = $('#detailArea');
  const st = buyStateOf(p);
  /* 富文本先净化再判空：只有净化后仍有内容（文字或图片/视频/表格/分隔线）才渲染整段「商品描述」区块，
     避免卖家没写详情时页面上留一个空标题 */
  const richHtml = sanitizeRich(p.richDescription || '');
  const hasRich = /<(img|video|source|table|hr|br)\b/i.test(richHtml)
    || richHtml.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, '').trim() !== '';
  /* 主图取第一张：优先 images[0]，其次列表主图 imageUrl，都没有则占位图 */
  const imgs = (p.images && p.images.length) ? p.images.slice() : (p.imageUrl ? [p.imageUrl] : []);
  const mainPic = imgs.length
    ? `<img src="${esc(imgs[0])}" alt="${esc(p.name)}">`
    : '<span class="pic-ph">占位图</span>';
  const thumbs = imgs.length > 1
    ? `<div class="thumbs">${imgs.map((src, i) =>
        `<button type="button" class="thumb${i === 0 ? ' active' : ''}" data-src="${esc(src)}" title="第 ${i + 1} 张"><img src="${esc(src)}" alt="第 ${i + 1} 张"><span class="thumb-no">${i + 1}</span></button>`
      ).join('')}</div>`
    : '';

  area.innerHTML = `
    <div class="detail">
      <div class="gallery">
        <div class="main-pic" id="mainPic">${mainPic}</div>
        ${thumbs}
      </div>
      <div class="info">
        <div class="name">${esc(p.name)}</div>
        ${st.badge ? `<div class="status-row">${st.badge}</div>` : ''}
        <div class="price"><small>￥</small>${fmtPrice(p.price)}</div>
        <div class="kv-line">库存：${p.stock == null ? '-' : esc(p.stock)} 件　类别：${esc(categoryPath(p))}</div>
        <div class="kv-line">发布时间：${fmtTime(p.publishedAt)}</div>
        <div class="actions">
          <button class="btn btn-primary" id="buyBtn" ${st.disabled ? 'disabled' : ''}>${st.btn}</button>
        </div>
        <div class="hint-banner" id="buyHint"${st.note || st.disabled ? ' style="display:none"' : ''}>未登录时点「立即购买」→ 跳转登录页并提示「请先登录」</div>
        ${st.note ? `<div class="deal-banner">${st.note}</div>` : ''}
      </div>
    </div>
    ${hasRich ? '<div class="divider"></div><div class="section-title">商品描述（富文本）</div><div class="rich" id="rich"></div>' : ''}
  `;

  /* 富文本渲染：先白名单净化再写入 innerHTML，不裸用后端字符串；
     描述为空（或净化后为空）时整段区块不渲染，不显示默认文案（US-17 线框图脚注） */
  const rich = $('#rich');
  if (rich) rich.innerHTML = richHtml;

  area.querySelectorAll('img').forEach(attachImgFallback);

  const thumbsBox = area.querySelector('.thumbs');
  if (thumbsBox) {
    thumbsBox.addEventListener('click', (e) => {
      const btn = e.target.closest('.thumb');
      if (!btn) return;
      area.querySelectorAll('.thumb').forEach((b) => b.classList.toggle('active', b === btn));
      $('#mainPic').innerHTML = `<img src="${esc(btn.dataset.src)}" alt="${esc(p.name)}">`;
      // 切换后是新节点，重新挂失败降级，否则这张图 404 时仍是破图图标
      const shown = $('#mainPic img');
      if (shown) attachImgFallback(shown);
    });
  }

  if (!st.disabled) {
    $('#buyBtn').addEventListener('click', submitIntent);
  }
}

/* ---------- 提交购买意向（W-3：登录态 + {productId}） ---------- */
function loginUrl() {
  const next = location.pathname + location.search;
  return '/login.html?next=' + encodeURIComponent(next);
}

function goLogin(msg) {
  toast(msg || '请先登录', 'err');
  setTimeout(() => { location.href = loginUrl(); }, 700);
}

async function submitIntent() {
  if (!currentProduct) return;
  if (!buyerToken()) {
    goLogin();
    return;
  }
  const btn = $('#buyBtn');
  btn.disabled = true;
  try {
    const data = await api('/api/intents', { method: 'POST', body: { productId: currentProduct.id } });
    const status = data.intent && data.intent.status;
    if (status === 'in_transaction') {
      toast('提交成功，你已进入交易，卖家将按账号电话联系你', 'ok');
    } else {
      toast('提交成功，已加入排队', 'ok');
    }
    /* 提交后商品状态可能变为交易中（自动冻结），重新拉一次详情 */
    setTimeout(loadDetail, 1200);
  } catch (e) {
    if (e.status === 401) {
      clearBuyerSession();
      goLogin('登录已失效，请重新登录');
      return;
    }
    toast(e.message, 'err');
    btn.disabled = false;
  }
}

async function loadCategories() {
  try {
    const data = await api('/api/categories/tree');
    categoryTree = data.categories || [];
  } catch (e) {
    categoryTree = [];
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

/* 返回列表：从列表页点进来的走浏览器返回，保留筛选条件；直接打开则回首页 */
(function initBack() {
  const link = $('#backLink');
  const fromList = document.referrer && document.referrer.indexOf(location.origin + '/') === 0
    && document.referrer.indexOf('/product.html') === -1;
  link.addEventListener('click', (e) => {
    if (fromList && history.length > 1) {
      e.preventDefault();
      history.back();
    }
  });
})();

loadCategories().then(loadDetail);
