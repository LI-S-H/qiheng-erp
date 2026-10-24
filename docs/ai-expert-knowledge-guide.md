# ERP 专家操作手册：拿到业务问题后怎么做

版本：2.1；核对日期：2026-10-24。对应运行时目录：`erp-server/erp-ai/src/main/resources/proposal/`。

这八份文件用来带着方案节点做事：接到问题后先走哪一步、安排什么证据、根据结果走哪条分支、什么时候应停下来确认，以及最后交付什么。规则编号便于定位，不要求读者先记住缩写再理解业务。

先从下面的自然问法选文件，打开后按入口路线走。想看一次完整过程，可直接看[补货手册](../erp-server/erp-ai/src/main/resources/proposal/replenishment.md)的贯穿案例：它会算出“第11天先缺10件”，再解释为什么第13天买到25件仍然没有解决问题。不要依次执行所有文件。

## 先照着走一次：用户说“帮我安排未来两周补货”

以下全部是假设，展示如何工作，不是已查询的经营结果。

1. **先明确这次要做到哪天、最后留多少。** 用户已经确认14天、期末留20件，就沿用；如果没有，先确认影响补货量的期间和目标，不默认替用户填好。进入补货手册 REP-002。
2. **先复用现有库存，再安排缺的证据。** 假设已有账面100、锁定30，得到未占用70。还需每日新增需求和未收采购，不要只拿库存与安全线就宣布未来够用。尚未执行时写进清单，不假称已取得结果。
3. **把已锁订单与预测逐天对齐。** 假设每天总预测10件；前6天各5件已锁订单，后8天无重叠订单。得到前6天新增各5件、后8天各10件，合计110件。没有需求输入才去需求手册，不因“补货”二字就强制跑所有模型。
4. **再把已有采购放到真正能到的那一天。** 假设40件第12天才到；REP-003要求日表。计算结果是第10天余额0，第11天缺10件，第12天到货后余额20，第14天余额0。此时知道早期缺口与期末目标是两个问题。
5. **先求缺多少，再按候选的下单条件改数量。** REP-004算总量还需20件；采购手册检查到起订25件，单价35.20元，因此候选采购25件、货值880元，多补5件。不要继续用20件计价。
6. **把候选到货再放回日表。** 第13天才到→第11天仍缺10件，不能推荐成已解缺；第11天销售前可到→在本例条件下可覆盖。没有这个承诺，就请采购方确认能提前多少、何时到、加急条件；不能用历史均值充当本次承诺。
7. **把选择交给用户，把补证工作交给执行链。** 交付“常规采购来晚”“提前到货的条件候选”“如考虑别仓还需核物流”等具体方案与下一项确认；不伪装已经下单。选中后由方案子图派生相应 D 段工作，已有证据继续引用。
8. **审核节点只检查计划有没有把这些工作安排进去。** 日表计算尚未执行，不是漏项；清单只算期末总量却要保证按时补货，才应指出缺少逐时点核验。通用手册 COM-005 给出具体清单与反馈示例。

每份文件都采用这样的过程，并写出缺数时怎样继续。对于“现在离安全线差多少”，最短路线只有 REP-001：假设账面10、锁定2、安全线20，交可用8、差额12就结束；不会强迫用户走完上面八步。

**怎样读每一段**：先看“现在做什么”，按编号操作；再看判断分支，跳到指定下一节；最后核对“本步留下”的表或待办。已经完成的步骤用原证据替代，不重做。公式只在需要计算的步骤出现，不把它们当必背目录。

**怎样区分两种使用者**：方案节点用流程规划取证，取得结果后作分支选择；审核节点用同一流程核对清单是否足以支持声称的结论，不亲自查询、计算或选择方案。必要可行性取证仍按原图阶段安排，本文不另加工具门禁。

本轮进一步明确了：快照之后的剩余需求；规划负余额与真实库存的区别；期末净量与单批救急量；足量下限与预算上限；总活动销量与新增可售量；达到持平与严格增利。每条公式旁给变量、单位、前提和例外。相关歧义及复算见[2.1复核记录](../_logs/task-review-20261024-expert-clarity.md)。

