# Java 正式实现进度

> 第 1 组 · 迭代 1　|　仓库目录：`server/`（Spring Boot 3.3.4 + JDK 17+ + Maven + H2）
> 与需求规则基线 [[原型实现说明]]（Node.js 原型）一一对应：原型负责"规则是否正确"，Java 负责"是否可交付"。

## 一、分层与责任人

| 层 | 内容 | 责任人 | 状态 |
| --- | --- | --- | --- |
| 骨架 | `pom.xml`、`application.yml`、`entity/`、`repository/`、`exception/`、`config/` | 陈星宇 | ✅ 已完成（`ce56672`） |
| 卖家端 | `SellerService`、`ProductService`、`SellerController`（12 接口，836 行） | 王振涛 | ✅ 已完成（`f4d8821`） |
| 买家端 | `IntentService`、`TradeService`、`BuyerController`（5 接口，454 行） | 林初俊 | ✅ 已完成（`cac53ac`） |
| 单元测试 | JUnit 5 + MockMvc，16 组用例（457 行） | 林初俊 | ✅ 已完成（`cac53ac`） |
| 前端 | `static/`（由 `prototype/public/` 迁入，5 个文件） | 嵇宇锋 | ✅ 已完成（`a4c7081`） |
| 构建部署 | Maven 已通；Docker 镜像与部署手册实测 | 陈星宇 | 🔄 进行中 |
| 端到端/性能测试 | Selenium、JMeter | 待分配 | ⬜ 未开始（迭代 2） |

**代码规模合计**：主代码 1290 行 + 测试 457 行 = 1747 行（不含骨架）。

## 二、卖家端已完成内容（王振涛，2026-10-07）

**代码**：`SellerService.java`（267 行）、`ProductService.java`（390 行）、`SellerController.java`（179 行），另补建 `ProductRepository.java`（骨架原缺失）。

**12 个接口实测结果**（curl 逐条执行，全部通过）：

| # | 接口 | 关键验证 |
| --- | --- | --- |
| 1 | `POST /api/seller/login` | 正确返回 token；错密码 401；重新登录后旧 token 失效 |
| 2 | `POST /api/seller/password` | 原密码错拒绝；新密码 <8 位拒绝；成功后 BCrypt 重编码 |
| 3 | `GET /api/seller/overview` | 空态、队列带 position、currentDeal/pendingFailed 不返回口令码 |
| 4 | `POST /api/seller/products` | 名称必填 ≤100、描述 ≤2000、价格 >0；重复发布拒绝；base64 图片落盘 |
| 5 | `POST /api/seller/products/{id}/freeze` | 在售→手动冻结；已下架拒绝；非在售拒绝 |
| 6 | `POST /api/seller/products/{id}/unfreeze` | 手动冻结可恢复；**交易冻结拒绝手动解冻** |
| 7 | `POST /api/seller/products/{id}/deal` | 队首进入交易、商品自动冻结（auto）；空队列拒绝 |
| 8 | `POST /api/seller/products/{id}/deal/result` | success→胜出+队列转失败+下架；fail→转失败待处置+下位递补 |
| 9 | `POST /api/seller/products/{id}/offshelf` | 队列剩余置失败；交易中拒绝下架 |
| 10 | `POST /api/seller/intents/{id}/dispose` | requeue 沿用原码排到队尾；void 作废；重复处置拒绝 |
| 11 | `GET /api/seller/history` | 只查已下架、closedAt 倒序、每页 10 条 |
| 12 | `GET /api/seller/history/{id}` | 详情+意向流水；**全文检查无口令码泄漏** |

## 三、买家端与交易流转已完成内容（林初俊，2026-10-07）

**代码**：`IntentService.java`（213 行）、`TradeService.java`（149 行）、`BuyerController.java`（92 行）。

**5 个接口**：

