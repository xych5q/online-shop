/**
 * 单卖家排队交易系统 —— 零依赖 Node.js 服务端
 * 规则依据《版本需求总结.md》：
 *  - 单卖家固定账号（admin / seller123，可修改密码），无注册、无退出登录
 *  - 单件在售、先到先得、进入交易自动冻结、线下成交、卖家标记结果
 *  - 买家免注册，凭唯一口令码查询位次 / 改信息 / 撤销
 *  - 交易成功→下架进历史；失败→自动递补；失败者可「作废/重新排队」
 *  - 数据 JSON 文件持久化，重启不丢失
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = __dirname;
const DATA_DIR = path.join(ROOT, 'data');
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const PUBLIC_DIR = path.join(ROOT, 'public');
const PORT = process.env.PORT || 3000;
const BODY_LIMIT = 10 * 1024 * 1024; // 允许 5MB 图片的 base64（约 6.8MB）

// ---------------- 数据持久化 ----------------
let db = null;

function hashPassword(password, salt) {
  return crypto.scryptSync(String(password), salt, 64).toString('hex');
}

function defaultDb() {
  const salt = crypto.randomBytes(16).toString('hex');
  return {
    seller: {
      username: 'admin',
      salt,
      hash: hashPassword('seller123', salt),
      token: crypto.randomBytes(24).toString('hex'), // 登录态持久化（无退出登录功能）
    },
    products: [],
    intentions: [],
    seq: { product: 0, intent: 0, upload: 0 },
  };
}

function loadDb() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  try {
    db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    if (!db.seller || !Array.isArray(db.products) || !Array.isArray(db.intentions)) {
      throw new Error('bad db');
    }
  } catch (e) {
    db = defaultDb();
    saveDb();
  }
}

function saveDb() {
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2), 'utf8');
  fs.renameSync(tmp, DB_FILE);
}

// ---------------- 业务辅助 ----------------
const TERMINAL = ['success', 'cancelled', 'failed'];

function now() {
  return new Date().toISOString();
}

function activeProduct() {
  return db.products.find((p) => p.status === 'on_sale' || p.status === 'frozen') || null;
}

function productById(id) {
  return db.products.find((p) => p.id === id) || null;
}

function intentById(id) {
  return db.intentions.find((i) => i.id === id) || null;
}

/** 队列：该商品下 queued 的意向，按提交时间正序 */
function queueOf(productId) {
  return db.intentions
    .filter((i) => i.productId === productId && i.status === 'queued')
    .sort((a, b) => (a.submittedAt === b.submittedAt ? a.id - b.id : a.submittedAt < b.submittedAt ? -1 : 1));
}

function currentDeal(productId) {
  return db.intentions.find((i) => i.productId === productId && i.status === 'in_transaction') || null;
}

/** 待卖家处理的失败意向（未作废/未重排，且商品未下架） */
function pendingFailed(productId) {
  return db.intentions
    .filter((i) => i.productId === productId && i.status === 'failed' && !i.disposed)
    .sort((a, b) => (a.submittedAt < b.submittedAt ? -1 : 1));
}

/** 生成唯一口令码（去除易混淆字符的 8 位大写字母数字） */
function genCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  for (let attempt = 0; attempt < 100; attempt++) {
    let code = '';
    const bytes = crypto.randomBytes(8);
    for (let i = 0; i < 8; i++) code += alphabet[bytes[i] % alphabet.length];
    if (!db.intentions.some((i) => i.code === code)) return code;
  }
  throw new Error('code collision');
}

/** 凭口令码找到有效（未终态）的意向 */
function findActiveByCode(code) {
  const c = String(code || '').trim().toUpperCase();
  const hits = db.intentions.filter((i) => i.code === c && !TERMINAL.includes(i.status));
  if (!hits.length) return null;
  hits.sort((a, b) => (a.submittedAt < b.submittedAt ? 1 : -1));
  return hits[0];
}

function publicProduct(p) {
  return {
    id: p.id,
    name: p.name,
    description: p.description,
    price: p.price,
    imageUrl: p.image ? '/uploads/' + p.image : null,
    status: p.status,
    publishedAt: p.createdAt,
  };
}

