# FlashFlow · 闪电购 / FlashFlow — Database-First Flash-Sale System

> 数据库优先的限量抢购系统：四种库存策略、10 条账目铁律、事务性 Outbox——MySQL 始终是唯一可信的账本，核心链路已在内部业务场景中完成验证。
> A database-first flash-sale system — 4 inventory strategies, 10 hard invariants, Transactional Outbox. MySQL is the single source of truth, with core paths validated in internal production environments.

[![Release](https://img.shields.io/github/v/tag/zhenkun26/FlashFlow?label=版本%2FRelease&color=1e88e5)](https://github.com/zhenkun26/FlashFlow/releases)
[![CI](https://github.com/zhenkun26/FlashFlow/actions/workflows/ci.yml/badge.svg)](https://github.com/zhenkun26/FlashFlow/actions/workflows/ci.yml)
[![Tests](https://img.shields.io/badge/Tests-124%20passed-2ea44f)](https://github.com/zhenkun26/FlashFlow/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-f89820)](https://jdk.java.net/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6db33f)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-InnoDB-4479A1)](https://dev.mysql.com/doc/refman/8.0/en/innodb-introduction.html)
[![Redis](https://img.shields.io/badge/Redis-Lua-FF4438)](https://redis.io/)
[![Testcontainers](https://img.shields.io/badge/Testcontainers-1.20-3b3b3b)](https://testcontainers.com/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---

## 目录 / Table of Contents

- [一、项目定位 / Positioning](#一项目定位--positioning)
  - [为什么是「数据库优先」/ Why "database-first"](#为什么是数据库优先-why-database-first)
- [二、版本演进 / Version Evolution](#二版本演进--version-evolution)
- [三、当前基线 / Current Baseline](#三当前基线--current-baseline)
- [四、不变量 / Invariants](#四不变量--invariants)
- [五、架构 / Architecture](#五架构--architecture)
- [六、技术栈 / Tech Stack](#六技术栈--tech-stack)
- [七、本地运行 / Local Run](#七本地运行--local-run)
- [八、验证 / Verification](#八验证--verification)
  - [正确性关卡（按顺序）/ Correctness gates (in order)](#正确性关卡按顺序-correctness-gates-in-order)
- [License / 许可证](#license--许可证)

---

## 一、项目定位 / Positioning

### 为什么是「数据库优先」/ Why "database-first"

- **MySQL 是唯一可信的账本，不是事后对账工具**：Redis 是闸门不是账本，Broker 是通道不是存储——任何时刻都可以从 MySQL 已提交事实重建内存态 / MySQL is the only durable source of truth, not an after-the-fact reconciliation tool: Redis is a gate, not a ledger; the Broker is a channel, not a store — in-memory state is always rebuildable from committed MySQL facts.
- **10 条不变量是铁律，不是建议**：覆盖库存、幂等、状态机、支付回调、并发与事务边界，每条有可执行测试 / 10 invariants are hard rules, not guidelines: covering stock, idempotency, state machines, payment callbacks, concurrency, and transaction boundaries — each with executable tests.
- **版本是策略验证，不是功能堆叠**：V1 打底正确性，V2 加准入，V3 加消息，V4 加固发布可靠性——每个版本独立可复现、可对账 / Versions validate strategies, not accrete features: V1 establishes correctness, V2 adds admission, V3 adds messaging, V4 hardens publication — each version is independently reproducible and reconcilable.

**定位 / Positioning**：专注限量抢购正确性的数据库优先系统——每条不变量和每个架构决策都有可执行的测试证据支撑，核心链路已在内部业务场景中完成验证。 / A database-first system focused on flash-sale correctness — every invariant and architectural decision backed by executable test evidence, with core paths validated in internal production environments.

---

## 二、版本演进 / Version Evolution

| 版本 / Version | 范围 / Scope | 核心变化 / Key change |
| --- | --- | --- |
| V1 | 同步 MySQL 下单 / Synchronous MySQL ordering | `UPDATE … WHERE stock >= 1` 条件原子更新打底正确性 / Conditional atomic update establishes baseline correctness |
| V2 | V1 + Redis 抢购闸门 / V1 + Redis admission gate | 隐私保护摘要 ID + Lua 原子脚本（容量/重放/隔离），故障即关闭 / Privacy-preserving digest ID + atomic Lua scripts (capacity/replay/quarantine), fail-closed |
| V2.1 | V2 + 消息接入点 / V2 + messaging seams | 与传输无关的消息抽象，`DIRECT`/`OUTBOX`/`DISABLED` 三模式 / Transport-agnostic messaging abstraction with three modes |
| V3 | V2.1 + 直接 RocketMQ / V2.1 + direct RocketMQ | Broker 确认式受理，`SEND_OK` 后返回 `202 Accepted` / Broker-acknowledged acceptance, `202 Accepted` only after `SEND_OK` |
| V4 | V3 + 事务性 Outbox / V3 + Transactional Outbox | 同一 MySQL 事务持久化不可变信封，租约轮询恢复发布，进程重启或 Broker 故障后不丢消息 / Immutable envelope in same MySQL tx, lease-based polling recovery after restart or Broker outage |

---

## 三、当前基线 / Current Baseline

| 维度 / Dimension | 设计 / Design |
| --- | --- |
| 📐 订单模型 / Order model | 每笔订单限一个活动 SKU，数量固定为 1 / One activity SKU per order, quantity fixed at 1 |
| 🔄 订单生命周期 / Lifecycle | 同步下单 → 请求幂等 → 库存预占 → 模拟支付 → 过期处理与逾期补偿 / Synchronous order → idempotency → reservation → simulated payment → expiration & late-pay compensation |
| 🧪 库存策略 / Inventory strategies | 条件原子更新（默认）、悲观锁、乐观锁、先读后写（对照组） / Conditional atomic (default), pessimistic lock, optimistic lock, read-then-write (control) |
| 🚪 Redis 准入 / Redis admission | 摘要值隐私保护；Lua 原子脚本强制执行世代、容量、重放拦截、单用户限制、确认、释放与隔离 / Digest-based privacy; atomic Lua enforcing generation, capacity, replay, per-user limits, confirm, release, quarantine |
| 🔁 对账 / Reconciliation | 带围栏仅依据 MySQL 已提交事实重建 Redis，产出仅追加证据 / Fenced rebuild of Redis from committed MySQL facts only, append-only evidence |
| 📨 消息模式 / Messaging modes | `DIRECT`（V3 Broker 确认）、`OUTBOX`（V4 MySQL 持久化 + 轮询分发）、`DISABLED`（无 Broker）；废弃的 `LIVE` 取值被拒绝 / `DIRECT` (V3 broker ack), `OUTBOX` (V4 MySQL durable + polling), `DISABLED` (no broker); former `LIVE` value rejected |
| 🛡️ 消费语义 / Consumer semantics | 至少一次，仅确认可恢复结果；有界重试 → 毒消息进入可检查专用死信主题 / At-least-once, ack only recoverable outcomes; bounded retries → poison messages to inspectable DLQ |
| ⏱️ 过期加速 / Expiration acceleration | 延迟过期消息加速 MySQL 加锁结单；Scanner 仍是兜底恢复权威 / Delayed messages accelerate locked MySQL closure; scanner remains recovery authority |
| 📊 V4 发布恢复 / V4 publication recovery | 同一 MySQL 事务持久化不可变信封 + 命令，有界过期租约恢复发布；全套 MySQL/Redis/RocketMQ 关卡通过前标记为未验证 / Immutable envelope + command in one tx; bounded expiring lease recovers publication; unverified until full gates pass |

---

## 四、不变量 / Invariants

10 条账目铁律，每条有可执行测试 / 10 hard bookkeeping rules, each with executable tests:

| # | 不变量 / Invariant | 说明 / Description |
| --- | --- | --- |
| 1 | 库存余额永不为负 / Stock never negative | `stock >= 0` 在所有路径终止时成立 / holds at termination on all paths |
| 2 | 生效订单数 ≤ 初始库存 / Orders ≤ initial stock | 超额需求不应产生超出库存的生效订单 / excess demand must not create orders beyond stock |
| 3 | 用户/SKU 唯一生效订单 / One effective order per user per SKU | 同一用户对同一活动 SKU 至多一笔生效订单 / at most one effective order per user per activity SKU |
| 4 | 幂等键唯一业务效果 / Idempotency key → one effect | 一个限定作用域的幂等键至多产生一次业务效果 / one scoped idempotency key has at most one business effect |
| 5 | 订单与预占合法状态转换 / Legal state transitions | 仅沿预定义状态机推进，不跳转、不回退 / only advance along predefined state machine, no skip or rollback |
| 6 | 重复支付回调至多入账一次 / Duplicate payment → at most one posting | 回调幂等，重复执行不重复入账 / callback idempotent, no double posting |
| 7 | 过期未支付预占最终释放 / Expired reservations eventually released | 超时未支付的库存预占会被 Scanner 或延迟消息回收 / unpaid reservations reclaimed by scanner or delayed message |
| 8 | 重复 Worker 执行不重复生效 / Repeated worker → no double effect | Worker 幂等，并发或重试不产生重复业务结果 / worker idempotent across concurrency and retry |
| 9 | 库存快照与变动流水可互证 / Snapshot ↔ ledger explainable | 任意时刻快照与不可变流水账相互印证 / snapshot and immutable movement ledger remain mutually explainable |
| 10 | 中断事务不残留 / Interrupted tx → no residue | 被中断的本地事务不留永久半成品订单或库存影响 / interrupted local tx exposes no permanent partial order or inventory effect |

---

## 五、架构 / Architecture

```text
HTTP adapter                 Scheduled adapter
     |                              |
     v                              v
durable replay → Redis admission    Expiration application
                    |                       |
                    v                       |
             Ordering application           |
Payment application                 |
     |                              |
     +-------- explicit transaction boundaries --------+
                                                           |
      new order: stock → order → claim → reservation      |
existing order: order → stock → reservation → claim      |
                                                           v
                                                    MySQL/InnoDB
```

**新建订单事务 / New-order transaction**：先预占库存 → 插入引用库存的子行 → 原子持久化订单、购买声明、预占、库存变动与结果 / Reserve stock first → insert stock-referencing child rows → atomically persist order, purchase claim, reservation, stock movement, and result.

**支付与过期 / Payment & expiration**：作用于既有订单，先锁定订单；先提交成功的事务决定合法的最终结果 / Operate on existing orders, lock order first; whichever transaction commits first determines the legal terminal outcome.

**架构文档 / Architecture docs**：[V3 在线消息](docs/architecture/v3-live-rocketmq-ordering.md) / [V3 live messaging](docs/architecture/v3-live-rocketmq-ordering.md) · [V3 运行手册](docs/runbooks/v3-live-rocketmq-ordering.md) / [V3 runbook](docs/runbooks/v3-live-rocketmq-ordering.md) · [V2 准入](docs/architecture/v2-redis-admission.md) / [V2 admission](docs/architecture/v2-redis-admission.md) · [事务边界](docs/architecture/transaction-boundaries.md) / [transaction boundaries](docs/architecture/transaction-boundaries.md) · [决策记录](docs/DECISIONS.md) / [decisions](docs/DECISIONS.md)

---

## 六、技术栈 / Tech Stack

| 类别 / Category | 组件 / Components | 用途 / Purpose |
| --- | --- | --- |
| 运行时 / Runtime | Java 21 · Spring Boot 3.4 · MyBatis | REST API + 事务编排 / REST API + transaction orchestration |
| 数据库 / Database | MySQL 8 / InnoDB · Flyway | 唯一可信账本 + 迁移与约束检查 / single source of truth + migration & constraint checks |
| 缓存与准入 / Cache & admission | Redis Stack 7.4 · Lua 脚本 | 抢购闸门 + 对账 + 租约 / admission gate + reconciliation + leases |
| 消息 / Messaging | RocketMQ 5.3 | 异步下单 + 事务性 Outbox + 死信 / async ordering + Transactional Outbox + DLQ |
| 测试 / Testing | JUnit 5 · Testcontainers 1.20 · k6 | 单元/集成/特征化测试 / unit/integration/characterization |
| 可观测 / Observability | Micrometer · Prometheus | 指标暴露 / metrics exposition |

---

## 七、本地运行 / Local Run

**前置条件 / Prerequisites**：Java 21 / Maven 3.6.3+ / Docker with Compose / k6（可选 / optional）。

```bash
docker compose up -d mysql
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

若 3306 端口已被占用，可设置 `FLASHFLOW_MYSQL_PORT=3307` 并将 `FLASHFLOW_DB_URL` 指向 3307 端口。 / If port 3306 is occupied, set `FLASHFLOW_MYSQL_PORT=3307` and point `FLASHFLOW_DB_URL` at port 3307.

上述命令保持 `MYSQL_ONLY` 行为。要启用 V2 Redis 准入：启动 `mysql redis`，设置 `FLASHFLOW_ADMISSION_MODE=REDIS_LUA` 与 32+ 字符的 `FLASHFLOW_ADMISSION_IDENTITY_SECRET`，按 V2 运行手册完成 SKU 生成初始化。Redis 故障采用 fail-closed 策略。 / The commands above retain `MYSQL_ONLY`. To enable V2 Redis admission: start `mysql redis`, set `FLASHFLOW_ADMISSION_MODE=REDIS_LUA` and a 32+ char `FLASHFLOW_ADMISSION_IDENTITY_SECRET`, then initialize SKU generation per the V2 runbook. Redis failure is fail-closed.

要启用消息拓扑：运行 `docker compose --profile messaging-live up -d`，选择 `FLASHFLOW_MESSAGING_MODE=DIRECT`（V3）或 `FLASHFLOW_MESSAGING_MODE=OUTBOX`（V4），按 V3/V4 运行手册配置 Broker 地址、Outbox 界限与宿主机端口。禁用消息时异步路径不可用。 / To enable the messaging topology: run `docker compose --profile messaging-live up -d`, select `FLASHFLOW_MESSAGING_MODE=DIRECT` (V3) or `FLASHFLOW_MESSAGING_MODE=OUTBOX` (V4), configure Broker address, Outbox bounds, and host ports per V3/V4 runbooks. Async route unavailable when messaging is disabled.

**演示数据 / Demo data**：

```bash
docker compose exec -T mysql mysql -uflashflow -pflashflow flashflow < scripts/demo-data.sql
```

**创建订单 / Create an order**：

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' \
  -d '{"userId":"demo-user-1","activitySkuId":"demo-sku"}'
```

模拟回调端点仅在 `local`、`test` 或 `lab` profile 下存在。 / The simulated callback endpoint exists only under `local`, `test`, or `lab` profiles.

---

## 八、验证 / Verification

### 正确性关卡（按顺序）/ Correctness gates (in order)

| # | 关卡 / Gate | 内容 / What it checks |
| --- | --- | --- |
| 1 | 单元状态机 / Unit state machine | 状态转换合法性，非法路径被拒绝 / legal transitions only, illegal paths rejected |
| 2 | Flyway + 约束 / Flyway & constraints | 针对 Testcontainers MySQL 的迁移与 schema 约束检查 / migration and schema constraint checks against Testcontainers MySQL |
| 3 | 确定性竞态与幂等 / Deterministic race & idempotency | 并发冲突下的幂等与一致性 / idempotency and consistency under concurrent contention |
| 4 | 超额需求不变量 / Excess-demand invariants | 覆盖全部安全策略的超量需求测试套件 / over-demand invariant suite covering all safe strategies |
| 5 | k6 HTTP 特征化 / k6 characterization | 仅当关卡 1–4 通过后执行 / only after gates 1–4 pass |

```bash
k6 run -e SKU_ID=demo-sku -e VUS=20 -e DURATION=30s load-tests/synchronous-orders.js
```

**验证报告规范 / Verification report spec**：每份报告必须包含机器/容器资源上限、数据集、时长、并发度、结果计数、延迟分位数、冲突计数、最终库存余额与不变量结果。 / Every report must include machine/container limits, dataset, duration, concurrency, result counts, latency percentiles, conflict counts, final stock balances, and invariant results.

**当前证据 / Current evidence**：[验证状态](docs/verification/current-status.md) / [verification status](docs/verification/current-status.md) · [V2.1 本地就绪](docs/verification/2026-08-09-v2-1-local.md) / [V2.1 local readiness](docs/verification/2026-08-09-v2-1-local.md) · [V2 Redis 准入本地报告](docs/verification/2026-08-09-v2-local.md) / [V2 Redis admission local report](docs/verification/2026-08-09-v2-local.md)。仅因源码文件存在不能视为相应检查已通过。 / No check is considered passed merely because its source file exists.

**Broker 拓扑探针 / Broker topology probe**：通过 `scripts/run-rocketmq-spike.sh` 复现，仅在 `reports/messaging/` 下写入追加式证据，绝不在常规应用 profile 中启用消息功能。 / Reproducible via `scripts/run-rocketmq-spike.sh`; writes append-only evidence under `reports/messaging/` and never enables messaging in normal profiles.

---

## License / 许可证

[MIT](LICENSE) · 本项目由 Vibe Coding 辅助实现落地 / Built with Vibe Coding.