## 1. 八份文件与规则目录

| 正式文件 | 规则 | 跟着文件会做什么 |
|---|---|---|
| [common.md](../erp-server/erp-ai/src/main/resources/proposal/common.md) | COM-001～005 | 选一条最短路线→统一输入→补证清单→核共享量→交接与审核 |
| [demand.md](../erp-server/erp-ai/src/main/resources/proposal/demand.md) | DEM-001～005 | 整理日记录→给基线→按时间回测选方法→去重订单→交补货；策略优化另走 |
| [replenishment.md](../erp-server/erp-ai/src/main/resources/proposal/replenishment.md) | REP-001～004 | 当前差额直接交付；未来补货则列需求→排到货→找首次缺口→重验候选 |
| [purchase.md](../erp-server/erp-ai/src/main/resources/proposal/purchase.md) | PUR-001～004 | 接外购剩余缺口→按各家起订量调整→核价预算→排批次→交推荐条件 |
| [supplier_selection.md](../erp-server/erp-ai/src/main/resources/proposal/supplier_selection.md) | SUP-001～004 | 列候选→分清违反硬条件与未知→解释历史分项→核本次承诺→选主候选和替补 |
| [transfer.md](../erp-server/erp-ai/src/main/resources/proposal/transfer.md) | TRF-001～003 | 核源仓能释放多少→分配共享量→查能否及时运到→交负责人确认清单 |
| [promotion.md](../erp-server/erp-ai/src/main/resources/proposal/promotion.md) | PRM-001～003 | 先选品/增利/复盘入口→缺成本先给可算部分→参数齐才比较→核供给→定复核 |
| [clearance.md](../erp-server/erp-ai/src/main/resources/proposal/clearance.md) | CLR-001～003 | 用事实筛候选→圈不能动的量→逐动作保留或排除→交落实与复核任务 |

每步都明确动作、判断、产物与下一步；贯穿案例展示如何从原始问题走到交付。编号仅用于跳转和定位审核问题，不新增 API 字段。此前[样稿](ai-expert-knowledge-sample.md)保留为作者背景，实际操作以本版手册为准。

## 2. 用户场景怎样组合知识

下面是方案子图后续选择知识的建议映射。当前加载器仍全量拼接，并未实现按此表自动检索。

| 用户场景 | 主要知识 | 必须明确的边界 |
|---|---|---|
| “当前库存够不够，离安全线差多少” | COM + REP-001 | 当前差额不等于未来采购量；不强制预测 |
| “未来两周补多少，何时会断供” | REP-002/003/004；缺需求输入时才转 DEM | 预测是否包含订单、逐桶消耗、逐时点到货 |
| “应该向谁买，买多少” | 上述 + PUR + SUP | MOQ 影响货值与余量；报价、评分不保证供货 |
| “采购延期了怎么办” | COM + REP-003/004 + PUR-003 + SUP | 保留未收敞口，重新确认到货；按首个缺口比较应急动作 |
| “能不能先从其他仓调货” | COM + DEM（涉及未来时）+ TRF + REP | 调出仓也要保护；无运输确认不能承诺及时支援 |
| “哪些产品适合做促销” | COM + CLR-001 + PRM | 低周转只是候选，不等于可预测降价效果 |
| “降价后要多卖多少才值得” | COM + DEM（正常基线）+ PRM | 成本与费用来源；增量贡献不叫实际净利润 |
| “积压库存怎么去化” | CLR；需要估未来需求才转 DEM，按选中动作增加 TRF/PRM | 无批次库龄；暂停采购不取消已有合同；退供需确认 |
| “安全库存或采购批量怎么优化” | COM + DEM-005 + PUR-004 | 服务目标、真实费用、模型假设和样本缺一不可 |

