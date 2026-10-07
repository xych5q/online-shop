# Java 正式实现进度

> 第 1 组 · 迭代 1　|　仓库目录：`server/`（Spring Boot 3.3.4 + JDK 17+ + Maven + H2）
> 与需求规则基线 [[原型实现说明]]（Node.js 原型）一一对应，原型负责"规则是否正确"，Java 负责"是否可交付"。

## 一、分层与责任人

| 层 | 内容 | 责任人 | 状态 |
| --- | --- | --- | --- |
| 骨架 | `pom.xml`、`application.yml`、`entity/`、`repository/`、`exception/`、`config/` | 陈星宇 | ✅ 已完成（`ce56672`） |
| 卖家端 | `SellerService`、`ProductService`、`SellerController`（12 接口，836 行） | 王振涛 | ✅ 已完成（`f4d8821`） |
| 买家端 | `IntentService`、`TradeService`、`BuyerController`（5 接口） | 林初俊 | 🔄 进行中 |
| 测试 | JUnit 5 单元测试 + 集成测试 | 林初俊 | 🔄 进行中 |
| 前端 | `static/`（由 `prototype/public/` 迁入） | 嵇宇锋 | 🔄 进行中 |

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

`mvn -q package` 编译通过，`java -jar target/online-shop.jar` 启动成功。

## 三、已知技术债与待办（组长已核实，需后续处理）

| # | 问题 | 现状处理 | 后续动作 | 责任 |
| --- | --- | --- | --- | --- |
| TD-1 | `Intent.code` 定义了数据库唯一约束，与"重新排队沿用原口令码"规则冲突 | 新记录沿用原码，已终态原记录口令码改写为 `原码#r{id}` 占位（终态意向不对外返回口令码，对外行为与原型一致） | 评估是否改为"仅对未终态意向做应用层唯一校验"，去掉数据库唯一约束 | 陈星宇 + 林初俊 |
| TD-2 | `TradeService`（林初俊）尚未入库 | `ProductService` 内以 `enterDealTemp` / `markResultTemp` / `disposeFailedTemp` 三个 private 临时方法实现，均标注 `TODO` | TradeService 就绪后一行替换为调用，删除临时方法 | 林初俊 |
| TD-3 | `GET /api/product` 暂放在 `SellerController` | 已实现并标注 `TODO: 买家端控制器就绪后由其接管` | 林初俊建 `BuyerController` 后迁出，**避免两处重复映射导致启动失败** | 林初俊 |
| TD-4 | 验收清单中 history 写 POST，[[接口设计]] 定义为 GET | 已按接口设计文档实现为 GET，与设计文档保持一致 | 无（已对齐） | — |

## 四、集成注意事项（给林初俊）

1. **不要重复实现** `GET /api/product`——王振涛已实现，你只需把它迁到 `BuyerController` 并从 `SellerController` 删除。两处同时映射同一路径会导致 Spring Boot 启动报错 `Ambiguous mapping`。
2. **替换临时方法时**：`ProductService` 中三处 `TODO` 注释（`enterDealTemp`、`markResultTemp`、`disposeFailedTemp`）删除后，改为注入 `TradeService` 调用同名方法，签名保持一致。
3. **口令码生成**必须保证全局唯一（当前数据库层有唯一约束兜底）。若采纳 TD-1 改为应用层校验，则需在生成时查询未终态意向是否已存在该码。
4. **红线文件不要改**：`pom.xml`、`application.yml`、`entity/`、`repository/`、`exception/`、`config/`。如需改动先在群里说明。

## 五、明天（10-08）演示口径

- **Java 版**：可演示卖家端全链路（登录 → 发布商品 → 冻结/解冻 → 查看历史），买家端尚不能演示。
- **Node 原型**：可演示完整买卖双方全链路（含提交意向、口令码、排队、交易、重排），作为规则验证依据。
- 演示时须主动说明："原型为 Node.js 规则验证实现，正式交付为 Java，卖家端已迁移完成并通过编译与接口测试。"
