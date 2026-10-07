# 在线购物系统（软件工程实践一 · 第1组）

> 课程：软件工程实践（一）　|　团队规模：5 人　|　开发方式：Scrum 敏捷迭代
> 技术栈：Java（主） + Python（辅）、Maven、Docker、JUnit / Selenium / JMeter

## 一、团队与角色

| 姓名 | 角色 | 主要职责 | GitHub 账号 |
| --- | --- | --- | --- |
| 陈星宇 | 组长 / Scrum Master | 排期、会议、禅道、进度跟踪、材料统筹；兼 DevOps/测试岗 | xych5q |
| 董嘉润 | 产品经理 PO | 需求订单优先级、用户故事、需求洽谈、问题库 | 待填 |
| 王振涛 | 后端开发 | 领域模型、接口设计、卖家端模块 | 待填 |
| 林初俊 | 后端开发 | 商品/交易模块、单元测试 | 待填 |
| 嵇宇锋 | 前端开发 | 商品详情页、意向提交页 | 待填 |

> 测试 / DevOps 岗建议每个冲刺轮换一次，保证 5 个人都能讲清构建、部署、运行的全过程。

## 二、迭代计划

| 迭代 | 起止 | 小目标 | 评审 | 报告 |
| --- | --- | --- | --- | --- |
| 迭代 1 | 2026-09-17 ~ 10-08 | 基线需求 MVP | 10-08 | [第1次阶段性报告](docs/reports/第1次阶段性报告.md) |
| 迭代 2 | 10-09 ~ 10-22 | 待定（升级需求） |  |  |
| ... |  |  |  |  |
| 迭代 7 | ~ 12-31 | 期末验收 | 12-31 | 项目验收报告 |

关键节点：**10-08 第一次评审**、**10-15 基线需求验收**、**12-31 期末验收答辩**。

## 三、文档中心

- [Wiki 首页](docs/Wiki首页.md)
- [需求规格说明书](docs/requirements/)
- [阶段性报告](docs/reports/)
- [会议记录](docs/meeting/)
- [验收材料清单](docs/验收材料清单.md)
- [协作规范](CONTRIBUTING.md)

## 四、目录结构

```
.
├── README.md                 仓库首页
├── CONTRIBUTING.md           协作规范（分支 / 提交 / PR）
├── pom.xml                   Maven 构建文件
├── src/
│   ├── main/java/...         主源码（规范包结构）
│   └── test/java/...         测试源码
├── build/                    构建脚本 + 构建手册
├── deploy/                   部署脚本 / Dockerfile + 部署手册
├── test-cases/               测试用例清单 + 自动化脚本 + 演示录屏
└── docs/                     全部过程文档（Markdown）
    ├── requirements/         需求（用例表、业务流程图）
    ├── design/               设计说明
    ├── reports/              第 N 次阶段性报告
    └── meeting/              小组会议记录
```

## 五、如何构建与运行

见 [build/构建手册.md](build/构建手册.md) 与 [deploy/部署手册.md](deploy/部署手册.md)。

```bash
mvn clean package          # 构建
docker build -t shop .     # 构建镜像
docker run -p 8080:8080 shop
```