订单履约、退货质量、客户信用等可在后续扩展为独立知识。本批只纳入它们对现有八类决策的必要影响，未宣称已经穷尽 ERP 的所有场景。

## 3. 需要查公式时再看这一节

默认使用真实业务数量、元、自然日。先沿操作路线知道自己要算什么，再在此查公式；不要按这张表依次运行所有模型。参数来源、实际输入与限制随计算保留。

| 方法 | 公式或计算顺序 | 位置与关键限制 |
|---|---|---|
| 金额换算 | 金额（分）=Q100×Pcent/100，再舍入 | COM-002；不是两个原始整数直接相乘 |
| 当前安全线差额 | max(0,S−A)，A=H−R | REP-001；S 产品级；A=S 时差额为零 |
| 日均实际出库 | Σx_t/N | DEM-001；N 包含可信观察期的真零日，缺采不补零 |
| SES | ℓ_t=αx_t+(1−α)ℓ_(t−1)；未来预测为最新水平 | DEM-002；α 要拟合验证，不适合明显趋势季节序列 |
| 回测误差 | MAE=Σ|e|/n；RMSE=√(Σe²/n) | DEM-003；时间滚动、相同步长，零需求不用 MAPE |
| 预测消耗 | Dnew_t=max(0,F_t−C_t)，Dnew_h=ΣDnew_t | DEM-004、REP-002；先逐桶截断，已锁订单只用一次 |
| 快照后的剩余总预测 | F_rem=max(0,F_day−X_done) | COM-003；只有全天预测才扣已实现出库，再匹配剩余锁单 |
| 逐时点余额 | PAB_free(t)=A+ΣI(≤t)−ΣDnew(≤t) | REP-003；可用量已扣锁定，到货不能提前使用 |
| 期末净补量 | max(0,Dnew_h+S_end−A−I_h) | REP-004；为0仍可能要救急，按拟到货日核定动作批量 |
| 单批按时供应量 | q_min(τ)=max(0,max_{τ≤t≤h}[−B0(t)],S_end−B0(h)) | REP-004；其它供需固定、无期间额外保护线；τ前缺口本批救不了 |
| 起订与数量步长 | 已分配外购量Q_buy>0时 ceil(max(Q_buy,MOQ)/u)×u | PUR-001；先核时点救急，再起订；MOQ 下限不等于整包倍数 |
| 包装倍数 | ceil(max(Q_buy,MOQ)/b)×b | PUR-001；b>0且b/u为整数才适用，26缺口/25MOQ/12件箱→36件 |
| 采购货值 | ΣQ_order×p | PUR-002；不含未知运费税费，不能称全部成本 |
| 固定费用条件下预算检验 | round_cent(Q_order×p)+E≤B；保守估计Q_cap_est=s×floor((B−E)/(p×s)) | PUR-002；单品固定价、费用齐全；边界按实际到分舍入，不直接用估计淘汰 |
| 经济订货量 | Q*=√(2DK/h) | PUR-004；D 年需求、K 次成本、h 单位年持有费 |
| 统计安全库存/补货点 | SS=zσ_d√L；ROP=μ_dL+SS | DEM-005；固定提前期、独立稳定需求、周期服务目标 |
| 现有供应商评分 | 价格30%+交付30%+质量30%+服务10% | SUP-002；缺分项不当零、不重新分权重 |
| 历史平均交期 | Σ(金额×完成周期)/Σ金额 | SUP-003；完成样本、触发式更新，不是交期承诺 |
| 调出余量 | max(0,A+I_h−Dnew_h−S_end) | TRF-001；不等于当前可发上限，还需逐时点保护 |
| 单次调出账面保护上界 | Cap_book(τ)=u×floor(min_{t∈K(τ)}[B(t)−M(t)]/u) | TRF-001；先判原源仓全期保护成立，再核实际可释放实物 |
| 活动增量贡献 | ΔC=q1m1−q0m0−F | PRM-001；m=p−c−v，正常基线是估计反事实 |
| 达到正常贡献的活动销量 | q1_BE=(q0m0+F)/m1 | PRM-001；正分母等条件成立，按数量精度上取整 |
| 严格增利销量 | u×(floor((q0m0+F)/(m1×u))+1) | PRM-001；148件仅持平、149件才严格增利，前提见正文 |
| 活动总销量与新增供给 | q1≥C且q1−C≤B_new | PRM-002；已预占活动销量C与q1同范围，其它订单单列保护 |
| 用户毛利底线 | p≥c/(1−g_min) | PRM-002；需定义与来源，不含全部活动费用 |
| 静态覆盖天数 | DOC=A/d_bar | CLR-001；d_bar>0，不是当前批次库龄 |
| 去化量候选 | max(0,A+I_h−Dnew_h−S_end) | CLR-002；不是今天能出清的量 |
| 持有费用情景 | Q_avg×h_day×n | CLR-003；费用、平均库存路径由用户或证据提供 |