// ---------------- 交易流转核心 ----------------

/** 与队首进入交易：自动冻结 */
function enterDeal(product) {
  const queue = queueOf(product.id);
  if (!queue.length) return { error: '当前没有排队的买家' };
  const head = queue[0];
  head.status = 'in_transaction';
  product.status = 'frozen';
  product.frozenBy = 'auto';
  product.frozenAt = now();
  saveDb();
  return { ok: true };
}

/**
 * 标记交易结果
 *  - success：胜出意向「成功」，队列剩余自动转「失败」，商品下架进历史
 *  - fail：当前意向转「失败」（待卖家作废/重新排队）；
 *          队列有人 → 下一位自动递补进入交易（商品保持冻结）；
 *          队列无人 → 商品自动恢复在售
 */
function markResult(product, result) {
  const deal = currentDeal(product.id);
  if (!deal) return { error: '当前没有进行中的交易' };
  if (result === 'success') {
    deal.status = 'success';
    deal.disposed = true;
    for (const i of queueOf(product.id)) {
      i.status = 'failed';
      i.disposed = true;
    }
    product.status = 'off_shelf';
    product.closedAt = now();
    saveDb();
    return { ok: true };
  }
  // fail
  deal.status = 'failed';
  deal.disposed = false;
  const queue = queueOf(product.id);
  if (queue.length) {
    queue[0].status = 'in_transaction'; // 自动递补，无需卖家再次点击
    product.frozenAt = now();
  } else {
    product.status = 'on_sale'; // 已恢复在售
    product.frozenBy = null;
    product.frozenAt = null;
  }
  saveDb();
  return { ok: true };
}

/** 卖家处理失败意向：作废 / 重新排队（不换码，位次刷新到队尾） */
function disposeFailed(intent, action) {
  const product = productById(intent.productId);
  if (!product || product.status === 'off_shelf') return { error: '商品已下架，无法处理' };
  intent.disposed = true;
  if (action === 'requeue') {
    db.intentions.push({
      id: ++db.seq.intent,
      productId: intent.productId,
      name: intent.name,
      phone: intent.phone,
      code: intent.code, // 重新排队不生成新口令码
      submittedAt: now(), // 提交时间刷新 → 排到队尾
      status: 'queued',
      requeuedFrom: intent.id,
      disposed: false,
    });
  }
  saveDb();
  return { ok: true };
}

/** 手动下架：队列中剩余意向转失败，商品进入历史 */
function offShelf(product) {
  for (const i of queueOf(product.id)) {
    i.status = 'failed';
    i.disposed = true;
  }
  for (const i of pendingFailed(product.id)) i.disposed = true;
  product.status = 'off_shelf';
  product.closedAt = now();
  saveDb();
  return { ok: true };
}

// ---------------- 图片处理 ----------------
function saveImage(dataUrl) {
  const m = /^data:(image\/(jpeg|png));base64,(.+)$/.exec(String(dataUrl || ''));
  if (!m) return { error: '图片格式仅支持 JPG / PNG' };
  const buf = Buffer.from(m[3], 'base64');
  if (buf.length === 0) return { error: '图片内容为空' };
  if (buf.length > 5 * 1024 * 1024) return { error: '图片大小不能超过 5MB' };
  const ext = m[2] === 'jpeg' ? 'jpg' : 'png';
  const name = 'img_' + Date.now() + '_' + (++db.seq.upload) + '.' + ext;
  fs.writeFileSync(path.join(UPLOAD_DIR, name), buf);
  return { name };
}

// ---------------- HTTP 基础 ----------------
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > BODY_LIMIT) {
        reject({ status: 413, message: '请求体过大' });
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) {
        reject({ status: 400, message: '请求格式错误' });
      }
    });
    req.on('error', reject);
  });
}