| # | 接口 | 关键规则 |
| --- | --- | --- |
| 1 | `GET /api/product` | 买家浏览当前在售商品 |
| 2 | `POST /api/intents` | 生成 8 位口令码（字符集剔除 I/O/0/1），查库保证全库唯一，**仅在提交响应中返回一次** |
| 3 | `GET /api/intents/lookup/{code}` | 返回位次与状态；进入交易时 `position=null`、`inTransaction=true`；无效或终态码 404 |
| 4 | `PUT /api/intents/lookup/{code}` | 只改姓名电话，`submittedAt` 不动 → **排队位次不变** |
| 5 | `POST /api/intents/lookup/{code}/cancel` | 交易中拒绝撤销；其余置 `cancelled`（终态留痕） |

**交易流转规则**（翻译自原型 `server.js` 第 135~221、330~395 行，未作改动）：队首不可挑人、进入交易自动冻结、标记成功清队进历史、标记失败自动递补或恢复在售、重排沿用原码排到队尾。

## 四、单元测试（JUnit 5，组长本地复跑验证）

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in BuyerApiTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- in TradeFlowTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

16 组用例覆盖：登录鉴权与 token 失效、参数边界（价格 -1、空姓名电话）、重复发布拦截、三人入队位次 1/2/3、无效口令码 404、改电话位次不变、撤销后口令码失效、冻结态拒绝新意向、交易中禁止手动解冻、失败后自动递补、重排沿用原码且排到队尾、成功后队列转失败、历史流水不含口令码。

采用 `@SpringBootTest + @AutoConfigureMockMvc` 走真实 Spring MVC 链路（含统一异常处理），每个用例前清空数据保证独立。

## 五、技术债处理情况

| # | 问题 | 处理 | 状态 |
| --- | --- | --- | --- |
| TD-1 | `Intent.code` 唯一约束 vs "重排沿用原码" | 终态原记录改码为 `原码#r{id}` 占位、新记录沿用原码；林初俊进一步修复了原临时实现的 Hibernate「INSERT 先于 UPDATE」缺陷，改为 `saveAndFlush` 先落库 | ✅ 已解决（对外行为与原型一致） |
| TD-2 | `TradeService` 未入库时的临时实现 | `ProductService` 注入 `TradeService`，三处 `xxxTemp` 临时方法已删除并改为正式调用 | ✅ 已解决（`cac53ac`） |
| TD-3 | `GET /api/product` 重复映射风险 | 已从 `SellerController` 删除，仅保留在 `BuyerController` | ✅ 已解决（`cac53ac`） |
| TD-4 | 验收清单中 history 写 POST，接口设计写 GET | 按 [[接口设计]] 实现为 GET | ✅ 已对齐 |

**遗留观察项**（不影响交付，记录备查）：TD-1 方案会在数据库终态记录上留下 `原码#r{id}` 形式的占位值。若后续需彻底清理，应改为"仅对未终态意向做应用层唯一校验"并去掉数据库唯一约束。

## 六、明天（10-08）演示口径

**Java 版已可演示买卖双方完整全链路**：

1. 卖家登录 → 发布商品
2. 买家浏览商品 → 提交意向 → 取得口令码（仅显示一次）
3. 第二、三位买家依次入队（位次 1/2/3）
4. 卖家查看意向购买人 → 与队首进入交易（商品自动冻结）
5. 标记失败 → 下一位自动递补
6. 卖家处置失败买家 → 重新排队（原口令码复效、排到队尾）
7. 标记成功 → 队列剩余转失败、商品下架进历史
8. 查询历史详情 → 意向流水完整且不含口令码

演示前须启动：`cd server && java -jar target/online-shop.jar`（H2 文件库，无需 MySQL）。管理员账号密码见启动日志（首次启动自动创建）。

**前端已迁入并完成联调**：组长本地实测——执行 `mvn package` 后 `java -jar target/online-shop.jar`，访问 `/index.html`（买家端）与 `/admin.html`（卖家后台）均返回 HTTP 200，style.css / buyer.js / admin.js 全部加载正常，`/api/product` 返回真实商品数据。即**可直接用浏览器演示完整流程**，无需再依赖 Node 原型。

演示时使用默认端口 8080（`java -jar target/online-shop.jar`），管理员账号密码见启动日志首次创建时打印的内容。仍须主动说明："原型为 Node.js 规则验证实现，正式交付为 Java，前后端与单元测试均已完成；Docker 部署与 Selenium/JMeter 测试为迭代 2 工作。"
