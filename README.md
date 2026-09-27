# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## 公寓运营模块（apt-1）

代码位于 `com.evops.apartment`，四张业务表（见 `schema.sql`）：

- `t_property_ledger` 房源台账：只增不改，更正通过 REVERSE 冲销单完成
- `t_lease` 租约：`uk_lease_contract_no` 唯一约束，并发同号建档只成功一条
- `t_lease_checkout` 退租记录（验房结果），一合同只退一次
- `t_deposit_deduction` 押金扣除明细，逐条留痕

所有写入落库审计四字段：`request_no`、`operator`、`biz_time`、`data_version`。

接口（`/api/apartment/**` 已在 Shiro 放行，首版操作人从请求体传入）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/apartment/property/entries` | 台账登记 |
| POST | `/api/apartment/property/reversals` | 台账冲销（`refEntryId` 指向原单，仅可冲销一次） |
| GET | `/api/apartment/property/entries?propertyCode=` | 台账流水查询 |
| POST | `/api/apartment/leases` | 租约建档（合同号重复返回唯一约束冲突） |
| GET | `/api/apartment/leases?contractNo=&startFrom=&startTo=` | 合同号 + 起租日期组合检索 |
| POST | `/api/apartment/leases/checkout` | 退租：验房结果 + 扣除明细 + 租约关闭，同事务，失败整体回退 |
| GET | `/api/apartment/leases/{contractNo}/checkout` | 退租详情（含扣除明细） |
| GET | `/api/apartment/leases/export` | 租约导出 CSV（参数同检索接口，敏感字段脱敏，UTF-8 BOM） |

注意：`application.yml` 中 `shiro.annotations.enabled=false` 不可随意打开——Shiro 注解自动代理会把事务代理再包一层 JDK 动态代理，导致 Service 按类型注入失败。

测试：`mvn test`（`ApartmentFlowTest`，H2 内存库），覆盖并发建档唯一性、退租事务完整性与回退、重复退租、台账冲销。

## 敏感信息脱敏（apt-2）

代码位于 `com.evops.apartment.mask`，范围仅限公寓模块（其他入口不挂载该序列化器，行为不变）：

- 字段打 `@Sensitive(SensitiveType.NAME|PHONE|ID_CARD)` 即同时作用于**接口返回**与**租约导出**，同一字段两条出口共用同一份规则，不会再出现口径漂移。
- 默认规则：姓名留姓（`张三` → `张*`，单字整体遮蔽）、手机号留前 3 后 4（`138****0000`）、证件号留前 6 后 4（`110101********1234`）。保留位数之和覆盖全串时整体遮蔽。
- 规则集中在 `evops.sensitive`（见 `application.yml` 默认底）与外部文件 `config/sensitive-rules.yml`；**改文件自动热加载**（约 1 秒内生效，无需重启），文件缺失回退默认，解析失败保留在用规则并告警。文件内只写要覆盖的类型即可，`enabled: false` 为临时关闭开关。
- 脱敏只发生在出口：入参正常接收、数据库仍存明文（`Lease.tenantIdCard` 为 apt-2 新增列，旧文件库启动时幂等 `ALTER TABLE ADD COLUMN IF NOT EXISTS`）。
- 新增导出：`GET /api/apartment/leases/export`，检索参数同租约列表，输出带 BOM 的 UTF-8 CSV（Excel 直接打开不乱码）。

测试：`SensitiveMaskingTest` 覆盖接口脱敏、库内明文、CSV 与接口同口径、规则热加载/坏文件/删除回退/总开关。

## 多角色权限并集（apt-3）

代码位于 `com.evops.rbac`，公寓模块在 `com.evops.apartment.security` 接入。一个账号可兼多个角色：

- **菜单/按钮取并集**：每请求实时联表组装全部**启用**角色的权限点并集（`GET /api/auth/me` 的 `menus`、`buttons`），接口经 `@RequirePermission("权限码")` 校验。
- **数据范围按最宽角色放行**：`SELF（仅本人经办） < SCOPE（指定片区） < ALL（全部）`，多角色取最宽；SCOPE 多片区取小区并集。租约检索/导出、台账流水按范围过滤，建档/冲销/退租按范围校验。
- **放行留审计**：每一次数据访问落 `t_data_scope_audit`（账号、当次角色、最宽范围、授权来源角色、小区、资源、条件摘要、行数、时间）；审计写失败该请求失败（fail-closed，不放无痕数据）。
- **停用即时收权**：授权不缓存。角色停用后下一次请求即从并集移除；账号停用当次请求即 401 且吊销全部令牌，重新启用须重新登录。
- 认证：`POST /api/auth/login`（账号口令）→ 令牌放 `X-Auth-Token` 头（默认 12 小时）；`POST /api/auth/logout`。未登录/失效 401，权限不足/越范围 403。
- 应急开关：`evops.rbac.enabled=false` 重启后旁路全部鉴权（仅限紧急窗口）。**回滚步骤见 `docs/apt-3/上线说明.md`，验证记录见 `docs/apt-3/验证记录.md`。**

种子账号（首登后必须改密）：steward01/steward123（管家, SELF）、supervisor01/supervisor123（主管, ALL）、area01/area123（片区管家, SCOPE=示例小区）、combo01/combo123（管家+主管, 验证并集）、admin/admin123（管理员）。

测试：`RbacUnionPermissionTest`（并集菜单按钮、SELF/SCOPE/ALL 过滤与审计、角色停用即时收权与恢复、账号停用令牌失效、401/403），`SensitiveMaskingTest` 改为登录后访问。

## 交接班线上化 + DFA 敏感词过滤（apt-4）

交接班代码位于 `com.evops.apartment`（`Handover*`），敏感词代码位于 `com.evops.apartment.sensitive`：

- **交接班线上化**：纸质交接本转线上。交班登记（DRAFT）→ 接班人确认（CONFIRMED），
  只增不删、不可重复确认、禁止自交自接；按小区/楼栋/班次/日期区间/状态检索，数据范围沿用 apt-3
  （SELF=本人交班或接班，SCOPE=授权小区，ALL=全部）并逐请求落放行审计。
- **DFA 敏感词过滤**：词典存库 `t_sensitive_word`，后台维护（增改、启停）后引擎整体重建、即时生效。
  - 文本先**归一化**再匹配：全角→半角（NFKC）、大小写折叠、零宽字符剥离、形近替换
    （`0→o 1→i 3→e 4→a 5→s 7→t @→a $→s`），并丢弃所有非字母数字字符；
  - **分隔符插空也命中**：`代-开发票`、`代 开·发*票`、全角/大小写/形近变形均命中「代开发票」；
  - **位置可定位**：命中回映原文字位 `[start,end)`，区间覆盖中间被剥离的插空字符；
  - **分级处理**：1=打码（区间遮蔽后放行落库），2=拦截（业务失败、整体回滚）。
- **操作痕迹**：命中逐词逐字段落 `t_sensitive_audit`（账号、请求号、模块、业务定位、字段、词条、
  字位、原文片段、打码结果、时间）；**拦截痕迹走 REQUIRES_NEW 独立事务，业务回滚痕迹仍在，出状况定位到人**。
  词典维护落 `t_sensitive_word_op_log`（变更前后快照、操作人、时间）。
- 接入位置：交接班（地址/待办/内容）、台账登记（地址/备注）与冲销（原因）、租约建档（新增 `remark` 列）、
  退租（验房备注、扣除项名称/备注）。结构化字段（姓名/手机/证件号）不扫描，避免误伤。
- 权限：管家/片区管家/主管可登记与确认交接班；`apartment:sensitive:manage`（词库维护）仅管理员；
  `apartment:sensitive:audit`（命中与维护留痕查看）管理员+主管。应急开关
  `evops.sensitive-word.enabled=false`（仅限紧急窗口）。**上线与回滚见 `docs/apt-4/上线说明.md`，验证记录见 `docs/apt-4/验证记录.md`。**

接口（均需登录，`/api/**`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/apartment/handovers` | 交接班登记（交班） |
| POST | `/api/apartment/handovers/{id}/confirm` | 接班确认（不可自交自接、不可重复确认） |
| GET | `/api/apartment/handovers` | 交接班检索（community/building/shift/dateFrom/dateTo/status） |
| GET | `/api/apartment/handovers/{id}` | 交接班详情 |
| GET/POST | `/api/apartment/sensitive/words[/{id}[/status]]` | 敏感词查询/新增/修改/启停（manage） |
| GET | `/api/apartment/sensitive/word-logs` | 词典维护留痕（audit） |
| GET | `/api/apartment/sensitive/audits` | 命中痕迹检索（audit，可按 username/module/bizRef/actionTaken 过滤） |

测试：`DfaEngineTest`（插空/零宽/全角/形近/位置/最长匹配/重叠去重）、`SensitiveWordRegistryTest`（分级/开关）、
`SensitiveFlowTest`（打码落库+痕迹定位、拦截回滚但留痕、交接班状态机、词典维护即时生效、403 权限）。

## 签约归档后台任务化 + 电子签章对接（apt-5）

慢操作（签约 + 电子签章 + 归档）挪后台，代码位于 `com.evops.apartment.task` 与 `com.evops.apartment.esign`：

- **任务表登记**：发起接口只做范围校验/房源核验/敏感词处置后登记 `t_async_task` 立即返回任务号；
  后台调度器 DB CAS 认领 PENDING 任务（多线程/多实例不重复执行），平台调用不占请求线程与长事务。
- **进度 5% 一步**：签约归档拆 **20 个一致步骤**（平台自检→房源核验→建档→用印→文书→租客签署→
  盖章→回传文件→归档→复核），每步 +5%；轮询详情带 status/progress/currentStep/stepName。
- **可取消，停在下个一致步骤**：执行器每步执行前重读取消标记，当前步骤提交完才停，
  进度恒为 5 的整数倍；排队/等签署中取消立即停在当前断点。取消与认领/等待退回的状态翻转
  并发时由两处 CAS 夹击闭合：等待退回仅在未申请取消时才降回 PENDING，否则就地 CANCELLED；
  取消接口条件更新落空按最新状态有界收敛——取消不会被吞掉而后台续跑。
- **失败断点重跑**：FAILED 带 fail_step/fail_reason，retry 从 current_step+1 续跑；
  已完成步骤不重跑 + 库内写入先查后写（合同号/归档 uk）+ 平台写操作按幂等号 `EVOPS-{合同号}` 去重，
  不产生重复租约/归档/用印；宕机遗留 RUNNING 启动时回收为 PENDING。
- **记录保留 14 天**：终态 retain_until=终态+14 天，每日 01:17 清理。
- **电子签章对接**：默认 `evops.esign.provider=mock` 进程内模拟平台，零外部依赖，
  演示台 `/api/esign-demo/console`（可手动模拟租客签署）；下周接真实平台改 `provider=http`
  （HMAC 签名、超时、bizNo 幂等），只改配置/客户端字段映射，任务步骤与接口不变。
- 数据范围沿用 apt-3（SELF 本人/SCOPE 授权片区/ALL），权限点 20–24 授予管家/片区管家/主管/管理员。

接口（均需登录）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/apartment/sign-archive/tasks` | 发起签约归档（秒回任务号） |
| GET | `/api/apartment/sign-archive/tasks?status=&contractNo=` | 任务列表（按数据范围过滤） |
| GET | `/api/apartment/sign-archive/tasks/{taskNo}` | 轮询进度详情 |
| POST | `/api/apartment/sign-archive/tasks/{taskNo}/cancel` | 取消（下个一致步骤停） |
| POST | `/api/apartment/sign-archive/tasks/{taskNo}/retry` | 失败/取消断点重跑 |
| GET | `/api/esign-demo/console` | 电子签章平台演示台（mock） |

配置见 `application.yml` 的 `evops.task.*` / `evops.esign.*`。**上线与回滚见 `docs/apt-5/上线说明.md`，验证记录见 `docs/apt-5/验证记录.md`。**

测试：`SignArchiveTaskFlowTest`（全流程 5% 步进、平台故障断点重跑不重复、等待/排队两路径取消停一致步骤、
取消后签署回调/调度不复活、敏感词拦截/打码、SELF/SCOPE 范围与 403、重复发起/401、宕机回收、14 天清理），
另配 `WaitCancelRaceTest`（等待步与取消并发的确定性竞态：就地 CANCELLED、进度只增不减）与
`TaskCancelConvergenceTest`（取消接口在 PENDING/RUNNING 翻转时有界收敛），全量 `mvn test` 全绿。

## 旺季备战：全链路 TraceId + 运维日志归集（apt-6）

Trace 代码位于 `com.evops.common.trace`，日志采集位于 `com.evops.common.opslog`，
检索入口位于 `com.evops.apartment.opslog`。老员工休假排障不断档，不用再在几十段日志里来回翻：

- **入口生成**：`TraceFilter` 最外层挂 `/api/**`，入站带合法 `X-Trace-Id` 则沿用（限 `[A-Za-z0-9-]{8,64}`，挡日志注入），否则服务端生成；401/403/异常分支也必有号。鉴权后账号自动进 MDC。
- **响应回带**：响应头 `X-Trace-Id` + 响应体 `traceId`（`ApiResponse` 固定字段）同值。
- **线程池传递**：`MdcTaskDecorator` 装配任务工作池；后台任务另把发起 TraceId 存 `t_async_task.trace_id`，
  工作线程认领后续绑（合同号/发起人一并绑定，finally 清理不串号）——
  同一合同「发起请求 → 调度认领 → 20 步」全部同号，等签署后换线程重领也不断。
- **按合同号聚合**：建档/退租/签约提交入口绑定合同号 MDC，关键动作打 INFO 日志，
  按合同号一次拉出一次请求（含后台任务）的全部日志。
- **固定格式 + 14 天滚动**（`logback-spring.xml`，字段不允许各环境私改）：
  `时间 级别 [应用,线程] traceId=.. contractNo=.. user=.. logger=.. - 消息`（无上下文输出 N/A）。
  文件按天 +100MB、gz 归档、保留 14 天；采集入库 `t_ops_log_entry` 每日 01:23 清 14 天前。
  采集口径：`com.evops` INFO+、框架 WARN+；有界队列 2000 + 单线程批量异步，满则丢新计数，不阻塞业务。
- **操作入口收进公寓楼菜单，权限只放给管家**：权限点 25（菜单）/26（检索）
  仅授管家与片区管家，主管/管理员菜单不可见、接口 403。检索台
  `/api/apartment/ops-log/console`（自包含 HTML，页内粘令牌），数据走鉴权接口。
  检索必带合同号或 TraceId、窗口强制 14 天、单次 ≤200 条；按合同号沿用 apt-3 数据范围
  （SELF 本人经办、SCOPE 授权片区），仅按 TraceId 须确有本人发起的行（防枚举）；逐查询落放行审计。
- 应急开关：`evops.trace.enabled=false`（停 TraceId 生成）、
  `evops.ops-log.enabled=false`（只停 DB 采集，文件日志照常）。
  **上线与回滚见 `docs/apt-6/上线说明.md`，验证记录见 `docs/apt-6/验证记录.md`。**

接口（除 console 空壳页外均需登录）：

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/apartment/ops-log/entries` | `apartment:opslog:view` | 按 contractNo/traceId（至少其一）+ level/from/to/limit 聚合检索 |
| GET | `/api/apartment/ops-log/console` | （空壳页） | 运维日志检索台 HTML，数据接口鉴权 |

测试：`TraceAndOpsLogFlowTest`（入口生成/入站透传/注入值拒绝、按合同号聚合同号、
工作线程续传跨线程同号、401/403/必带参数/SELF/SCOPE 范围、14 天清理、装饰器跨线程），
全量 `mvn test` **49 个用例全绿**。

## 出租率额度预占模型（apt-7）

公寓楼年检卡在出租率：出租率有额度控制，申请建档直接扣额度并发容易超扣。本期在
`com.evops.apartment.quota` 落地**预占模型**，额度按小区维度（与台账小区、SCOPE 授权口径一致）：

- **申请先占、确认实扣**：申请（`POST /quota/holds`）在余量内 `reserved+1` 预占 1 个名额，
  确认（`/holds/{holdNo}/confirm`）才把预占转 `occupied` 实扣；
  余量 `available = total - occupied - reserved`，`GET /quota/balances` 随时可查。
- **不超扣靠数据库**：申请是一条带 `WHERE total-occupied-reserved >= 1` 的**条件更新**，
  并发申请由行锁串行化，不足者更新 0 行业务失败（不是先查后写）；8 线程抢 3 个名额只成 3 笔。
- **取消或 20 分钟超时自动释放**：管家可主动取消；后台 fixed-delay 扫描到期 HELD 单
  自动释放回补余量。取消与超时、多实例扫描并发由 `WHERE status='HELD'` CAS 兜底，
  同一单只释放、只回补、只写一条流水。
- **余量变化全程可查**：预占/确认/取消释放/超时释放/配额调整五类变化逐笔落
  `t_quota_journal`（带变化后三余额），`GET /quota/journals?community=` 可查、可从 0 回放对账。
- **配额调整留痕**：`POST /quota/adjustments` 开户/上调/下调均落 ADJUST 流水，
  下调不得低于「已实扣 + 预占中」（不击穿在途单）；仅主管/管理员。种子：示例小区额度 300。
- **同单不可重复预占**：同一合同号同时只允许一笔活跃预占单（H2 生成列 `active_biz_ref`
  上的唯一索引兜底并发）；释放后可就同一合同重新申请（新单号，旧单留痕）。
- 数据范围沿用 apt-3（SELF 仅本人申请的单、SCOPE 授权小区、ALL），逐动作落放行审计；
  应急开关 `evops.quota.enabled=false`（停超时扫描，已占额度需人工取消）。

接口（均需登录，`/api/apartment/quota/**`）：

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/holds` | `apartment:quota:apply` | 申请预占（占 1 余量，返单号/到期时间） |
| POST | `/holds/{holdNo}/confirm` | `apartment:quota:confirm` | 确认实扣（过期/已释放/重复确认拒绝） |
| POST | `/holds/{holdNo}/cancel` | `apartment:quota:cancel` | 主动取消释放（与超时互斥，仅一次） |
| GET | `/holds` | `apartment:quota:view` | 预占单检索（community/status/bizRef） |
| GET | `/balances?community=` | `apartment:quota:view` | 配额/已实扣/预占中/余量 |
| GET | `/journals?community=` | `apartment:quota:view` | 额度流水（可按 changeType/bizRef 过滤） |
| POST | `/adjustments` | `apartment:quota:adjust` | 配额开户/调整（仅主管/管理员，留痕） |

测试：`QuotaHoldFlowTest`（并发不超卖、恰好占满、确认实扣、取消/超时释放与竞争、
过期拒绝确认、同单重复预占含并发、配额下调保护、流水回放对账、401/403/SELF/SCOPE），
全量 `mvn test` **64 个用例全绿**。**上线与回滚见 `docs/apt-7/上线说明.md`，
管家边界场景逐条结论见 `docs/apt-7/验证记录.md`。**

## 签约结果正式 Word 报告（apt-8）

上面要求签约结果出正式 Word 版报告。房源台账历史包袱不轻，本期**只从新数据理起**：
在 `com.evops.apartment.report` 以 **poi-tl 1.12.2** 渲染 `.docx`，口径基于新台账（REGISTER 流水）
按公寓楼归集房源、关联新签租约（`t_lease`），不回溯历史台账：

- **页眉带公寓楼名称**：模板页眉放 `{{buildingName}}`，poi-tl 默认渲染页眉标签。
- **租约明细循环表格行**：`LoopRowTableRenderPolicy`（onSameLine），一条租约复制一行；
  行内字段用方括号语法 `[contractNo]`、`[monthlyRent]` 等；租客姓名沿用 apt-2 脱敏。
- **租金合计自动汇总**：服务端对明细 `monthly_rent` 做 `BigDecimal` 求和；
  **明细为空时合计行为 0.00、不报错**（空列表仅移除数据行，合计 reduce 给零初值）。
- **落归档目录 + 登记版本，历史不覆盖**：文件只以 `CREATE_NEW` 落
  `归档根/小区/楼栋/签约结果报告_楼栋_vN_时间戳.docx`，同楼版本号 +1 登记
  `t_sign_report_version`；`CREATE_NEW` + `uk(building,version)` + `uk(file_path)` 三重防覆盖，
  并发撞号自动重算重试。
- **演示账号最小授权**：新角色 `report_demo` 仅授本期 3 个权限点（33–35）、数据范围 SCOPE=示例小区；
  账号 `demo01/demo123` 只挂该角色，访问台账/租约/签约等既有功能一律 403。每次启动幂等收权，
  防止被全量授权种子带权。主管/管理员附加报告权限；管家/片区管家本期不授。

接口（均需登录，`/api/apartment/sign-reports/**`）：

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/generate` | `apartment:sign-report:generate` | 按公寓楼（+可选小区/起租区间）生成，落盘并登记版本 |
| GET | `` | `apartment:sign-report:view` | 版本列表（buildingName 可选，按数据范围过滤） |
| GET | `/{id}` | `apartment:sign-report:view` | 版本详情（范围放行） |
| GET | `/{id}/download` | `apartment:sign-report:view` | 下载该版归档 docx（历史文件不可变） |

数据范围沿用 apt-3（SELF 仅本人、SCOPE 授权小区、ALL），逐动作落放行审计；归档根
`evops.sign-report.archive-dir`（默认 `archive/sign-reports`，已 gitignore）。

测试：`SignReportFlowTest`（页眉/循环行/合计、版本 +1 与历史不覆盖、空明细为零、
demo01 仅报告范围权限与 403/401、SCOPE 跨片区 403 与列表隔离），
全量 `mvn test` **68 个用例全绿**。**上线与回滚见 `docs/apt-8/上线说明.md`，
逐条验收结论见 `docs/apt-8/验证记录.md`。**

## 租约签署断点回调 + 平台回调安全规矩（apt-9）

签约归档第 12 步「等待租客签署」原先只靠 5 秒轮询，签署完成到续跑之间有断点。本期由
**电子签章平台在租客签署完成后主动回调** EVOPS 即时唤醒任务（轮询保留兜底，回调丢失也能成功），
代码位于 `com.evops.apartment.esign.web`。平台是外部系统、没有人工账号，管家要求
「规矩定进系统、不靠自觉」，入口过滤器强制三条安全要求，缺一条 401：

- **请求带签名与随机号，服务端按同样密钥验签**：头 `X-EV-Key/X-EV-Timestamp/X-EV-Nonce/X-EV-Signature`，
  与出站同把密钥、同算法（`Base64(HMAC_SHA256(secret, METHOD\nPATH\ntimestamp\nnonce\nmd5(body)))`，
  统一在 `ESignSignature`）；**签名覆盖报文全部参数含空值**——对平台原始报文整体 MD5，
  字段缺省/空值改非空即验签不过；定长比较，密钥未配置 fail-closed。
- **超 8 分钟的请求拒绝**：时间戳取**服务端时钟**比对（默认容差 480 秒），
  过早（截获旧包）/过晚（时钟漂移）都拒。
- **同一 nonce 15 分钟内只许出现一次**：验签通过后抢占写 `t_esign_nonce`，
  唯一键冲突即重放；多实例由数据库唯一约束兜底，到期记录定时清理。

回调 `POST /api/esign/callback/sign`（机器对机器，不挂登录权限，Shiro 放行到验签过滤器）：
按 `bizNo=EVOPS-合同号` 定位进行中任务，条件更新把停在第 12 步的 PENDING 任务
`next_run_at` 提到当前、下轮派发立即续跑；任务 RUNNING/已越过等待步则 0 行不回退，
重复回调业务幂等，不产生重复租约/归档。

配置见 `application.yml` 的 `evops.esign.callback.*`（默认强制开启；
`enabled=false` 仅限紧急排障）。**发布安排在公寓楼结账之后、预留半小时窗口，
上线与回滚见 `docs/apt-9/上线说明.md`，验证结论见 `docs/apt-9/验证记录.md`。**

测试：`ESignCallbackSecurityFlowTest`（合法回调即时唤醒至 SUCCESS、超 8 分钟/错签/缺头拒绝、
同 nonce 重放拒绝、不同 nonce 幂等、空值参与签名、未知 bizNo 400），
全量 `mvn test` **76 个用例全绿**（apt-1～8 原 68 个无回归）。

## 房源归属树闭包表（apt-10）

区域总新功能：管家自己记了本流水账，对账全靠房源归属树。本期把房源归属关系改成**闭包表（Closure Table）**
模型，代码位于 `com.evops.apartment.tree`，子树、祖先链、同级排序全部走表结构，不再现场递归拼树：

- **两张表**：`t_property_node`（节点：直接父 `parent_id`、同级 `sort_order`、冗余 `community` 供数据范围过滤）
  + `t_property_closure`（每对祖-后一行，`distance=0` 为自身行，主键 `(ancestor_id, descendant_id)`）。
- **查询走表结构**：子树 = `ancestor_id` 等值联表一次取全（按层、同父 `sort_order` 排）；
  祖先链 = `descendant_id` 等值 `ORDER BY distance DESC`，恒为「根 → … → 自己」的正确顺序；
  同级 = `parent_id` 等值按 `sort_order` 升序。
- **拖拽换父子孙自动跟随**：同事务内「改直接父 → 删旧路径（子树内部边保留）→ 接新路径
  （新父祖先 × 被拖子树后代笛卡尔接边）→ 整子树 `community` 跟随」，子孙的祖先链与小区归属整体迁移，
  无需递归重建；也支持把节点拖为根。
- **环状引用直接拒绝**：换父前查闭包表，新父是被拖节点自身或其后代即拒（挂到子孙下被拒绝，验收必查），
  结构上不可能成环；被拒绝时事务回滚，闭包边与节点归属保持不变。
- **维护日志按合同号可查**：建节点/换父/排序请求可带 `contractNo`，动作 INFO 日志绑该合同号入 MDC，
  在 apt-6 运维日志菜单按合同号一次拉出该合同相关的全部归属变更（验收必查项）；逐动作落数据放行审计。
- 数据范围沿用 apt-3：SELF 仅本人经办节点，SCOPE 按节点冗余 `community` 过滤，ALL 全部；
  权限点 36（菜单）/37（维护）/38（查询）授管家/片区管家/主管/管理员，apt-8 的 demo01 演示角色不授。

接口（均需登录，`/api/apartment/property-tree/**`）：

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| POST | `/nodes` | `apartment:property-tree:manage` | 新增节点（parentId 空=建根，须给 community；子节点继承父小区） |
| POST | `/nodes/move` | `apartment:property-tree:manage` | 拖拽换父（newParentId 空=挪为根；挂自身/子孙拒绝） |
| POST | `/nodes/sort` | `apartment:property-tree:manage` | 同级排序（只改 sort_order，不动归属） |
| GET | `/nodes?parentId=` | `apartment:property-tree:view` | 同级节点（不传 parentId 返回根列表） |
| GET | `/nodes/{rootId}/subtree` | `apartment:property-tree:view` | 子树（含根，按层/同级次序） |
| GET | `/nodes/{nodeId}/ancestors` | `apartment:property-tree:view` | 祖先链（含自身，根 → 自己） |

测试：`PropertyClosureTreeFlowTest`（祖先链根→己顺序与 distance 递减、子树取全且只取子集、
同级排序与排序接口、换父子孙闭包边+小区整体跟随、挪为根、挂自身/子孙拒绝且结构不变、
**日志按合同号可查**、401/403/SCOPE 跨片区/demo01 最小授权、schema 重跑幂等），
全量 `mvn test` **85 个用例全绿**（apt-1～9 原 76 个无回归）。
**上线与回滚见 `docs/apt-10/上线说明.md`，逐条验收结论见 `docs/apt-10/验证记录.md`。**




## 租约列表拼音检索（apt-11）

总部巡检点名项。代码位于 `com.evops.apartment.pinyin`，检索入口沿用租约列表：

- **保存时同事务生成冗余字段**：租约建档（接口与签约归档后台任务两个写入点）在同一事务内
  生成租客姓名的**全拼**与**首字母**两个冗余列（`tenant_name_py_full` /
  `tenant_name_py_initials`），失败整体回滚，不会出现“租约落了、拼音没生成”。
- **多音字取多读音集合**：每个汉字取全部读音（pinyin4j，无声调小写）做笛卡尔积——
  乐 le/yue、查 cha/zha、单 dan/chan/shan、解 jie/xie 等姓氏任一读法都能命中，
  不靠姓氏词库猜。字段形如 `|lefei|le fei|yuefei|yue fei|`（变体以 `|` 包裹）。
- **检索三路匹配**：列表新增可选参数 `keyword`，同时匹配中文原文、全拼冗余、首字母冗余
  （OR）；拼音按**变体起点前缀**匹配（`%|前缀%`），音节中间子串不误命中。
- **大小写全半角容错**：检索词与索引同口径归一化（逐字 NFKC 全角→半角 + 小写），
  `LEFEI`、`ｌｅｆｅｉ`、全角空格分隔音节均可命中。
- **历史数据启动回填**：旧文件库升级后两列为 NULL，`PinyinIndexBackfillRunner` 启动时
  按同口径分批补齐（每批独立事务，失败下轮续跑）。
- **验收打印页**：`GET /api/apartment/leases/acceptance/console` 自包含页面——播种多音字
  样例租约（幂等）→ 服务端按线上同口径逐条执行验收用例 → 全部通过后浏览器打印，
  纸面签字确认（验收人/复核人/日期签字栏，打印时自动隐藏操作区）。

接口（均需登录；复用既有权限点，不新增）：

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/apartment/leases?keyword=` | `apartment:lease:view` | 列表检索新增 keyword（中文/全拼/首字母） |
| GET | `/api/apartment/leases/export?keyword=` | `apartment:lease:export` | 导出与检索同口径 |
| POST | `/api/apartment/leases/acceptance/samples` | `apartment:lease:create` | 幂等播种验收样例租约 |
| GET | `/api/apartment/leases/acceptance/data` | `apartment:lease:view` | 样例 + 验收用例执行结果 |
| GET | `/api/apartment/leases/acceptance/console` | （空壳页，数据接口鉴权） | 验收打印页 |

拼音冗余字段仅供库内检索，不随接口/导出输出（`@JsonIgnore`，与姓名脱敏口径一致）。

测试：`PinyinSearchFlowTest`（同事务生成与多音字集合、三路匹配、变体起点不误命中、
大小写/全半角容错、历史回填、组合条件与 SELF 数据范围、导出同口径、验收用例全过、
打印页签字栏、401 鉴权），全量 `mvn test` **97 个用例全绿**（apt-1～10 原 85 个无回归）。
**上线与回滚见 `docs/apt-11/上线说明.md`，逐条验收结论见
`docs/apt-11/验证记录.md`。**

## 紧急联系人嵌套对象 + 嵌套递归脱敏修复（apt-12）

安全抽查发现：导出的租约文件里证件号（扁平字段）打码了，同表 `emergency_contact`
嵌套对象里的紧急联系人电话却是明文。根因：脱敏框架只处理实体上的扁平 String 字段，
不递归嵌套对象；日志出口同样未走规则。本期修复：

- **嵌套对象递归脱敏**：`SensitiveFields` 对嵌套 bean / Map / 集合 / 数组深度遍历，
  凡 `@Sensitive` 字段一律按 `SensitiveRuleRegistry` 同规则脱敏（规则仍是
  `application.yml` 默认底 + `config/sensitive-rules.yml` 热加载，嵌套层级同一份）。
  导出单元格渲染为脱敏后 JSON；`@JsonIgnore` 字段不输出，循环引用截断防死循环。
- **日志输出同规则**：新增 `SensitiveFields.maskValue`（单值）/ `toMaskedJson`（整对象），
  签约归档步骤、电签 mock、拼音索引 5 处明文姓名日志改走同一套规则；
  约定日志打印敏感数据一律经此入口。
- **紧急联系人**：`t_lease.emergency_contact`（JSON 列，库内明文，脱敏只在出口）；
  建档接口与租约导出（末列「紧急联系人」）同步支持；接口 JSON 嵌套成员自动脱敏。

测试：`SensitiveNestedMaskingTest`（导出/接口/库内/日志四出口、嵌套形状单测、
APT8签约楼同批租约回归），全量 `mvn test` **102 个用例全绿**（apt-1～11 原 97 个无回归）。
**缺陷确认与修复前后对照见 `docs/apt-12/验证记录.md`（证据在 `docs/apt-12/evidence/`），
上线与回滚见 `docs/apt-12/上线说明.md`。**

## 双角色菜单变少工单排查 + 并集口径固化（apt-13）

工单称「兼主管+管家双角色账号菜单比单角色少，权限按交集计算」。经源码/字节码/
文件库数据/真实服务进程四层核实：**不属实**——当前工作区为并集且默认拒绝正确
（combo01 实测 10 菜单 ⊋ 单角色 9，管家独占 `ops-log` 与主管独占 `sign-report`
菜单同时在列）。主代码零改动，本期只做口径固化：

- **并集守卫用例**：新增 `RbacDualRoleUnionTest`——双角色必须同时看到两个角色的
  独占菜单、菜单数严格多于任一单角色、双方都未授的权限点不进并集（默认拒绝）、
  匿名/越权 401/403。任何人把并集改成交集，该测试类立即失败。
- **附带发现**：文件库 `supervisor` 角色多授 `apartment:sensitive:manage`（id=18，
  口径应仅管理员），为历史种子只增不删造成的漂移；处置 SQL 见
  `docs/apt-13/验证记录.md` 第 5 节（需会签，未擅自变更）。

测试：全量 `mvn test` **104 个用例全绿**（apt-1～12 原 102 个无回归）。
**排查步骤与证据见 `docs/apt-13/验证记录.md`（证据在 `docs/apt-13/evidence/`），
上线与回滚见 `docs/apt-13/上线说明.md`。**

