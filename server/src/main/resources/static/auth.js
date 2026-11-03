/* 买家登录态（US-18 / D-3）—— 由 index.html、product.html、login.html、register.html、my/history.html 共用
   约定：
   - token 存 localStorage.buyer_token，用户名存 localStorage.buyer_username，刷新后保持登录
   - 卖家侧凭证为 localStorage.seller_token，两套互不影响
   - 本文件不声明 $ / api / toast，避免与同页加载的页面脚本顶层声明冲突 */
const BUYER_TOKEN_KEY = 'buyer_token';
const BUYER_NAME_KEY = 'buyer_username';

function buyerToken() {
  return localStorage.getItem(BUYER_TOKEN_KEY) || '';
}

function buyerName() {
  return localStorage.getItem(BUYER_NAME_KEY) || '';
}

function saveBuyerSession(token, username) {
  localStorage.setItem(BUYER_TOKEN_KEY, token);
  localStorage.setItem(BUYER_NAME_KEY, username);
}

function clearBuyerSession() {
  localStorage.removeItem(BUYER_TOKEN_KEY);
  localStorage.removeItem(BUYER_NAME_KEY);
}

/* 退出登录：先清本地，再通知后端使旧 token 失效（接口失败也不影响本地退出） */
async function buyerLogout() {
  const tk = buyerToken();
  clearBuyerSession();
  if (tk) {
    try {
      await fetch('/api/buyers/logout', { method: 'POST', headers: { 'X-Token': tk } });
    } catch (e) {
      /* 网络异常忽略：本地登录态已清除 */
    }
  }
  location.reload();
}

function authEsc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/* 顶部登录态（US-18 / US-19）：未登录显示「登录」按钮；
   已登录显示「你好，用户名 + 我的历史 + 退出登录」 */
function renderTopbarUser() {
  const box = document.getElementById('topbarUser');
  if (!box) return;
  const name = buyerName();
  if (name) {
    box.innerHTML = `你好，<b>${authEsc(name)}</b>
      · <a href="/my/history.html">我的历史</a>
      · <a href="#" id="topbarLogout">退出登录</a>`;
    document.getElementById('topbarLogout').addEventListener('click', (e) => {
      e.preventDefault();
      buyerLogout();
    });
  } else {
    box.innerHTML = '<a href="/login.html" class="topbar-btn">登录</a>';
  }
}

/* 本地有 token 时静默校验一次（GET /api/buyers/me）：
   token 已失效（401）则清本地登录态并回落未登录视图；后端不可达时保留本地态 */
async function verifyBuyerSession() {
  const tk = buyerToken();
  if (!tk) return;
  try {
    const res = await fetch('/api/buyers/me', { headers: { 'X-Token': tk } });
    if (res.status === 401) {
      clearBuyerSession();
      renderTopbarUser();
      return;
    }
    if (res.ok) {
      const data = await res.json();
      if (data.buyer && data.buyer.username) saveBuyerSession(tk, data.buyer.username);
    }
  } catch (e) {
    /* 后端未启动 / 网络异常：保留本地登录态，不打扰用户 */
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    renderTopbarUser();
    verifyBuyerSession();
  });
} else {
  renderTopbarUser();
  verifyBuyerSession();
}