### 可复算的教学链路

这些数字均是假设，未读取真实经营数据：

1. H=100、R=30，所以 A=70。各桶已经核验且 C_t≤F_t，全期预测140、同桶重叠锁定订单合计30，故新增需求合计110。
2. 可信按时到货40、期末目标20，总量候选为110+20−70−40=20；MOQ25、精度0后采购25，35.20元单价对应880.00元货值，多补5件。
3. 两桶 F=(10,10)、C=(20,0) 时新增为(0,10)，合计10。若先汇总截断会错成0；这也是为什么第1步必须限定逐桶核验。
4. 促销正常单价100、活动90、商品成本60、变动费5、正常销量100、固定活动费200：至少148件达到正常贡献；销量120/150/180时增量贡献为−700/50/800元。这不是已实现利润或销量预测。

## 4. ERP 数据能力与事实依据

`knowledge/` 解释业务记账与字段，`proposal/` 解释决策所需的取舍与审核证据。固定字段名仅用于明确知识的来源；planner 与需求审阅仍应按现有提示词输出业务口径，SQL 表字段、JOIN 和过滤实现由取数子图决定，不把专家知识整段复制进 `instruction`。

| 来源短 ID | 本地依据 | 本批核实的事实 |
|---|---|---|
| ERP-PRODUCT | [产品表设计](database/mvp-product-schema.md)、[数据库约定](database/database-design-conventions.md) | safety_stock_qty 产品级；数量精度；参考价 DECIMAL 元 |
| ERP-WHS | [库存服务](../erp-server/erp-warehouse/src/main/java/com/qiheng/erp/warehouse/service/impl/WarehouseStockServiceImpl.java)、[入库服务](../erp-server/erp-warehouse/src/main/java/com/qiheng/erp/warehouse/service/impl/InboundBillServiceImpl.java)、[出库服务](../erp-server/erp-warehouse/src/main/java/com/qiheng/erp/warehouse/service/impl/OutboundBillServiceImpl.java)、[仓储表设计](database/mvp-warehouse-schema.md) | A=H−R；确认才改库存；全量入库并记录质检，无当前质量分桶；无独立调拨链路 |
| ERP-SALES | [销售服务](../erp-server/erp-sales/src/main/java/com/qiheng/erp/sales/service/impl/SalesOrderServiceImpl.java)、[销售表设计](database/mvp-sales-schema.md) | 提交即锁库；未履约=quantity−outbound_qty；明细 locked_qty 不是当前剩余锁量 |
| ERP-PUR | [采购服务](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/PurchaseOrderServiceImpl.java)、[采购表设计](database/mvp-purchase-schema.md) | 采购敞口、分批回写、金额、历史交期；报价/MOQ 并非当前所有接口的硬校验 |
| ERP-PUR（评分补充） | [价格](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/scoring/PriceScoreCalculator.java)、[质量](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/scoring/QualityScoreCalculator.java)、[交付](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/scoring/DeliveryScoreCalculator.java)、[综合](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/scoring/AggregateScoreCalculator.java)、[重算服务](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/SupplierScoreRecalculateServiceImpl.java) | 金额质量比、交付逾期处罚、固定权重与 NULL 边界；不是另造一套优选算法 |
| ERP-RETURN | [退货服务](../erp-server/erp-return/src/main/java/com/qiheng/erp/returnorder/service/impl/ReturnOrderServiceImpl.java)、[退货表设计](database/mvp-return-schema.md) | 来源可退量与退供流程存在，不证明供应商必接受或费用退款已闭环 |
| ERP-AI | [加载器](../erp-server/erp-ai/src/main/java/com/qiheng/erp/ai/graph/support/ProposalPlaybook.java)、[审核节点](../erp-server/erp-ai/src/main/java/com/qiheng/erp/ai/graph/node/NeedReviewNode.java)、[审核提示](../erp-server/erp-ai/src/main/resources/prompts/need_review.md)、[图配置](../erp-server/erp-ai/src/main/java/com/qiheng/erp/ai/config/GraphConfiguration.java) | 构造时全量加载；业务完整性审核；方案子图及执行后效果不因知识落盘自动实现 |

