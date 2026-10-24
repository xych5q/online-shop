/* 买家登录页逻辑（US-18 / D-3）
   依赖接口：POST /api/buyers/login（D-2 已交付）、GET /api/buyers/me、POST /api/buyers/logout
   登录成功后 token 存 localStorage（auth.js），顶部显示用户名，刷新保持登录 */
const $ = (s) => document.querySelector(s);

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
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
  if (!res.ok) throw new Error(data.error || '请求失败');
  return data;
}

/* ---------- 登录态视图切换 ---------- */
function showLoggedIn(username) {
  $('#loginForm').style.display = 'none';
  $('#loggedIn').style.display = 'block';
  $('#loggedName').textContent = username;
}

async function initLoginPage() {
  if (sessionStorage.getItem('buyer_registered')) {
    sessionStorage.removeItem('buyer_registered');
    toast('注册成功，请登录', 'ok');
  }
  if (!buyerToken()) return;
  try {
    const data = await api('/api/buyers/me');
    saveBuyerSession(buyerToken(), data.buyer.username);
    showLoggedIn(data.buyer.username);
  } catch (e) {
    /* token 已失效：静默清掉本地登录态，回到登录表单 */
    clearBuyerSession();
  }
}

/* ---------- 登录 ---------- */
async function submitLogin() {
  const username = $('#loginUser').value.trim();
  const password = $('#loginPass').value;
  const err = $('#loginError');
  err.textContent = '';
  if (!username || !password) {
    err.textContent = '请输入用户名与密码';
    return;
  }
  try {
    const data = await api('/api/buyers/login', { method: 'POST', body: { username, password } });
    saveBuyerSession(data.token, data.username);
    toast('登录成功', 'ok');
    setTimeout(() => { location.href = '/'; }, 700);
  } catch (e) {
    err.textContent = e.message;
  }
}

$('#loginSubmit').addEventListener('click', submitLogin);
$('#loginPass').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') submitLogin();
});
$('#logoutBtn').addEventListener('click', buyerLogout);

initLoginPage();