function json(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function isAuthed(req) {
  const h = req.headers['authorization'] || '';
  const token = (req.headers['x-token'] || '').trim() || h.replace(/^Bearer\s+/i, '').trim();
  return token && db.seller.token === token;
}

function authFail(res) {
  json(res, 401, { error: '未登录或登录已失效' });
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.png': 'image/png',
};

function serveFile(res, filePath) {
  fs.readFile(filePath, (err, buf) => {
    if (err) return json(res, 404, { error: 'not found' });
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(buf);
  });
}

// ---------------- 校验 ----------------
function validateProductInput(body) {
  const name = String(body.name || '').trim();
  const description = String(body.description || '').trim();
  const price = Number(body.price);
  if (!name) return { error: '商品名称必填' };
  if (name.length > 100) return { error: '商品名称不能超过 100 字' };
  if (description.length > 2000) return { error: '商品描述不能超过 2000 字' };
  if (!Number.isFinite(price) || price <= 0) return { error: '价格必须为大于 0 的数字' };
  return {
    name,
    description,
    price: Math.round(price * 100) / 100,
  };
}

// ---------------- 路由 ----------------
async function handleApi(req, res, url) {
  const method = req.method;
  const p = url.pathname;
  const send = (obj, status) => json(res, status || 200, obj);
  const body = ['POST', 'PUT', 'PATCH'].includes(method) ? await readBody(req) : {};

  // ---- 买家侧 ----
  if (method === 'GET' && p === '/api/product') {
    const product = activeProduct();
    return send({ product: product ? publicProduct(product) : null });
  }

  if (method === 'POST' && p === '/api/intents') {
    const product = activeProduct();
    if (!product) return send({ error: '暂无商品在售' }, 400);
    if (product.status !== 'on_sale') return send({ error: '商品交易中，暂停接收新意向' }, 400);
    const name = String(body.name || '').trim();
    const phone = String(body.phone || '').trim();
    if (!name || !phone) return send({ error: '姓名与联系电话均为必填' }, 400);
    const intent = {
      id: ++db.seq.intent,
      productId: product.id,
      name,
      phone,
      code: genCode(),
      submittedAt: now(),
      status: 'queued',
      disposed: false,
    };
    db.intentions.push(intent);
    saveDb();
    return send({ code: intent.code }); // 口令码仅此一次返回
  }

  const mLookup = /^\/api\/intents\/lookup\/([A-Za-z0-9]+)$/.exec(p);
  if (mLookup) {
    const code = decodeURIComponent(mLookup[1]);
    const intent = findActiveByCode(code);
    if (!intent) return send({ error: '口令码无效或已失效' }, 404);
    const product = productById(intent.productId);
    const queue = queueOf(intent.productId);
    const position = queue.findIndex((i) => i.id === intent.id) + 1;
    const inTransaction = intent.status === 'in_transaction';
    if (method === 'GET') {
      return send({
        intent: {
          name: intent.name,
          phone: intent.phone,
          status: intent.status,
          inTransaction,
          position: inTransaction ? null : position,
          submittedAt: intent.submittedAt,
        },
        product: product ? publicProduct(product) : null,
      });
    }
    if (method === 'PUT') {
      const name = String(body.name || '').trim();
      const phone = String(body.phone || '').trim();
      if (!name || !phone) return send({ error: '姓名与联系电话均为必填' }, 400);
      intent.name = name;
      intent.phone = phone;
      saveDb();
      return send({ ok: true });
    }
  }

  const mCancel = /^\/api\/intents\/lookup\/([A-Za-z0-9]+)\/cancel$/.exec(p);
  if (mCancel && method === 'POST') {
    const intent = findActiveByCode(decodeURIComponent(mCancel[1]));
    if (!intent) return send({ error: '口令码无效或已失效' }, 404);
    if (intent.status === 'in_transaction') {
      return send({ error: '你已进入交易，无法自行撤销，请联系卖家' }, 400);
    }
    intent.status = 'cancelled'; // 终态；历史留痕
    saveDb();
    return send({ ok: true });
  }

  // ---- 卖家登录 ----
  if (method === 'POST' && p === '/api/seller/login') {
    const username = String(body.username || '').trim();
    const password = String(body.password || '');
    if (username !== db.seller.username || hashPassword(password, db.seller.salt) !== db.seller.hash) {
      return send({ error: '用户名或密码错误' }, 401);
    }
    db.seller.token = crypto.randomBytes(24).toString('hex');
    saveDb();
    return send({ token: db.seller.token, username: db.seller.username });
  }

  if (method === 'POST' && p === '/api/seller/password') {
    if (!isAuthed(req)) return authFail(res);
    const oldPassword = String(body.oldPassword || '');
    const newPassword = String(body.newPassword || '');
    if (hashPassword(oldPassword, db.seller.salt) !== db.seller.hash) {
      return send({ error: '原密码错误' }, 400);
    }
    if (newPassword.length < 8) return send({ error: '新密码长度不能少于 8 位' }, 400);
    const salt = crypto.randomBytes(16).toString('hex');
    db.seller.salt = salt;
    db.seller.hash = hashPassword(newPassword, salt);
    saveDb();
    return send({ ok: true });
  }

  // ---- 卖家业务（需登录） ----
  if (p.startsWith('/api/seller/')) {
    if (!isAuthed(req)) return authFail(res);

    if (method === 'GET' && p === '/api/seller/overview') {
      const product = activeProduct();
      let detail = null;
      if (product) {
        const deal = currentDeal(product.id);
        detail = {
          product: {
            ...publicProduct(product),
            frozenBy: product.frozenBy,
            frozenAt: product.frozenAt,
            inTransaction: !!deal,
          },
          queue: queueOf(product.id).map((i, idx) => ({
            id: i.id,
            position: idx + 1,
            name: i.name,
            phone: i.phone,
            submittedAt: i.submittedAt,
          })),
          currentDeal: deal
            ? { id: deal.id, name: deal.name, phone: deal.phone, submittedAt: deal.submittedAt }
            : null, // 不含口令码
          pendingFailed: pendingFailed(product.id).map((i) => ({
            id: i.id,
            name: i.name,
            phone: i.phone,
            submittedAt: i.submittedAt,
          })),
        };
      }
      return send({ detail, canPublish: !product });
    }

    if (method === 'POST' && p === '/api/seller/products') {
      if (activeProduct()) return send({ error: '已有商品在售或交易中，需先下架' }, 400);
      const v = validateProductInput(body);
      if (v.error) return send({ error: v.error }, 400);
      let image = null;
      if (body.image) {
        const r = saveImage(body.image);
        if (r.error) {
          return send({ error: r.error }, 400);
        }
        image = r.name;
      }
      const product = {
        id: ++db.seq.product,
        name: v.name,
        description: v.description,
        price: v.price,
        image,
        status: 'on_sale',
        createdAt: now(),
        frozenAt: null,
        frozenBy: null,
        closedAt: null,
      };
      db.products.push(product);
      saveDb();
      return send({ ok: true });
    }

    let m;
    m = /^\/api\/seller\/products\/(\d+)((?:\/[a-z-]+)+)?$/.exec(p);
    if (m && method === 'POST') {
      const product = productById(Number(m[1]));
      if (!product) return send({ error: '商品不存在' }, 404);
      const action = m[2] || '';
      if (product.status === 'off_shelf') return send({ error: '商品已下架' }, 400);

      if (action === '/freeze') {
        if (product.status !== 'on_sale') return send({ error: '仅「在售」商品可手动冻结' }, 400);
        product.status = 'frozen';
        product.frozenBy = 'manual';
        product.frozenAt = now();
        saveDb();
        return send({ ok: true });
      }
      if (action === '/unfreeze') {
        if (product.status !== 'frozen') return send({ error: '商品未处于冻结状态' }, 400);
        if (product.frozenBy !== 'manual') return send({ error: '交易中的商品不能手动解冻' }, 400);
        product.status = 'on_sale';
        product.frozenBy = null;
        product.frozenAt = null;
        saveDb();
        return send({ ok: true });
      }
      if (action === '/offshelf') {
        if (product.status === 'frozen' && product.frozenBy !== 'manual') {
          return send({ error: '交易中的商品请先标记交易结果' }, 400);
        }
        return send(offShelf(product));
      }
      if (action === '/deal') {
        if (product.status !== 'on_sale') return send({ error: '仅「在售」商品可进入交易' }, 400);
        return send(enterDeal(product));
      }
      if (action === '/deal/result') {
        if (product.status !== 'frozen' || product.frozenBy !== 'auto') {
          return send({ error: '当前没有进行中的交易' }, 400);
        }
        const result = body.result;
        if (result !== 'success' && result !== 'fail') return send({ error: '参数错误' }, 400);
        return send(markResult(product, result));
      }
      return send({ error: '未知操作' }, 404);
    }

    m = /^\/api\/seller\/intents\/(\d+)\/dispose$/.exec(p);
    if (m && method === 'POST') {
      const intent = intentById(Number(m[1]));
      if (!intent) return send({ error: '意向不存在' }, 404);
      if (intent.status !== 'failed' || intent.disposed) return send({ error: '该意向无需处理' }, 400);
      if (body.action !== 'void' && body.action !== 'requeue') return send({ error: '参数错误' }, 400);
      return send(disposeFailed(intent, body.action));
    }

    if (method === 'GET' && p === '/api/seller/history') {
      const PAGE = 10;
      const list = db.products
        .filter((x) => x.status === 'off_shelf')
        .sort((a, b) => (a.closedAt < b.closedAt ? 1 : -1));
      const page = Math.max(1, Number(url.searchParams.get('page')) || 1);
      const totalPages = Math.max(1, Math.ceil(list.length / PAGE));
      const cur = Math.min(page, totalPages);
      const items = list.slice((cur - 1) * PAGE, cur * PAGE).map((x) => ({
        id: x.id,
        name: x.name,
        price: x.price,
        imageUrl: x.image ? '/uploads/' + x.image : null,
        publishedAt: x.createdAt,
        closedAt: x.closedAt,
        intentCount: db.intentions.filter((i) => i.productId === x.id).length,
      }));
      return send({ page: cur, totalPages, total: list.length, items });
    }

    m = /^\/api\/seller\/history\/(\d+)$/.exec(p);
    if (m && method === 'GET') {
      const product = productById(Number(m[1]));
      if (!product || product.status !== 'off_shelf') return send({ error: '历史商品不存在' }, 404);
      const intents = db.intentions
        .filter((i) => i.productId === product.id)
        .sort((a, b) => (a.submittedAt < b.submittedAt ? -1 : 1))
        .map((i) => ({
          name: i.name, // 口令码不进历史
          phone: i.phone,
          submittedAt: i.submittedAt,
          status: i.status,
        }));
      return send({
        product: {
          id: product.id,
          name: product.name,
          description: product.description,
          price: product.price,
          imageUrl: product.image ? '/uploads/' + product.image : null,
          publishedAt: product.createdAt,
          closedAt: product.closedAt,
        },
        intents,
      });
    }
  }

  return send({ error: '接口不存在' }, 404);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  try {
    if (p.startsWith('/api/')) return await handleApi(req, res, url);
    if (p.startsWith('/uploads/')) {
      const file = path.normalize(p.slice('/uploads/'.length)).replace(/^([.][.][\\\/])+/, '');
      const full = path.join(UPLOAD_DIR, file);
      if (!full.startsWith(UPLOAD_DIR)) return json(res, 403, { error: 'forbidden' });
      return serveFile(res, full);
    }
    if (p === '/' || p === '/index.html') return serveFile(res, path.join(PUBLIC_DIR, 'index.html'));
    if (p === '/admin' || p === '/admin.html') return serveFile(res, path.join(PUBLIC_DIR, 'admin.html'));
    const staticFile = path.join(PUBLIC_DIR, path.normalize(p).replace(/^([.][.][\\\/])+/, ''));
    if (staticFile.startsWith(PUBLIC_DIR) && fs.existsSync(staticFile) && fs.statSync(staticFile).isFile()) {
      return serveFile(res, staticFile);
    }
    json(res, 404, { error: 'not found' });
  } catch (e) {
    if (e && e.status) return json(res, e.status, { error: e.message || '请求错误' });
    console.error(e);
    json(res, 500, { error: '服务器内部错误' });
  }
});

loadDb();
server.listen(PORT, () => {
  console.log(`单卖家排队交易系统已启动: http://localhost:${PORT}`);
  console.log(`买家页: http://localhost:${PORT}/  |  卖家后台: http://localhost:${PORT}/admin`);
  console.log(`默认卖家账号: admin / seller123 （登录后请及时修改密码）`);
});