权限与实际 Schema 以运行时为准，不能把以上资料当额外授权。当前不具备的数据包括：完整实际销货成本、库存成本结转、活动费用账与对照实验、实时供应产能、仓间运输时效费用、批次库龄/到期日、当前合格与不合格库存余额。知识均给出相应的情景或待确认边界。

本批同时修正文档中的必要冲突：`knowledge/shared.md` 的放大数乘除量纲；`knowledge/purchase.md` 的原始整数金额与完成率换算、未来窗口供应衔接和已锁订单重复计量。未改 Java 业务实现、图拓扑、数据库、接口或前端。

## 5. 专业资料与改写范围

外部资料用来支持方法及假设，本 ERP 的公式应用、组合约束和字段语义由项目证据限定。不是把其他 ERP 的功能、默认参数或接口行为照搬过来。

| 短 ID | 一手资料与支持范围 | 对应规则 |
|---|---|---|
| FPP | Hyndman、Athanasopoulos 的 [Forecasting: Principles and Practice，预测工具](https://otexts.com/fpp3/toolbox.html)及[指数平滑](https://otexts.com/fpp3/exponential-smoothing.html)：基线、时间回测、误差与 SES 的适用条件 | DEM-001～003 |
| APICS-SS | Peter L. King，APICS Magazine，[Understanding safety stock and mastering its equations](https://web.mit.edu/2.810/www/files/readings/King_SafetyStock.pdf)，2011，MIT 托管：需求变动、安全库存及周期服务水平；不是 fill rate 保证 | DEM-005 |
| MIT-INV | MIT OCW，[15.772J Inventory Management](https://ocw.mit.edu/courses/15-772j-d-lab-supply-chains-fall-2014/b0b41cc09a83ffa4cf1335ac65f5f9ab_MIT15_772JF14_Lec13.pdf)：订货成本、持有成本、EOQ 与确定性模型假设 | PUR-004；DEM-005 订货策略背景 |
| ORACLE-INV | Oracle Inventory，[Planning and Replenishment](https://docs.oracle.com/cd/E26401_01/doc.122/e48820/T291651T292324.htm)：供需净算、补货点与订单修正条件；不是本 ERP 已实现 Min-Max 引擎 | DEM-005、PUR-001/004 |
| MS-PLAN | Microsoft Business Central，[Handling Reordering Policies](https://learn.microsoft.com/en-us/dynamics365/business-central/design-details-handling-reordering-policies)：按时间处理需求和收货、区别补货政策；逐桶消耗的具体适用方式见本项目 DEM-004 | COM-004、REP、PUR-003、SUP-004、TRF-001 |
| OPENSTAX-CM | OpenStax，[Contribution Margin](https://openstax.org/books/principles-managerial-accounting/pages/3-1-explain-contribution-margin-and-calculate-contribution-margin-per-unit-contribution-margin-ratio-and-total-contribution-margin)：贡献、固定/变动费及相关范围 | PRM-001/002、CLR-003 |
| OPENSTAX-CVP | OpenStax，[Break-Even Point](https://openstax.org/books/principles-managerial-accounting/pages/3-2-calculate-a-break-even-point-in-units-and-dollars)：成本—销量—贡献关系；PRM-001 的“达到正常贡献销量”由两种情景相等推导，并非原文现成 ERP 公式 | PRM-001 |

场景组织另参考 Oracle 的[促销与降价优化产品资料](https://docs.oracle.com/en/industries/retail/promotion-markdown-offer-optimization-cloud/22.2.401.0/)，仅借鉴去化、收入和贡献等目标区分，不声称采用其模型或具备该产品数据能力。

## 6. 加载、上下文开销与维护

现有 `ProposalPlaybook` 在构造时按文件名排序读取 `classpath*:proposal/*.md`。清洗会去掉文件一级标题与标题前说明行，把二级标题转成文本段落；场景入口均位于正文，标题剥除后仍能选路线。只有占位内容的小节仍自动跳过。目录中不要再放 README、编写指南或参考资料清单，否则也会被注入。

`need_review` 在专家知识非空时全量附加，普通 DATA 审核也会收到。入口限制采用范围，并没有自动节省输入长度。本版增加实际操作过程与案例，比初稿更长；精确字节与清洗后字符统计见本版验证记录，还未计其它提示词、业务知识与 Schema。未来按所选场景读取仍需实际接线，本批不改加载策略。字符数不是模型 token 数，应以目标模型 tokenizer/调用 usage 为准，不用粗略换算保证低开销。

文件落盘与打包不等于当前 JVM 已读取。现有实例无热更新；部署时需要重建资源并重建 `ProposalPlaybook` 实例，通常随服务重启。本批不自动重启用户正在使用的服务。

维护规则：

1. 新规则沿用现有编号，已有编号保持含义稳定；删除或改模型假设需记录迁移，避免同一编号指向不同结论。
2. 业务事实先对源码、表设计、授权 Schema，专业方法查一手资料；冲突需在知识中直接修正，不能只在指南写“以新文件为准”。
3. 每步必须说清从哪里开始、具体做什么、按结果走哪里、缺数如何补、留下什么。公式嵌在计算步骤里，并保留量纲、参数来源与可复算案例；不能给旧术语清单加上“第一步”就算完成。
4. 企业阈值、比例、费用与承诺不得由作者发明；例子明确是假设，未知参数允许情景分析，不伪造事实。
5. 新文件或规则改动后，更新加载契约测试与本索引，检查交叉引用、清洗结果、教学计算和实际注入开销。
6. 同名废弃文件如果只删了源码、仍留在 `target/classes`，普通增量复制不会移除。发布前使用干净构建，避免旧知识重新打包。

## 7. 验证范围

本版首先按业务场景走查：只查当前差额能否及时停止；晚到货能否定位早期缺口；缺成本促销能否给出可算结果与补证任务；缺物流调拨能否明确停止承诺及下一负责人。每例记录入口、已有/待取证据、分支、交付及停止点，检查是否还有“不知道下一步做什么”的断点。

再检查八份真实 classpath 资源、31 个唯一规则、清洗后操作正文保留、占位跳过行为、完整知识传入审核 Prompt，以及模拟通过后的状态消费；脚本复算教学数字、引用和打包一致性。过程记录见[操作手册改写验收记录](../_logs/task-review-20261024-expert-workflows.md)；[首版生成记录](../_logs/task-review-20261024-expert-knowledge.md)仅保留历史，不代表用户已认可其指导性。

这些检查证明知识可加载、注入与文档内容一致，不证明真实模型一定正确遵守，也不证明已经取数、生成可执行方案或实现经营收益。真实模型场景评测应另覆盖：当前查询不误加预测、缺数不伪造、重叠订单不双算、到货过晚不报解决、促销不推因果，以及错误能否定位到必需证据。
