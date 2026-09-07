# CONTRIBUTING.md

本文件用于规范 `travel-agency` 项目所有成员的开发、分支、提交、合并与测试流程。所有成员在开始开发前都应阅读并遵守本规范。

## 1. 分支模型

项目长期保留：

```text
main
dev
```

- `main`：稳定分支，只保存经过完整测试、可演示、可交付的代码。
- `dev`：开发集成分支，汇总各功能分支已经完成的代码。

所有实际开发必须在 `feature/*` 分支中进行，例如：

```text
feature/login
feature/order
feature/refund
feature/user-home
feature/admin-dashboard
feature/database-init
feature/fix-order-status
```

禁止创建长期个人分支，例如：

```text
zhangsan
lisi
frontend-person
backend-person
```

分支应对应具体功能或任务，而不是对应某个成员。

## 2. 禁止直接在 main 和 dev 上开发

严禁直接在 `main` 或 `dev` 中编写、提交业务代码，也禁止直接向这两个分支 push 开发提交。

正确流程：

```text
dev
 ↓
feature/xxx
 ↓
Pull Request
 ↓
dev
 ↓
联调与测试
 ↓
main
```

所有代码和数据库变更都必须在对应的任务分支中完成：新功能使用 `feature/*`，Bug 修复使用 `fix/*`。禁止直接在 `dev` 或 `main` 分支上进行开发或修改。

## 3. 开始开发前必须同步最新 dev

每次开始新的开发任务前，必须先同步远程最新 `dev`：

```bash
git switch dev
git pull origin dev
git switch -c feature/xxx
```

例如：

```bash
git switch dev
git pull origin dev
git switch -c feature/order
```

不得基于过期的本地 `dev` 创建新分支。

如果功能分支已经存在，在继续较长时间的开发前也应同步最新 `dev`：

```bash
git switch dev
git pull origin dev

git switch feature/xxx
git merge dev
```

## 4. 一个分支只处理一个主要任务

一个 `feature/*` 分支应尽量只完成一个明确任务。

例如 `feature/order` 可以包含订单创建、订单详情以及与订单功能直接相关的修改，但不要同时加入首页重构、导游页面或大范围无关格式化。

彼此独立的任务应拆分为不同分支。

## 5. 开发过程中同步 dev

对于开发时间较长的功能，应定期将最新 `dev` 同步到当前功能分支，避免最后集中产生大量冲突。

发现冲突时，应确认冲突代码的业务含义。无法判断时应与相关模块负责人沟通，不得为了快速合并而直接覆盖其他成员代码。

处理冲突后必须重新执行相关功能测试。

## 6. Commit 规范

提交应小而明确，不要把大量无关修改混在一个 commit 中。

推荐使用 Conventional Commits 风格：

```text
feat: 新增功能
fix: 修复问题
refactor: 重构但不改变功能
docs: 修改文档
test: 添加或修改测试
chore: 构建、配置等维护工作
```

建议带模块名称：

```text
feat(order): add order creation
feat(route): add route detail api
fix(refund): restore departure capacity
docs(api): update payment api
```

禁止使用无意义的提交信息，例如：

```text
update
修改
test
123
最终版
改一下
```

## 7. 合并到 dev

功能完成后，不得直接在本地向 `dev` 提交代码。

必须通过：

```text
feature/xxx
    ↓
Pull Request
    ↓
dev
```

创建 Pull Request 前至少确认：

- 功能已经完成；
- 当前分支能够正常启动；
- 没有明显报错；
- 已同步最新 `dev`；
- 已处理与最新 `dev` 的冲突；
- 已执行与本次修改相关的测试或构建；
- 没有提交密码、密钥等敏感信息；
- 没有误提交 IDE 临时文件、构建产物或无关文件。

## 8. dev 是日常集成分支

所有完成的功能首先进入 `dev`。

前后端联调、接口联调和阶段性测试均以最新 `dev` 为基础。

当其他成员的功能合并到 `dev` 后，下一次开始开发前必须重新同步：

```bash
git switch dev
git pull origin dev
```

不要长期基于旧版本开发。

## 9. main 合并规则

正常情况下只允许：

```text
dev → main
```

禁止：

```text
feature/* → main
```

只有满足以下条件，才允许将 `dev` 合并到 `main`：

