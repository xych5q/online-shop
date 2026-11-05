# 在线购物系统（软件工程实践一 · 第1组）

> 课程：软件工程实践（一）　|　团队规模：5 人　|　开发方式：Scrum 敏捷迭代
> 技术栈：Java 21、Spring Boot 3.3.4、Spring Data JPA、H2、Maven、JUnit 5 与 MockMvc、Docker；前端为原生 HTML / CSS / JavaScript 静态页

## 一、团队与角色

| 姓名 | 角色 | 主要职责 | GitHub 账号 |
| --- | --- | --- | --- |
| 陈星宇 | 组长 / Scrum Master | 排期、会议、禅道、进度跟踪、集成与发布、材料统筹 | [xych5q](https://github.com/xych5q) |
| 董嘉润 | 产品经理 PO | 需求订单优先级、用户故事、需求洽谈、测试与部署文档 | [jrdong666](https://github.com/jrdong666) |
| 王振涛 | 后端开发 | 交易与队列域、意向流转、接口设计 | [Tao1122-del](https://github.com/Tao1122-del) |
| 林初俊 | 后端开发 | 商品与卖家后台域、测试用例清单 | [Lotus0506](https://github.com/Lotus0506) |
| 嵇宇锋 | 前端开发 | 商品列表与详情、注册登录、卖家后台页面 | [qfwl1](https://github.com/qfwl1) |

## 二、迭代计划

| 迭代 | 起止 | 小目标 | 评审 | 报告 |
| --- | --- | --- | --- | --- |
| 迭代 1 | 2026-09-17 至 2026-10-19 | 基线需求 MVP（单商品、排队、交易冻结、卖家后台） | 第 1 次 2026-10-08 | [第 1 次阶段性评审报告](https://github.com/xych5q/online-shop/wiki/第1次阶段性评审报告) |
| 迭代 2 | 2026-10-19 至 未定 | 挑战需求 A：商品管理升级（多商品、两级类别、库存、浏览搜索、多图富媒体）与客户升级 | 第 2 次 2026-10-22、第 3 次 2026-11-05 | [第 2 次](https://github.com/xych5q/online-shop/wiki/第2次阶段性评审报告) ｜ [第 3 次](https://github.com/xych5q/online-shop/wiki/第3次阶段性评审报告) |
| 迭代 3 至 6 | 随挑战需求 B 至 E 解锁 | 挑战需求 B 至 E | 第 4 至 6 次：2026-11-19、12-03、12-17 | 各次评审时提交 |
| 迭代 7 | 至 2026-12-31 | 期末项目验收 | 第 7 次 2026-12-31 | 项目验收报告 |

关键节点：2026-10-08 第 1 次评审、2026-10-19 基线成果验收、2026-10-22 与 2026-11-05 第 2、3 次评审、2026-12-31 期末验收答辩。

> 口径：一个迭代对应一次需求升级，迭代终点为该需求的**成果验收通过**，评审是迭代过程中的演示汇报而非迭代边界。迭代 2 的截止日期取决于挑战需求 A 的最晚验收日，课程尚未公布。

## 三、当前进度

迭代 2 的五维加权进度 **100%**（需求与设计文档 0.20、后端 0.45、单元测试 0.10、前端 0.10、构建与部署 0.15，合计 112 小时，迭代 1 已验收成果不重复计分），等待成果验收。

| 项 | 迭代 1 | 迭代 2 |
| --- | --- | --- |
| 控制器接口 | 17 个 | 32 个（卖家 18、买家 6、目录 3、意向 2、类别 3） |
| JUnit 用例 | 18 条 | 77 条，全绿 |
| 前端页面 | 5 个 | 8 个（新增注册、登录、我的历史） |
| 自动化脚本 | api-smoke.sh | api-smoke-iter2.sh（60 项）、admin-api-demo.sh（18 项）、verify-all.sh（统一入口） |
| 交付物 | tag v0.1-iter1 | tag v0.2-iter2、镜像 `ghcr.io/xych5q/online-shop:0.2-iter2`、Release 三附件 |

一条命令复现全部验收：`bash test-cases/verify-all.sh`（全量单测、干净库起服务、接口冒烟、后台演示四段串联，任一步失败退出码 1）。

## 四、文档中心

过程文档以 [项目 Wiki](https://github.com/xych5q/online-shop/wiki) 为主，仓库 `docs/` 保留需求与测试原件。

- 需求：[需求规格说明书 v1.6](https://github.com/xych5q/online-shop/wiki/需求规格说明书)（Wiki）｜ [docs/requirements](docs/requirements/)
- 设计：[接口设计](https://github.com/xych5q/online-shop/wiki/接口设计)（Wiki）｜ [前端页面说明](https://github.com/xych5q/online-shop/wiki/前端页面说明)（Wiki）
- 迭代管理：[冲刺订单（迭代 2）](https://github.com/xych5q/online-shop/wiki/冲刺订单（迭代2）)（Wiki）
- 测试：[测试用例清单 · 迭代 2](test-cases/测试用例清单-迭代2.md) ｜ [第 3 次测试记录](https://github.com/xych5q/online-shop/wiki/第3次测试记录)（Wiki）
- 构建与部署：[构建手册](build/构建手册.md) ｜ [部署手册](deploy/部署手册.md) ｜ [Wiki 版](https://github.com/xych5q/online-shop/wiki/构建与部署手册)
- 报告：[第 1 次](docs/reports/第1次阶段性评审报告.md)（仓库）｜ 第 2、3 次在 Wiki
- 协作规范：[CONTRIBUTING.md](CONTRIBUTING.md)

## 五、目录结构

```
.
├── README.md                 仓库首页
├── CONTRIBUTING.md           协作规范（分支策略、提交与 PR 要求）
├── server/                   后端工程（Maven 单模块）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/group1/onlineshop/
│       │   ├── controller/   5 个控制器，32 个接口
│       │   ├── service/      业务逻辑（交易、商品、卖家、目录）
│       │   ├── entity/       实体与状态枚举
│       │   ├── repository/   数据访问
│       │   └── config/       静态资源映射、旧数据迁移、初始化
│       ├── main/resources/static/   前端静态页与脚本
│       └── test/java/        7 个测试类，77 条用例
├── build/                    构建脚本与构建手册
├── deploy/                   Dockerfile、compose、校验脚本与部署手册
├── test-cases/               用例清单、冒烟与后台演示脚本、统一入口、JMeter 压测
└── docs/                     需求、设计、报告与汇报材料
    ├── requirements/         需求规格说明书、用户故事、用例表、流程图、问题库
    ├── design/               接口设计、前端页面说明、实现进度、截图
    ├── reports/              第 1 次评审报告与三次测试记录
    └── slides/               评审汇报材料与演示录屏
```

## 六、如何构建与运行

见 [build/构建手册.md](build/构建手册.md) 与 [deploy/部署手册.md](deploy/部署手册.md)。

```bash
cd server
mvn clean package                                  # 构建，产出可执行 jar
mvn test                                           # 全量单元测试，77 条
java -jar target/online-shop-1.0.0-SNAPSHOT.jar    # 运行，默认 8080 端口

docker build -t online-shop:0.2-iter2 -f deploy/Dockerfile .
docker run -p 8080:8080 online-shop:0.2-iter2
```

默认使用 H2 内存库，启动时由 `DataInitializer` 造演示数据；上传图片存放在 `./data/uploads`，由 `/uploads/**` 映射对外提供。演示脚本与验收流程见 `test-cases/`。
