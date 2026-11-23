# ADR-RF-014：RocketMQ 削峰与密文信封投递

## 状态

**Accepted**（2026-11-23，用户指示「Kafka / RocketMQ 削峰，控制器把密文扔进 MQ」）·
影响：`AGENTS.md` B-10（改写）+ **B-14**（新增：MQ 上只允许密文信封）、`ADR-RF-008` §3、`R02 §7`、`R09 §5`、`PROGRESS §2 §6` ·
部分取代 `ADR-RF-008` 第 3 条（「不引入 MQ / Seata / 分布式事务」中的 MQ 部分），不取代 Seata 与分布式事务结论

## 背景

- **原口径是硬红线**：`AGENTS.md` B-10 写「不引入 MQ / Seata / 对象存储；文件本地、事件走 outbox」，
  `ADR-RF-008`（Accepted）写「不引入 MQ / Seata / 分布式事务」，`R02 §7.2` 写「不引 MQ / Seata」。
  `interview/面试专题-并发与一致性话术核查.md` 那一行（「Kafka / RocketMQ 削峰，控制器把密文扔进 MQ」）
  记的正是「我们的口径 = 不引 MQ，走 Transactional Outbox + 轮询投递 + 消费幂等 + DEAD 兜底」。
- **本轮决策反转**：用户明确要求引入 MQ 做削峰，并要求「控制器把密文扔进 MQ」。
  这是架构决策，不是实现细节——因此必须走 ADR + 红线文件同步，而不是只加一段代码。
- **真实存在的削峰位点**（不是为「像高并发」而造）：① 附件材料落盘与登记（大文件 I/O + 哈希对拍）；
  ② 征信报送与查询（外部机构调用，批量重试会形成洪峰）；③ 法定书面文件送达（外部通道调用）；
  ④ outbox 事件投递本身（DB 轮询在事件量上来后成为瓶颈）。
- **合规约束不可绕过**：MQ 的 broker 落盘、保留、重投都不可控——明文个人信息一旦进队列，
  就已经离开了「进程内函数调用」的可控边界。因此本 ADR 的核心不是「加个 MQ」，而是
  **「MQ 上只允许出现密文信封」**。
- **环境事实（2026-11-23 实测）**：本机 Docker Hub 拉取超时（`registry-1.docker.io` 超时），
  无法取得 RocketMQ 官方镜像；Maven Central 可达，故本地单节点 broker 由
  `rocketmq-namesrv` / `rocketmq-broker` 5.0.0 直接起（`data/mq-broker`，不入版本库）。
  本轮口径「先不用 MQ 集群」——单 broker 足以证明真实链路。

## 结论

1. **选型：RocketMQ 5 客户端**（`rocketmq-client:5.0.0`），**只用客户端、不引
   `rocketmq-spring-boot-starter`**：后者的自动装配条件与本仓「精确装配」纪律不咬合，
   生产者/消费者生命周期由 `infrastructure/messaging` 自管，依赖面最小、行为可预期。
   起步**单 broker、不建集群**。

2. **outbox 仍是唯一事务真相源，MQ 是其后的投递通道**：
   状态变更与事件产生同事务落库（B-11、INV 不变量全部不动），MQ 不参与事务，
   **不引入分布式事务、不引 Seata**。通用中继由 `retail-core` **单点**承担
   （`relayed_at` 标记保证每条事件只中继一次；多实例重复中继是明确禁止的）。

3. **密文信封（`CipherEnvelope`）是 MQ 上唯一的载荷形状**，顶层恰好 8 个键：
   `envelopeId / bizType / bizRef / keyVersion / alg / ciphertext / traceId / issuedAt`。
   - `EnvelopePayloadGuard` **默认拒绝**：顶层键必须全在白名单内，
     且 `payload / name / idNumber / bankAccount …` 等明文 PII 键一律拒绝投递；
   - 业务载荷（含 PII）只存在于 **密文内部**；解密后校验密文内绑定的 `bizType/bizRef`
     同信封声明一致，不一致即拒绝（防跨业务重放）；
   - 信封数据密钥**复用 `retail.pii.data-key`**（密文信封承载的就是 PII，不新增密钥面，R06 §5）；
   - 这是 `ADR-RF-007`（事件载荷禁 PII）在 MQ 通道上的对应扩展：
     **MQ 上不出现明文载荷，只出现密文信封**。

4. **默认关闭，零回归**：`retail.mq.enabled=false`（缺省）时装配 `NoopEnvelopePublisher`，
   `enabled()` 恒为 false，调用方据此走**同步路径**，对外行为与现网逐字一致；
   开关打开后才装配生产端、消费端与密钥。**两者都不许悄悄降级**：
   已启用但密钥缺失 → 启动失败（fail-closed）；已启用但加解密器缺失 → 抛错而非退回复制同步路径。

