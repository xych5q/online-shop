/* 买家注册页逻辑（US-18 / D-3）
   依赖接口：POST /api/buyers/register（D-1 已交付）
   入参 {username, password, phone, phoneConfirm, defaultLocation}，与 BuyerService.register 一一对应
   前端只做即时反馈，最终以接口返回的错误原文为准；授权勾选仅前端校验，后端无该字段，不随请求提交 */
const $ = (s) => document.querySelector(s);

function toast(msg, type) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (type || '');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.className = 'toast'), 2600);
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

/* ---------- 授权勾选：未勾选则注册按钮不可点 ---------- */
$('#regAgree').addEventListener('change', () => {
  $('#regSubmit').disabled = !$('#regAgree').checked;
});

/* ---------- 隐私说明弹层 ---------- */
$('#openPrivacy').addEventListener('click', () => $('#privacyMask').classList.add('show'));
$('#privacyOk').addEventListener('click', () => $('#privacyMask').classList.remove('show'));
$('#privacyMask').addEventListener('click', (e) => {
  if (e.target === $('#privacyMask')) $('#privacyMask').classList.remove('show');
});
document.querySelectorAll('[data-close]').forEach((btn) =>
  btn.addEventListener('click', () => $('#' + btn.dataset.close).classList.remove('show'))
);

/* ---------- 提交注册 ---------- */
async function submitRegister() {
  const username = $('#regUser').value.trim();
  const password = $('#regPass').value;
  const phone = $('#regPhone').value.trim();
  const phoneConfirm = $('#regPhone2').value.trim();
  const defaultLocation = $('#regLocation').value.trim();
  const err = $('#regError');
  err.textContent = '';

  if (!$('#regAgree').checked) {
    err.textContent = '请先阅读并同意隐私说明';
    return;
  }
  if (!username) {
    err.textContent = '用户名不能为空';
    return;
  }
  if (!/^[A-Za-z0-9_]{3,20}$/.test(username)) {
    err.textContent = '用户名需为 3-20 位字母、数字或下划线';
    return;
  }
  if (password.length < 8 || !/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
    err.textContent = '密码长度不能少于 8 位，且需同时包含字母与数字';
    return;
  }
  if (!phone) {
    err.textContent = '联系电话不能为空';
    return;
  }
  if (phone !== phoneConfirm) {
    err.textContent = '两次输入的联系电话不一致';
    return;
  }

  try {
    await api('/api/buyers/register', {
      method: 'POST',
      body: { username, password, phone, phoneConfirm, defaultLocation },
    });
    /* 注册成功不自动登录，提示后跳转登录页（US-18 How to demo 第 2 步） */
    sessionStorage.setItem('buyer_registered', '1');
    toast('注册成功', 'ok');
    setTimeout(() => { location.href = '/login.html'; }, 900);
  } catch (e) {
    err.textContent = e.message;
  }
}

$('#regSubmit').addEventListener('click', submitRegister);
$('#regPass').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') submitRegister();
});
