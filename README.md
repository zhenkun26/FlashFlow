# FlashFlow

FlashFlow（闪电购）是一个把「限量抢购」做到账目不出错的工程实验。V1 用同步 MySQL 下单打底正确性；V2 加入 Redis 抢购闸门；V2.1 打通与传输无关的消息接入点；V3 启用直接 RocketMQ 下单；进行中的 V4 增加轮询式事务性 Outbox，让消息在进程重启或 Broker 故障后也能恢复发布（至少一次投递）。MySQL 始终是唯一可信的账本。

FlashFlow is a database-first flash-sale lab where the books never break. V1 establishes synchronous MySQL/InnoDB correctness; V2 adds a Redis admission gate; V2.1 adds transport-neutral messaging seams; V3 activates direct RocketMQ ordering; the in-progress V4 change adds a polling Transactional Outbox so publication recovers after process restarts or Broker outages (at-least-once delivery). MySQL remains the sole durable source of truth.

## 当前 V3 基线现状与 V4 适用范围 · Current V3 baseline and V4 apply scope

- 技术栈：Java 21、Spring Boot、MyBatis、MySQL/InnoDB、Redis Lua、Flyway、JUnit、Testcontainers、Micrometer 与 k6。
- 每笔订单限一个活动 SKU，数量固定为 1。
- 同步下单、请求幂等、同一用户对同一 SKU 至多一笔生效订单、库存预占、模拟支付、过期处理与逾期未支付补偿记录。
- 四种库存策略：条件原子更新（常规默认）、悲观锁、乐观锁，以及一个不安全的"先读后写"实验对照组（laboratory control）。
- Redis 准入 ID 是保护隐私的摘要值（digest）；原子脚本强制执行世代（generation）、容量上限、重放拦截、单用户/单活动限制、确认、释放与隔离（quarantine）等约束。
- 带围栏（fenced）的对账仅依据 MySQL 已提交事实重建 Redis，并产出仅追加（append-only）的证据。
- `DIRECT` 保留 V3 内联的 Broker 确认式受理（acceptance）；`OUTBOX` 选择 V4 的 MySQL 持久化受理与轮询分发；`DISABLED` 保持无 Broker。已废弃的 `LIVE` 取值会被拒绝。
- `/api/v2/orders` 仅在收到 `SEND_OK` 后才返回 `202 Accepted`；`/api/v2/order-commands/{commandId}` 暴露调用方作用域内的持久化状态。
- 消费者采用至少一次语义，且仅确认可恢复的结果。有界重试与毒消息（poison message）最终进入可检查的专用死信主题。
- 延迟过期消息加速既有的 MySQL 加锁结单流程；扫描器（scanner）仍是兜底恢复的权威。
- V4 在同一个 MySQL 事务内，将稳定的不可变信封（envelope）与命令一同持久化，随后借助有界、带过期的租约（lease），在进程重启或 Broker 故障后恢复发布。
- 在 MySQL/Redis/真实 RocketMQ 全套验证关卡（gate）通过之前，V4 仍是未经验证的候选流程（apply workflow）。本文档不做任何关于恰好一次（exactly-once）传输、CDC、自动重放、生产可用性、持久化、延迟 SLA 或生产容量的声明。

- Java 21, Spring Boot, MyBatis, MySQL/InnoDB, Redis Lua, Flyway, JUnit, Testcontainers, Micrometer, and k6.
- One activity SKU and quantity one per order.
- Synchronous ordering, request idempotency, one effective order per user/SKU, inventory reservation, simulated payment, expiration, and late-payment compensation records.
- Four inventory strategies: conditional atomic update (normal default), pessimistic lock, optimistic lock, and an unsafe read-then-write laboratory control.
- Redis admission IDs are privacy-preserving digests; atomic scripts enforce generation, capacity, replay, per-user activity, confirmation, release, and quarantine.
- Fenced reconciliation rebuilds Redis only from committed MySQL facts and emits append-only evidence.
- `DIRECT` preserves V3 inline Broker-acknowledged acceptance, `OUTBOX` selects V4 durable MySQL acceptance and polling dispatch, and `DISABLED` remains broker-free. The former `LIVE` value is rejected.
- `/api/v2/orders` returns `202 Accepted` only after `SEND_OK`; `/api/v2/order-commands/{commandId}` exposes caller-scoped durable status.
- Consumers are at-least-once and acknowledge only recoverable outcomes. Bounded retries and poison messages lead to an inspectable dedicated dead-letter topic.
- Delayed expiration messages accelerate the existing locked MySQL closure; the scanner remains the recovery authority.
- V4 persists a stable immutable envelope beside the command in one MySQL transaction, then uses bounded expiring leases to recover publication after restart or Broker outage.
- V4 remains an unverified apply workflow until its full MySQL/Redis/real-RocketMQ gates pass. No exactly-once transport, CDC, automated replay, production availability, persistence, delay SLA, or production-capacity claim is made.