5. **同一 handler、两个入口**：同步入口与 MQ 入口调用**同一个业务处理逻辑**，
   不允许写两份实现。分支判定只看 `publisher.enabled()`。

6. **幂等双保险**：
   - 密文信封消费：`integration.mq_envelope_inbox(envelope_id, consumer)` 唯一键占位；
   - 事件中继消费：与直投**共用** `core.event_consumptions(event_id, consumer)`，
     因此「直投」与「中继」同时开启也不会重复执行业务动作（谁先到谁执行）。

7. **失败语义**（沿用直投口径）：失败 → attempts+1、返回 `RECONSUME_LATER` 让 broker 重投；
   累计 3 次 → 置 DEAD 并停止重投（一条坏消息不得阻塞整条链路）；
   结构非法的毒消息直接记账丢弃（重投多少次都不会成功）；
   broker 不可用 → 投递抛 `MqEnvelopeException`，**绝不静默吞掉**（丢信封 = 丢业务动作）。

8. **四条链路**：
   ① 附件与身份材料（文件字节 base64 后入密文信封，超 2MB 回落同步落盘；
   落盘结果写 `core.attachments` 的「待登记」占位行，register 端点按 objectKey 对拍转正）；
   ② 征信报送与查询（事件 → 密文信封 → 消费端解密 → 既有执行服务，幂等与 INV-5 门禁不变）；
   ③ 通知送达（送达内容密文入队 → 消费端解密 → 既有送达通道适配器）；
   ④ 通用 outbox → MQ 中继（relay 生产端在 retail-core，消费端在目标服务）。

9. **中继接管时直投让位**：开启 `relail-consumer-enabled` 的服务，其 `EventConsumer`
   默认由 `RF_EVENT_RELAY` 主题驱动，直投（outbox 轮询）让位；
   `retail.mq.direct-consumers` 点名的消费者仍走直投
   （秒级刚性事件，如同意撤回，走 MQ 会引入秒级延迟）。

## 后果

**变容易**：外部机构调用洪峰被队列吸收，主链路只负责「受理 + 入队」；
大文件落盘与哈希对拍不再压在请求线程上；投递具备 broker 级重投与死信隔离；
事件投递速率与消费速率解耦，可独立限速。

**变难**：多了一个外部依赖（broker 需独立部署与运维）；引入「消息可能重复、可能延迟、
可能进 DEAD」三类新状态，必须有幂等与补偿；信封密钥成为新的运维敏感项；
本地与生产的时序特征不同（异步路径下接口响应不再代表业务已完成）。

**明确不做**：不引 Seata / 分布式事务；不让 MQ 参与任何本地事务；
不把明文个人信息放进任何 MQ 消息；不缓存债权余额 / 个人信息 / 审查结果；
不建 MQ 集群（起步单 broker）；不把 MQ 作为事件一致性的唯一真相源。

## 被拒选项与理由

| 选项 | 理由 |
|---|---|
| **Kafka**（spring-kafka） | Kafka 协议更通用，但本机无 Kafka broker 镜像且 Docker Hub 不可达；
RocketMQ 客户端已在本地仓，且国内金融语境下重试/死信/延时语义更贴；两者可并存，
后续若迁 Kafka 只需替换 `infrastructure/messaging` 的适配器（端口已隔离） |
| **引 `rocketmq-spring-boot-starter`** | 其自动装配条件与本仓「精确装配、不做全包扫描」纪律不咬合，
且会引入与 Boot 3.2 组合的额外行为面；自管生命周期更可控 |
| **用 MQ 取代 outbox** | 业务表写入与消息发出的原子性无法保证，等于把金融账务的一致性押在
「先写库还是先发消息」的时序上——正是 outbox 存在的理由 |
| **broker 不可用时静默降级为同步执行** | 静默降级会让调用方以为已入队、实际同步执行，
造成重复投递与时序不可预期；宁可显式失败 |
| **附件等大对象无限入队** | base64 膨胀 + broker 单消息上限（默认 4MB）会让大文件必然失败；
必须设上限并回落同步路径 |
| **把明文载荷直接放进 MQ** | broker 落盘行永久保留，明文 PII 一旦入队无法撤回——
与 B-1 精神直接冲突，是本 ADR 最核心要避免的事故形态 |

## 权衡

用「多一个中间件 + 三个新状态（重复/延迟/DEAD）」换「外部调用洪峰不拖垮主链路」。
选择保留 outbox 作为唯一真相源（不推翻既有并发与一致性结论），
并用**密文信封**把 PII 边界从「进程内」延伸到「跨进程」——代价是信封密钥成为运维敏感项，
收益是 broker 侧不落明文个人信息。
