# 架构与接口说明

## 分层结构

```text
frontend (Vue SPA)
  ├─ 用户端 PublicLayout
  ├─ 管理后台 AdminLayout
  └─ 导游工作台 AdminLayout + 路由角色守卫
          │ /api
backend (Spring Boot REST)
  ├─ controller   HTTP、参数校验、权限入口
  ├─ service      业务事务、状态机、名额控制
  ├─ mapper       MyBatis-Plus BaseMapper
  ├─ entity       领域持久化模型
  └─ common       JWT、RBAC、异常、分页、配置
          │
MySQL (schema.sql)
```

用户端和管理后台使用同一前端工程，权限由前端路由守卫和后端 Spring Security 双重判断。后端不把前端隐藏按钮当作权限边界。

## 核心关系

```text
TravelRoute 1 ── N RouteItineraryDay 1 ── N RouteItineraryItem
TravelRoute 1 ── N Departure N ── 1 Guide
User 1 ── N TravelOrder N ── 1 Departure
TravelOrder 1 ── N OrderTraveler (历史实名快照)
TravelOrder 1 ── 1 Payment
TravelOrder 1 ── N Refund / 0..1 Review
```

团期价格从 `departure` 复制到订单的 `adult_unit_price` / `child_unit_price`；出行人从常用资料复制到 `order_traveler`，后续修改不会影响历史订单。

## 订单状态机

```text
WAIT_PAY ──支付回调──> PAID_WAIT_CONFIRM ──工作人员确认──> CONFIRMED
   │                                             │
   └─取消──> CANCELLED                           └─导游开始──> TRAVELLING ──结束──> COMPLETED

PAID_WAIT_CONFIRM / CONFIRMED ──用户申请──> REFUND_APPLYING
REFUND_APPLYING ──同意──> REFUND_PROCESSING ──完成──> REFUNDED
REFUND_APPLYING ──拒绝──> REFUND_REJECTED ──恢复原业务状态──> 原状态
```

订单创建时在事务内用条件 `UPDATE` 增加 `reserved_people`，支付待确认仍占用名额；取消/退款释放名额。工作人员确认时再次检查 `confirmed_people + 当前人数 <= max_people`，避免并发超卖。

## 主要 API 分组

本节只描述模块边界和权限归属。精确接口与模型以 [OpenAPI 定义](openapi.yaml) 为准，通用规则以 [API 契约](API.md) 为准，避免在架构文档中复制并逐渐产生过期接口清单。

| 分组 | 主要接口 | 权限 |
|---|---|---|
| 认证 | `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me` | 注册/登录公开，其余登录 |
| 线路 | `GET /api/routes`、`GET /api/routes/{id}` | 公开 |
| 账户 | `/api/account`、`/api/travelers`、`/api/favorites`、`/api/messages` | USER |
| 订单 | `/api/orders`、`/api/payments/alipay/notify` | 用户；支付通知公开但必须按支付宝规范验签 |
| 后台 | `/api/admin/**` | STAFF / ADMIN，用户与日志接口再限制 ADMIN |
| 导游 | `/api/guide/**` | GUIDE / ADMIN，业务方法校验本人 guide_id |
| 内容 | `GET /api/articles`、`GET /api/attractions`、`/api/consultations` | 公开浏览；咨询需登录 |

## 数据合规约束

- 证件号在响应中默认脱敏；导游接口只提供最小必要联系方式和紧急联系人。
- 密码只保存 BCrypt 哈希；日志不记录密码、Token、完整证件号和支付敏感参数。
- `sql/test-data.sql` 的账号、线路和景点均标记为测试/演示数据，不代表真实经营数据。
- 高德地图只承担 Marker/Polyline 行程辅助展示，配置 Key 后加载官方 JS API，并保留版权/来源提示。
- 支付仅定位于支付宝沙箱业务链路演示，不处理真实商业资金。