## 不变量 · Invariants

1. 库存余额永不为负。
2. 生效订单数永不超过初始库存。
3. 同一用户对同一活动 SKU 至多持有一笔生效订单。
4. 一个限定作用域的幂等键至多产生一次业务效果。
5. 订单与预占仅沿合法状态转换推进。
6. 重复的支付回调至多执行一次入账。
7. 过期未支付的预占最终会被释放。
8. 重复的工作线程执行不会重复产生业务效果。
9. 库存快照与不可变变动流水账始终可相互印证。
10. 被中断的本地事务不会留下永久的半成品订单或库存影响。

1. Inventory balances never become negative.
2. Effective orders never exceed initial stock.
3. One user has at most one effective order for an activity SKU.
4. One scoped idempotency key has at most one business effect.
5. Orders and reservations only follow legal state transitions.
6. Repeated payment callbacks apply payment at most once.
7. Expired unpaid reservations are eventually released.
8. Repeated worker execution does not repeat a business effect.
9. The stock snapshot and immutable movement ledger remain explainable together.
10. An interrupted local transaction exposes no permanent partial order or inventory effect.

## 架构 · Architecture

```text
HTTP adapter                 Scheduled adapter
     |                              |
     v                              v
durable replay -> Redis admission   Expiration application
                    |                       |
                    v                       |
             Ordering application          |
Payment application                 |
     |                              |
     +--------- explicit transaction boundaries --------+
                                                            |
       new order: stock -> order -> claim -> reservation    |
 existing order: order -> stock -> reservation -> claim    |
                                                            v
                                                     MySQL/InnoDB
```

安全的新建订单事务会先预占库存，再插入引用库存的子行，随后将订单、购买声明（purchase claim）、预占、库存变动与结果一并原子地持久化。支付与过期处理作用于既有订单，先锁定订单；先提交成功的事务决定合法的最终结果。

The safe new-order transaction reserves stock before inserting stock-referencing child rows, then persists the order, purchase claim, reservation, stock movement, and result atomically. Payment and expiration operate on existing orders and lock the order first; whichever transaction commits first determines the legal terminal outcome.

另见：[V3 在线消息架构](docs/architecture/v3-live-rocketmq-ordering.md)、[V3 运行手册](docs/runbooks/v3-live-rocketmq-ordering.md)、[V2 准入架构](docs/architecture/v2-redis-admission.md)、[事务边界](docs/architecture/transaction-boundaries.md)与[决策记录](docs/DECISIONS.md)。

See [V3 live messaging architecture](docs/architecture/v3-live-rocketmq-ordering.md), [V3 runbook](docs/runbooks/v3-live-rocketmq-ordering.md), [V2 admission architecture](docs/architecture/v2-redis-admission.md), [transaction boundaries](docs/architecture/transaction-boundaries.md), and [decisions](docs/DECISIONS.md).

逐版本变更及其证据边界记录于[变更日志](CHANGELOG.md)。

Release-by-release changes and their evidence boundaries are recorded in the [changelog](CHANGELOG.md).

## 本地运行 · Local run

前置条件：Java 21、Maven 3.6.3+、带 Compose 的 Docker，以及可选的 k6。

Prerequisites: Java 21, Maven 3.6.3+, Docker with Compose, and optionally k6.