- 当前阶段计划内功能已经完成；
- 后端能够正常启动；
- 前端能够正常启动并完成生产构建；
- 数据库脚本可以正常初始化；
- 主要业务流程测试通过；
- 前后端联调通过；
- 没有已知阻断性 Bug；
- 没有严重权限或数据安全问题；
- 测试负责人确认当前版本可以进入稳定分支。

推荐通过 Pull Request：

```text
dev
 ↓
Pull Request
 ↓
main
```

不得为了方便直接向 `main` push。

## 10. Bug 修复规则

Bug 也不得直接在 `dev` 或 `main` 上修改。

例如：

```text
dev
 ↓
fix/order-status
 ↓
Pull Request
 ↓
dev
 ↓
重新测试
```

如果 Bug 是在准备合并 `main` 前发现的，应先修复并重新完成相关回归测试，再考虑：

```text
dev → main
```

## 11. 测试要求

任何代码进入 `main` 前必须完成测试。

至少包括：

```text
后端启动检查
前端启动检查
前端生产构建
数据库初始化检查
核心接口测试
核心业务流程测试
权限测试
```

核心业务流程至少应覆盖：

```text
注册 / 登录
线路浏览
团期选择
创建订单
支付结果处理
旅行社确认
取消 / 退款
订单状态变化
行程完成
评价
```

不得在已知存在阻断性问题的情况下合并到 `main`。

## 12. 数据库协作规则

数据库结构修改应同步维护：

```text
sql/schema.sql
```

测试数据维护在：

```text
sql/test-data.sql
```

不得只修改个人本地数据库而不更新 SQL 脚本。

如果修改涉及表名、字段名、字段类型、外键、索引或状态字段，应提前通知可能受影响的后端成员，并确认对应 Entity、Mapper、Service 和 API 是否需要同步修改。

## 13. API 协作规则

接口的全局规范以 `docs/API.md` 为准，模块级 Method、URL、参数、模型、响应、状态码和权限以 `docs/openapi.yaml` 为准。前端不得从页面假数据推测接口，后端不得从 Entity 或当前脚手架反推响应。

开始模块开发前，前后端应先评审并冻结该模块的 OpenAPI 契约。前端可以通过契约 Mock 独立开发，后端必须按同一份契约实现并通过契约测试，再切换真实接口联调。

接口已经冻结或被其他成员使用后，不得单方面修改。确需变更时，先修改契约并说明兼容影响，经受影响成员确认后，再同步更新前端、后端和测试。模块进度放在 Issue、任务看板或 PR 中，不在契约文档内维护完成清单。

## 14. 禁止提交的内容

不要提交：

```text
真实数据库密码
JWT Secret
支付宝私钥
高德地图私密配置
.env 中的真实密钥
IDE 临时文件
编译产物
日志文件
node_modules
```

提交前建议检查：

```bash
git status
git diff
```

确认本次提交只包含当前任务需要的文件。

## 15. 推荐日常开发流程

领取新任务：

```bash
git switch dev
git pull origin dev
git switch -c feature/xxx
```

开发并验证后：

```bash
git status
git add <本次任务相关文件>
git commit -m "feat(xxx): description"
git push -u origin feature/xxx
```

然后在 GitHub 创建：

```text
feature/xxx → dev
```

的 Pull Request。

功能合并后，下一项任务重新从最新 `dev` 创建新的 `feature/*` 分支。

## 16. 阶段发布流程

正常开发：

```text
feature/a ─┐
feature/b ─┼─→ dev
feature/c ─┘
```

集成验证：

```text
dev
 ↓
前后端联调
 ↓
功能测试
 ↓
回归测试
```

确认稳定后：

```text
dev
 ↓
Pull Request
 ↓
main
```

整个项目遵循：

```text
feature/* → dev → test → main
```

## 17. 核心原则

所有成员必须遵守以下规则：

1. **所有代码修改都在 `feature/*` 分支完成，禁止直接在 `dev` 和 `main` 上开发。**
2. **每次开始开发前必须先同步远程最新 `dev`。**
3. **功能完成后只能先合并到 `dev`，不得直接合并到 `main`。**
4. **Bug 修复必须通过 `fix/*` 分支进行。**
5. **只有 `dev` 完成联调、测试和回归验证，确认不存在阻断性问题后，才能通过 Pull Request 合并到 `main`。**

如本规范与个人开发习惯冲突，以本规范为准。
