/* 端到端业务流程验证脚本 */
const BASE = 'http://localhost:3000';

async function api(path, opts = {}) {
  const res = await fetch(BASE + path, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(opts.token ? { 'X-Token': opts.token } : {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  return { status: res.status, data };
}

let passed = 0, failed = 0;
function check(name, cond, extra) {
  if (cond) { passed++; console.log('  PASS', name); }
  else { failed++; console.log('  FAIL', name, extra !== undefined ? JSON.stringify(extra) : ''); }
}

(async () => {
  // 1. 登录
  let r = await api('/api/seller/login', { method: 'POST', body: { username: 'admin', password: 'wrong' } });
  check('错误密码被拒绝', r.status === 401);
  r = await api('/api/seller/login', { method: 'POST', body: { username: 'admin', password: 'seller123' } });
  const token = r.data.token;
  check('卖家登录成功', r.status === 200 && !!token);

  // 2. 未登录访问后台被拒
  r = await api('/api/seller/overview');
  check('未登录访问后台被拒', r.status === 401);

  // 3. 发布商品（价格校验 + 单件约束）
  r = await api('/api/seller/products', { method: 'POST', token, body: { name: 'iPhone 15 Pro', description: '99新 国行', price: -1 } });
  check('价格必须>0', r.status === 400);
  r = await api('/api/seller/products', { method: 'POST', token, body: { name: 'iPhone 15 Pro', description: '99新 国行', price: 5999.999 } });
  check('发布成功', r.status === 200);
  r = await api('/api/product');
  check('买家可见在售商品', r.data.product && r.data.product.name === 'iPhone 15 Pro', r.data);
  check('价格保留两位小数', r.data.product.price === 6000, r.data.product && r.data.product.price);
  r = await api('/api/seller/products', { method: 'POST', token, body: { name: '第二件', price: 1 } });
  check('在售时不能再发布', r.status === 400);

  // 4. 买家提交意向
  r = await api('/api/intents', { method: 'POST', body: { name: '张三', phone: '13800000001' } });
  const code1 = r.data.code;
  check('张三提交意向并获口令码', r.status === 200 && /^[A-Z2-9]{8}$/.test(code1));
  await api('/api/intents', { method: 'POST', body: { name: '李四', phone: '13800000002' } }).then((x) => (globalThis.code2 = x.data.code));
  await api('/api/intents', { method: 'POST', body: { name: '王五', phone: '13800000003' } }).then((x) => (globalThis.code3 = x.data.code));
  r = await api('/api/intents', { method: 'POST', body: { name: '', phone: '' } });
  check('姓名电话必填', r.status === 400);

  // 5. 口令码查询位次
  r = await api('/api/intents/lookup/' + code2);
  check('李四排第2位', r.data.intent.position === 2, r.data);
  r = await api('/api/intents/lookup/ZZZZZZZZ');
  check('无效口令码报错', r.status === 404);

  // 6. 修改联系信息 + 撤销
  r = await api('/api/intents/lookup/' + code3, { method: 'PUT', body: { name: '王五', phone: '13900000003' } });
  check('王五修改电话成功', r.status === 200);
  r = await api('/api/intents/lookup/' + code3 + '/cancel', { method: 'POST' });
  check('王五撤销意向', r.status === 200);
  r = await api('/api/intents/lookup/' + code3);
  check('撤销后口令码失效', r.status === 404);

  // 7. 冻结时不收新意向
  let ov = (await api('/api/seller/overview', { token })).data.detail;
  r = await api(`/api/seller/products/${ov.product.id}/freeze`, { method: 'POST', token });
  check('手动冻结成功', r.status === 200);
  r = await api('/api/intents', { method: 'POST', body: { name: '赵六', phone: '138' } });
  check('冻结后不收新意向', r.status === 400 && /交易中/.test(r.data.error));
  r = await api(`/api/seller/products/${ov.product.id}/unfreeze`, { method: 'POST', token });
  check('手动解冻成功', r.status === 200);

  // 8. 与队首进入交易（卖家不可挑人）
  r = await api(`/api/seller/products/${ov.product.id}/deal`, { method: 'POST', token });
  check('与队首张三进入交易', r.status === 200);
  ov = (await api('/api/seller/overview', { token })).data.detail;
  check('商品自动冻结', ov.product.status === 'frozen' && ov.product.frozenBy === 'auto');
  check('当前交易对象是张三', ov.currentDeal && ov.currentDeal.name === '张三', ov.currentDeal);
  r = await api('/api/intents/lookup/' + code1);
  check('张三可见已进入交易', r.data.intent.inTransaction === true);
  r = await api(`/api/seller/products/${ov.product.id}/unfreeze`, { method: 'POST', token });
  check('交易中不能手动解冻', r.status === 400);

  // 9. 交易失败 → 自动递补
  r = await api(`/api/seller/products/${ov.product.id}/deal/result`, { method: 'POST', token, body: { result: 'fail' } });
  check('标记交易失败', r.status === 200);
  ov = (await api('/api/seller/overview', { token })).data.detail;
  check('李四自动递补进入交易', ov.currentDeal && ov.currentDeal.name === '李四', ov.currentDeal);
  check('张三进入待处理失败列表', ov.pendingFailed.some((i) => i.name === '张三'), ov.pendingFailed);

  // 10. 张三重新排队（不换码、排队尾）
  const zhang = ov.pendingFailed.find((i) => i.name === '张三');
  r = await api(`/api/seller/intents/${zhang.id}/dispose`, { method: 'POST', token, body: { action: 'requeue' } });
  check('张三重新排队', r.status === 200);
  r = await api('/api/intents/lookup/' + code1);
  check('原口令码仍有效', r.status === 200);
  ov = (await api('/api/seller/overview', { token })).data.detail;
  check('张三排到队尾(第1位，因队列只剩他)', ov.queue.length === 1 && ov.queue[0].name === '张三', ov.queue);

  // 11. 李四交易成功 → 下架进历史，剩余转失败
  r = await api(`/api/seller/products/${ov.product.id}/deal/result`, { method: 'POST', token, body: { result: 'success' } });
  check('标记交易成功', r.status === 200);
  r = await api('/api/product');
  check('买家页恢复无商品', r.data.product === null);
  r = await api('/api/intents/lookup/' + code1);
  check('交易结束后口令码失效', r.status === 404);
  r = await api('/api/seller/history', { token });
  check('历史记录有1件商品', r.data.total === 1 && r.data.items[0].name === 'iPhone 15 Pro', r.data);
  const hid = r.data.items[0].id;
  r = await api('/api/seller/history/' + hid, { token });
  const byName = {};
  for (const i of r.data.intents) byName[i.name + '|' + i.status] = true;
  check('李四终态=成功', !!byName['李四|success'], r.data.intents);
  check('王五终态=已撤销(留痕)', !!byName['王五|cancelled']);
  check('张三终态=失败', !!byName['张三|failed']);
  check('口令码不进历史', !JSON.stringify(r.data).includes(code1));
  r = await api('/api/seller/history/' + hid + '/x', { method: 'POST', token, body: {} });
  check('历史只读(无修改接口)', r.status === 404);

  // 12. 修改密码
  r = await api('/api/seller/password', { method: 'POST', token, body: { oldPassword: 'seller123', newPassword: '123' } });
  check('新密码不足8位被拒', r.status === 400);
  r = await api('/api/seller/password', { method: 'POST', token, body: { oldPassword: 'wrong', newPassword: '12345678' } });
  check('原密码错误被拒', r.status === 400);
  r = await api('/api/seller/password', { method: 'POST', token, body: { oldPassword: 'seller123', newPassword: 'newpass88' } });
  check('修改密码成功', r.status === 200);
  r = await api('/api/seller/login', { method: 'POST', body: { username: 'admin', password: 'newpass88' } });
  check('新密码可登录', r.status === 200);
  r = await api('/api/seller/login', { method: 'POST', body: { username: 'admin', password: 'seller123' } });
  check('旧密码失效', r.status === 401);

  console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error('脚本异常:', e);
  process.exit(1);
});