```bash
docker compose up -d mysql
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

若 3306 端口已被占用，可为 Compose 设置 `FLASHFLOW_MYSQL_PORT=3307`，并在启动应用时将 `FLASHFLOW_DB_URL` 指向 3307 端口。

If port 3306 is already occupied, set `FLASHFLOW_MYSQL_PORT=3307` for Compose and point `FLASHFLOW_DB_URL` at port 3307 when starting the application.

上述命令明确保持 `MYSQL_ONLY` 行为。要启用 V2，请启动 `mysql redis`，设置 `FLASHFLOW_ADMISSION_MODE=REDIS_LUA` 与至少 32 个字符的 `FLASHFLOW_ADMISSION_IDENTITY_SECRET`，再按 V2 运行手册所述完成 SKU 生成初始化。Redis 故障时采用失败即关闭（fail-closed）策略；绝不允许回退到未经准入的 MySQL 尝试。

The commands above explicitly retain `MYSQL_ONLY` behavior. To enable V2, start `mysql redis`, set `FLASHFLOW_ADMISSION_MODE=REDIS_LUA` and a 32+ character `FLASHFLOW_ADMISSION_IDENTITY_SECRET`, then initialize the SKU generation as described in the V2 runbook. Redis failure is fail-closed; it never falls back to an unadmitted MySQL attempt.

要启用消息实验室拓扑，运行 `docker compose --profile messaging-live up -d`，并选择 `FLASHFLOW_MESSAGING_MODE=DIRECT`（V3 对照组）或 `FLASHFLOW_MESSAGING_MODE=OUTBOX`（V4）。按 V3/V4 运行手册配置对外公布的 Broker 地址、Outbox 界限与宿主机端口。禁用消息功能时，异步路径不可用。

To enable the messaging laboratory topology, run `docker compose --profile messaging-live up -d` and select `FLASHFLOW_MESSAGING_MODE=DIRECT` for the V3 control or `FLASHFLOW_MESSAGING_MODE=OUTBOX` for V4. Configure the advertised Broker address, Outbox bounds, and host ports as described in the V3/V4 runbooks. The asynchronous route is unavailable when messaging is disabled.

载入一次性演示数据：

Load disposable demonstration data:

```bash
docker compose exec -T mysql mysql -uflashflow -pflashflow flashflow < scripts/demo-data.sql
```

创建一笔订单：

Create an order:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' \
  -d '{"userId":"demo-user-1","activitySkuId":"demo-sku"}'
```

模拟回调端点仅存在于 `local`、`test` 或 `lab` profile 下。

The simulated callback endpoint exists only under `local`, `test`, or `lab` profiles.

## 验证 · Verification

正确性关卡（gate），按顺序：

Correctness gates, in order:

1. 单元级状态机检查。
2. 针对 Testcontainers MySQL 的 Flyway 迁移与约束检查。
3. 确定性竞态与幂等检查。
4. 覆盖全部安全策略的超量需求不变量测试套件。
5. 仅当关卡 1–4 全部通过后，才用 k6 进行 HTTP 特征化（characterization）测试。

1. Unit state-machine checks.
2. Flyway and constraint checks against Testcontainers MySQL.
3. Deterministic race and idempotency checks.
4. Excess-demand invariant suites for all safe strategies.
5. HTTP characterization with k6 only after gates 1-4 pass.

测试通过后运行 k6：

Run k6 after tests pass:

```bash
k6 run -e SKU_ID=demo-sku -e VUS=20 -e DURATION=30s load-tests/synchronous-orders.js
```

每份实验报告必须包含：机器/容器资源上限、数据集、时长、并发度、结果计数、延迟分位数、冲突计数、最终库存余额与不变量结果。本地 Docker 结果不能作为生产高可用性或普适 QPS 数值的证据。

Every experiment report must include machine/container limits, dataset, duration, concurrency, result counts, latency percentiles, conflict counts, final stock balances, and invariant results. Local Docker results are not evidence of production high availability or a universal QPS figure.

当前执行证据记录于[验证状态](docs/verification/current-status.md)、[V2.1 本地就绪报告](docs/verification/2026-08-09-v2-1-local.md)、[V2 Redis 准入本地报告](docs/verification/2026-08-09-v2-local.md)及更早的带日期报告中。仅因源码文件存在，不能视为相应检查已经通过。

Current execution evidence is recorded in [verification status](docs/verification/current-status.md), the [V2.1 local readiness report](docs/verification/2026-08-09-v2-1-local.md), the [V2 Redis admission local report](docs/verification/2026-08-09-v2-local.md), and the earlier dated reports. No check is considered passed merely because its source file exists.

独立的 Broker 拓扑探针可通过 `scripts/run-rocketmq-spike.sh` 复现；它只在 `reports/messaging/` 下写入追加式证据，并且绝不在常规应用 profile 中启用消息功能。

The isolated broker topology probe is reproducible with `scripts/run-rocketmq-spike.sh`; it writes append-only evidence below `reports/messaging/` and never enables messaging in the normal application profile.
