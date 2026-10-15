# Java 正式实现进度

> 第 1 组 · 迭代 1　|　仓库目录：`server/`（Spring Boot 3.3.4 + JDK 21 + Maven + H2）
> 实现依据：[[需求规格说明书]] v1.4 的 FR-S1~S10 / FR-B1~B5 与第 4 节状态机；这里记录 Java 侧的实现情况。

## 一、分层与责任人

| 层 | 内容 | 责任人 | 状态 |
| --- | --- | --- | --- |
| 骨架 | `pom.xml`、`application.yml`、`entity/`、`repository/`、`exception/`、`config/` | 陈星宇 | 已完成（`ce56672`） |
| 卖家端 | `SellerService`、`ProductService`、`SellerController`（12 接口，730 行） | 王振涛 | 已完成（`f4d8821`） |
| 买家端 | `IntentService`、`TradeService`、`BuyerController`（5 接口，492 行） | 林初俊 | 已完成（`a42a102`） |
| 单元测试 | JUnit 5 + MockMvc，18 组用例（492 行） | 林初俊 | 已完成（`a42a102`） |
| 前端 | `static/`（5 个页面文件） | 嵇宇锋 | 已完成（`a4c7081`） |
| 构建部署 | Maven 已通；Docker 镜像云端构建成功，容器内冒烟 44/44 通过 | 陈星宇 | 已完成 |

**代码规模合计**：主代码 1237 行 + 测试 492 行 = 1729 行（不含骨架）。

## 二、卖家端已完成内容（王振涛，2026-10-07）

**代码**：`SellerService.java`（267 行）、`ProductService.java`（296 行）、`SellerController.java`（167 行），另补建 `ProductRepository.java`（骨架原缺失）。

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

**代码**：`IntentService.java`（216 行）、`TradeService.java`（184 行）、`BuyerController.java`（92 行）。

**5 个接口**：

| # | 接口 | 关键规则 |
| --- | --- | --- |
| 1 | `GET /api/product` | 买家浏览当前在售商品 |
| 2 | `POST /api/intents` | 生成 8 位口令码（字符集剔除 I/O/0/1），查库保证全库唯一，**仅在提交响应中返回一次** |
| 3 | `GET /api/intents/lookup/{code}` | 返回位次与状态；进入交易时 `position=null`、`inTransaction=true`；无效或终态码 404 |
| 4 | `PUT /api/intents/lookup/{code}` | 只改姓名电话，`submittedAt` 不动 → **排队位次不变** |
| 5 | `POST /api/intents/lookup/{code}/cancel` | 交易中拒绝撤销；其余置 `cancelled`（终态留痕） |

**交易流转规则**（依据 [[需求规格说明书]] 第 4.1~4.3 节，未作改动）：队首不可挑人、进入交易自动冻结、标记成功清队进历史、标记失败自动递补或恢复在售、重排沿用原码排到队尾。

## 四、单元测试（JUnit 5，组长本地复跑验证）

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in BuyerApiTest
[INFO] Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- in TradeFlowTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

18 组用例覆盖：登录鉴权与 token 失效、参数边界（价格 -1、空姓名电话）、重复发布拦截、三人入队位次 1/2/3、无效口令码 404、改电话位次不变、撤销后口令码失效、冻结态拒绝新意向、交易中禁止手动解冻、失败后自动递补、交易中撤销且自动递补、交易中撤销且队列空恢复在售、重排沿用原码且排到队尾、成功后队列转失败、历史流水不含口令码。

采用 `@SpringBootTest + @AutoConfigureMockMvc` 走真实 Spring MVC 流程（含统一异常处理），每个用例前清空数据保证独立。

