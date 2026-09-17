# 认证与 CORS 回归记录

验证基线：`dev` 的 `9d9cad2`（2026-09-17）。修复分支：`fix/auth-contract-regression`。

## 已复现并修复

| 场景 | 基线行为 | 修复后行为 |
| --- | --- | --- |
| 注册成功 | HTTP 200，无 Location | HTTP 201，Location 指向 `/api/account/profile` |
| 用户名重复注册 | HTTP 400 | HTTP 409；并发插入的唯一键冲突也返回 409 |
| 清空个人资料 | MyBatis-Plus 忽略 null，数据库保留旧值 | 服务层显式更新可空字段为 SQL NULL |
| 停用账号后使用旧 JWT 查询订单 | HTTP 200 | 过滤器检查当前账号状态，返回 HTTP 401 |
| 跨域下单预检 | 允许的请求头未包含 Idempotency-Key | 显式允许该头，同时继续拒绝非白名单 Origin |

前四项通过真实 MySQL 集成测试在修复前复现。CORS 回归同时断言响应状态、允许的 Origin 和实际返回的允许请求头；仅断言预检返回 200 不足以证明浏览器可以发送全部请求头。

此前审查指出的注册 `createdAt` 回填及 JSON 序列化问题，已在此次基线的后续提交中修复。本次集成测试确认注册响应中 ID 为字符串、expiresIn 为数字、createdAt 非空且可按带时区的时间点解析。

## 复测

使用项目配置的 MySQL，或通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 指定开发数据库。WSL Docker MySQL 需保持运行，Windows 后端默认访问 `localhost:3306`。

```powershell
cd backend
$env:TRAVEL_MYSQL_TEST = 'true'
mvn -ntp test package
Remove-Item Env:TRAVEL_MYSQL_TEST

cd ../frontend
pnpm build
```

`AuthContractIntegrationTest` 使用真实 Spring HTTP 处理链、JWT 和 MyBatis-Plus/MySQL。每个测试事务结束后回滚新建的测试账号及其修改，不重置数据库、不重跑初始化脚本。未设置 `TRAVEL_MYSQL_TEST=true` 时跳过该集成测试，普通单元测试仍执行。

本次结果：45 个后端测试通过（40 个原有测试、5 个新增集成测试），后端打包通过，前端生产构建通过。前端存在既有的大分块构建警告，项目未配置 lint 脚本。

## 验证范围

本次验证集中于近期合并后的认证、个人资料、CORS 和原有交易服务测试，不代表所有后台接口或真实支付宝支付链路已完成验收。现有支付通知仍是 HMAC 测试适配器，不能等同于支付宝官方沙箱验签；未对真实支付发起操作。
