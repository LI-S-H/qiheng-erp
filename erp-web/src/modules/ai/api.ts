import { getResult, http } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import type { Result } from '@/shared/types/api';
import type {
  AiActionCard,
  AiAgentTrace,
  AiAssistantOverview,
  AiChartSpec,
  AiChatMessage,
  AiChatRequest,
  AiChatResponse,
  AiContextSource,
  AiConversationSummary,
  AiConversationUpdateRequest,
  AiPromptField,
  AiQuickPrompt,
  AiScheduledTask,
  AiScheduledTaskOutputFormat,
  AiScheduledTaskPage,
  AiScheduledTaskStatus,
  AiScheduledTaskSummary,
  AiScheduledTaskTemplate,
  AiScheduledTaskUpdateRequest,
  AiTaskCard,
  AiTaskExecution,
  AiTaskExecutionAction,
  AiTaskExecutionMetric,
  AiTaskExecutionSection,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

let mockConversationSeq = 4;
let mockTaskSeq = 5;

const agentNameMap: Record<string, string> = {
  'chief-router-agent': '主控智能体',
  'data-analysis-agent': '数据分析智能体',
  'purchase-advice-agent': '采购建议智能体',
  'inventory-analysis-agent': '库存分析智能体',
  'sales-forecast-agent': '销量预测智能体',
  'supplier-evaluation-agent': '供应商评估智能体',
};

let mockConversations: AiConversationSummary[] = [
  { conversationId: 'conv-today', title: '今日经营分析', description: '库存、销量和采购风险协同分析', updatedAt: '2026-07-01 10:18:00' },
  { conversationId: 'conv-replenish', title: '补货建议讨论', description: '指定商品生成采购建议', updatedAt: '2026-07-01 09:42:00' },
  { conversationId: 'conv-sales', title: '销量异常复盘', description: '解释下滑商品和预测需求', updatedAt: '2026-06-30 18:20:00' },
  { conversationId: 'conv-inventory', title: '库存风险巡检', description: '低库存、锁定库存和调拨建议', updatedAt: '2026-06-30 16:40:00' },
  { conversationId: 'conv-supplier', title: '供应商履约分析', description: '交期、价格和异常履约复盘', updatedAt: '2026-06-29 15:12:00' },
  { conversationId: 'conv-weekly', title: '周经营复盘', description: '周销量、采购和库存状态汇总', updatedAt: '2026-06-29 09:05:00' },
];

const mockSources: AiContextSource[] = [
  { sourceId: 'stock-tool', sourceType: 'TOOL', title: '库存余额 Tool', description: 'warehouse_stock 可用库存、锁定库存和安全库存', freshness: '实时查询' },
  { sourceId: 'sales-summary-tool', sourceType: 'TOOL', title: '销售汇总 Tool', description: '销售订单、出库和商品销量趋势', freshness: '今日 10:00' },
  { sourceId: 'purchase-tool', sourceType: 'TOOL', title: '采购在途 Tool', description: '采购订单、入库进度和供应商供货关系', freshness: '今日 10:00' },
];

const mockPurchaseCharts: AiChartSpec[] = [
  {
    chartId: 'purchase-demand-line',
    type: 'line',
    title: '补货商品近 7 日需求趋势',
    description: '用于判断补货优先级，数值来自销售订单和出库汇总。',
    xField: 'date',
    yFields: ['usbDemand', 'labelDemand'],
    fieldLabels: { usbDemand: 'USB-C扩展坞需求', labelDemand: '热敏标签纸需求' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { date: '06-25', usbDemand: 4, labelDemand: 18 },
      { date: '06-26', usbDemand: 5, labelDemand: 21 },
      { date: '06-27', usbDemand: 7, labelDemand: 20 },
      { date: '06-28', usbDemand: 8, labelDemand: 24 },
      { date: '06-29', usbDemand: 10, labelDemand: 28 },
      { date: '06-30', usbDemand: 12, labelDemand: 31 },
      { date: '07-01', usbDemand: 14, labelDemand: 35 },
    ],
  },
  {
    chartId: 'purchase-gap-bar',
    type: 'bar',
    title: '安全库存缺口',
    description: '缺口越高，越适合进入右侧草稿工作框复核。',
    xField: 'productName',
    yFields: ['gapQty'],
    fieldLabels: { gapQty: '安全库存缺口' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { productName: 'USB-C扩展坞', gapQty: 8 },
      { productName: '热敏标签纸', gapQty: 40 },
      { productName: 'A4复印纸', gapQty: 78 },
    ],
  },
];

const mockStockCharts: AiChartSpec[] = [
  {
    chartId: 'stock-risk-pie',
    type: 'pie',
    title: '库存风险分布',
    description: '风险分类由库存余额、安全库存和锁定库存派生。',
    xField: null,
    yFields: [],
    yUnit: '项',
    nameField: 'riskType',
    valueField: 'count',
    data: [
      { riskType: '无可用库存', count: 4 },
      { riskType: '低于安全库存', count: 3 },
      { riskType: '锁定占用过高', count: 2 },
    ],
  },
];

const mockLockReleaseCharts: AiChartSpec[] = [
  {
    chartId: 'lock-release-bar',
    type: 'bar',
    title: '可复核锁定数量',
    description: '按来源单据展示可复核数量，避免用占比图掩盖具体处理对象。',
    xField: 'sourceNo',
    yFields: ['releaseQty'],
    fieldLabels: { releaseQty: '可复核锁定数量' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { sourceNo: 'SO012', releaseQty: 12 },
      { sourceNo: 'SO018', releaseQty: 30 },
      { sourceNo: 'SO021', releaseQty: 8 },
    ],
  },
];

const mockSalesCharts: AiChartSpec[] = [
  {
    chartId: 'sales-forecast-line',
    type: 'line',
    title: '未来 7 天销量预测',
    description: '预测数据由销量预测智能体基于销售订单和出库趋势生成。',
    xField: 'date',
    yFields: ['forecastQty'],
    fieldLabels: { forecastQty: '预测销量' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { date: 'D1', forecastQty: 18 },
      { date: 'D2', forecastQty: 22 },
      { date: 'D3', forecastQty: 24 },
      { date: 'D4', forecastQty: 21 },
      { date: 'D5', forecastQty: 27 },
      { date: 'D6', forecastQty: 30 },
      { date: 'D7', forecastQty: 33 },
    ],
  },
];

const mockSupplierCharts: AiChartSpec[] = [
  {
    chartId: 'supplier-on-time-bar',
    type: 'bar',
    title: '供应商准时率对比',
    description: '按近 30 天到货记录计算，值越低越需要复核履约风险。',
    xField: 'supplierName',
    yFields: ['onTimeRate'],
    fieldLabels: { onTimeRate: '准时率' },
    yUnit: '%',
    nameField: null,
    valueField: null,
    data: [
      { supplierName: '森纸纸业', onTimeRate: 97.4 },
      { supplierName: '华东饮品', onTimeRate: 93.8 },
      { supplierName: '拓联数码', onTimeRate: 84.6 },
    ],
  },
];

const mockOverview: AiAssistantOverview = {
  refreshedAt: '2026-07-01 10:30:00',
  conversations: mockConversations,
  quickPrompts: [
    {
      promptId: 'risk',
      title: '经营风险巡检',
      description: '按仓库和商品范围识别今天最需要处理的风险',
      intentCode: 'multi-agent-risk-review',
      promptTemplate: '请按以下范围做经营风险巡检：商品范围：{{products}}；仓库范围：{{warehouses}}；关注事项：{{focus}}。输出库存、销量、采购和供应商风险，并给出今日处理顺序。',
      fields: [
        { fieldKey: 'products', label: '商品范围', fieldType: 'PRODUCT_MULTI', required: true, placeholder: '选择需要巡检的商品', defaultValue: ['全部商品'] },
        { fieldKey: 'warehouses', label: '仓库范围', fieldType: 'WAREHOUSE_MULTI', required: true, placeholder: '选择需要巡检的仓库', defaultValue: ['全部仓库'] },
        { fieldKey: 'focus', label: '关注事项', fieldType: 'TEXT', required: false, placeholder: '例如：优先看缺货和滞销', defaultValue: '优先识别缺货、滞销、锁定占用和采购交期风险' },
      ],
    },
    {
      promptId: 'purchase',
      title: '生成补货建议',
      description: '选择商品、仓库后生成采购建议',
      intentCode: 'multi-agent-purchase-advice',
      promptTemplate: '请为以下商品生成补货建议：{{products}}；关注仓库：{{warehouses}}；预测天数：{{days}} 天。需要结合销量预测、可用库存、安全库存、在途采购和供应商评分。',
      fields: [
        { fieldKey: 'products', label: '关注商品', fieldType: 'PRODUCT_MULTI', required: true, placeholder: '选择商品', defaultValue: ['A4复印纸', 'USB-C扩展坞'] },
        { fieldKey: 'warehouses', label: '关注仓库', fieldType: 'WAREHOUSE_MULTI', required: true, placeholder: '选择仓库', defaultValue: ['华东中心仓', '华南中心仓'] },
        { fieldKey: 'days', label: '预测天数', fieldType: 'DAYS', required: true, placeholder: '填写预测天数', defaultValue: '14' },
      ],
    },
    {
      promptId: 'transfer',
      title: '生成调拨单',
      description: '识别跨仓缺口并生成调拨草稿工作框',
      intentCode: 'multi-agent-transfer-draft',
      promptTemplate: '请基于已完成的销量预测、库存缺口和跨仓可用库存生成调拨建议：商品范围：{{products}}；参与仓库：{{warehouses}}。需要给出调出仓、调入仓、调拨数量和原因。',
      fields: [
        { fieldKey: 'products', label: '调拨商品', fieldType: 'PRODUCT_MULTI', required: true, placeholder: '选择商品', defaultValue: ['A4复印纸', '热敏标签纸'] },
        { fieldKey: 'warehouses', label: '参与仓库', fieldType: 'WAREHOUSE_MULTI', required: true, placeholder: '选择调出/调入仓库', defaultValue: ['华东中心仓', '华南中心仓'] },
      ],
    },
    {
      promptId: 'release',
      title: '释放锁定库存',
      description: '找出长期锁定但未出库的库存',
      intentCode: 'multi-agent-lock-release',
      promptTemplate: '请按以下范围检查可释放锁定库存：商品范围：{{products}}；仓库范围：{{warehouses}}；关注事项：{{focus}}。需要返回来源单号、可释放数量和复核原因。',
      fields: [
        { fieldKey: 'products', label: '商品范围', fieldType: 'PRODUCT_MULTI', required: true, placeholder: '选择商品', defaultValue: ['全部商品'] },
        { fieldKey: 'warehouses', label: '仓库范围', fieldType: 'WAREHOUSE_MULTI', required: true, placeholder: '选择仓库', defaultValue: ['全部仓库'] },
        { fieldKey: 'focus', label: '释放条件', fieldType: 'TEXT', required: false, placeholder: '例如：锁定超过 48 小时且未出库', defaultValue: '锁定超过 48 小时且未进入出库确认' },
      ],
    },
    {
      promptId: 'sales',
      title: '销量预测',
      description: '选择商品并预测未来销量',
      intentCode: 'multi-agent-sales-forecast',
      promptTemplate: '请预测以下商品未来 {{days}} 天销量：{{products}}；仓库范围：{{warehouses}}。需要解释增长或下滑原因，并提示缺货对销量的影响。',
      fields: [
        { fieldKey: 'products', label: '预测商品', fieldType: 'PRODUCT_MULTI', required: true, placeholder: '选择商品', defaultValue: ['A4复印纸', 'USB-C扩展坞', '热敏标签纸'] },
        { fieldKey: 'warehouses', label: '仓库范围', fieldType: 'WAREHOUSE_MULTI', required: true, placeholder: '选择仓库', defaultValue: ['全部仓库'] },
        { fieldKey: 'days', label: '预测天数', fieldType: 'DAYS', required: true, placeholder: '填写预测天数', defaultValue: '7' },
      ],
    },
  ],
};

let mockTasks: AiScheduledTask[] = [
  {
    taskId: 'task-daily-report',
    taskName: '每日经营晨报',
    category: 'REPORT',
    frequency: 'DAILY',
    cronExpression: '0 30 8 * * ?',
    productScope: ['全部商品'],
    warehouseScope: ['全部仓库'],
    analysisGoal: '汇总昨日销售、采购、库存、供应商和待办异常，给出今日优先处理建议。',
    nextRunAt: '2026-07-02 08:30:00',
    lastRunAt: '2026-07-01 08:30:02',
    lastResultSummary: '昨日销售额 28.6 万，库存高风险 SKU 4 个，建议优先处理待审核销售单和 USB-C 扩展坞补货。',
    recipients: ['经营负责人', '采购主管'],
    status: 'ENABLED',
    agentCodes: ['chief-router-agent', 'data-analysis-agent', 'inventory-analysis-agent', 'purchase-advice-agent'],
    painPoint: '每天不用人工逐页看销售、采购和库存。',
    outputFormat: 'REPORT',
    editable: true,
  },
  {
    taskId: 'task-inventory-health',
    taskName: '库存健康巡检',
    category: 'INVENTORY',
    frequency: 'DAILY',
    cronExpression: '0 30 9 * * ?',
    productScope: ['USB-C扩展坞', '热敏标签纸', '无线办公鼠标'],
    warehouseScope: ['华东中心仓', '华南中心仓', '南京备货仓'],
    analysisGoal: '识别低库存、无可用库存、高锁定库存和滞销商品，并给出补货或调拨建议。',
    nextRunAt: '2026-07-02 09:30:00',
    lastRunAt: '2026-07-01 09:30:01',
    lastResultSummary: '8 个 SKU 触发库存预警，其中 4 个无可用库存，建议生成补货草稿。',
    recipients: ['仓库主管', '采购主管'],
    status: 'ENABLED',
    agentCodes: ['chief-router-agent', 'inventory-analysis-agent', 'purchase-advice-agent'],
    painPoint: '提前发现缺货、滞销和锁定库存，减少临时救火。',
    outputFormat: 'REPORT',
    editable: true,
  },
  {
    taskId: 'task-purchase-advice',
    taskName: '采购补货建议',
    category: 'PURCHASE',
    frequency: 'DAILY',
    cronExpression: '0 0 10 * * ?',
    productScope: ['A4复印纸', 'USB-C扩展坞', '热敏标签纸'],
    warehouseScope: ['华东中心仓', '华南中心仓'],
    analysisGoal: '根据指定商品的销量预测、当前库存、安全库存、在途采购和供应商评分给出补货建议。',
    nextRunAt: '2026-07-02 10:00:00',
    lastRunAt: '2026-07-01 10:00:05',
    lastResultSummary: '建议补货 5 个商品，优先向华东饮品供应链和森纸纸业集团下单。',
    recipients: ['采购主管'],
    status: 'ENABLED',
    agentCodes: ['chief-router-agent', 'purchase-advice-agent', 'sales-forecast-agent', 'supplier-evaluation-agent'],
    painPoint: '把销量预测、库存和供应商评分合并成可执行建议。',
    outputFormat: 'REPORT',
    editable: true,
  },
  {
    taskId: 'task-weekly-sales',
    taskName: '每周销量报告',
    category: 'SALES',
    frequency: 'WEEKLY',
    cronExpression: '0 0 9 ? * MON',
    productScope: ['全部商品'],
    warehouseScope: ['全部仓库'],
    analysisGoal: '总结 Top 商品、下滑商品、客户贡献和未来一周销量预测。',
    nextRunAt: '2026-07-06 09:00:00',
    lastRunAt: '2026-06-29 09:00:03',
    lastResultSummary: 'A4复印纸、苏打水增长明显，USB-C扩展坞因缺货影响销量。',
    recipients: ['销售主管', '经营负责人'],
    status: 'ENABLED',
    agentCodes: ['chief-router-agent', 'data-analysis-agent', 'sales-forecast-agent'],
    painPoint: '自动找出销量波动，不再手动导出表格对比。',
    outputFormat: 'REPORT',
    editable: true,
  },
];

const mockTemplates: AiScheduledTaskTemplate[] = [
  {
    templateId: 'tpl-daily-report',
    taskName: '每日经营晨报',
    category: 'REPORT',
    frequency: 'DAILY',
    description: '主控智能体调度多个专家，生成经营日报。',
    defaultCronExpression: '0 30 8 * * ?',
    agentCodes: ['chief-router-agent', 'data-analysis-agent', 'inventory-analysis-agent'],
    defaultProductScope: ['全部商品'],
    defaultWarehouseScope: ['全部仓库'],
    defaultAnalysisGoal: '汇总昨日销售、采购、库存、供应商和待办异常，给出今日优先处理建议。',
    outputFormat: 'REPORT',
  },
  {
    templateId: 'tpl-purchase-advice',
    taskName: '采购补货建议',
    category: 'PURCHASE',
    frequency: 'DAILY',
    description: '采购、库存、销量预测和供应商评估智能体协同。',
    defaultCronExpression: '0 0 10 * * ?',
    agentCodes: ['purchase-advice-agent', 'sales-forecast-agent', 'supplier-evaluation-agent'],
    defaultProductScope: ['A4复印纸', 'USB-C扩展坞'],
    defaultWarehouseScope: ['华东中心仓', '华南中心仓'],
    defaultAnalysisGoal: '根据指定商品的销量预测、当前库存、安全库存、在途采购和供应商评分给出补货建议。',
    outputFormat: 'REPORT',
  },
  {
    templateId: 'tpl-weekly-sales',
    taskName: '每周销量报告',
    category: 'SALES',
    frequency: 'WEEKLY',
    description: '销量预测智能体和数据分析智能体生成周报。',
    defaultCronExpression: '0 0 9 ? * MON',
    agentCodes: ['data-analysis-agent', 'sales-forecast-agent'],
    defaultProductScope: ['全部商品'],
    defaultWarehouseScope: ['全部仓库'],
    defaultAnalysisGoal: '总结 Top 商品、下滑商品、客户贡献和未来一周销量预测。',
    outputFormat: 'REPORT',
  },
];

let mockExecutions: AiTaskExecution[] = [
  {
    executionId: 'exec-20260701-1000',
    taskId: 'task-purchase-advice',
    taskName: '采购补货建议',
    startedAt: '2026-07-01 10:00:05',
    finishedAt: '2026-07-01 10:00:16',
    status: 'SUCCESS',
    resultSummary: '建议补货 5 个商品，USB-C扩展坞和热敏标签纸优先级最高；A4复印纸在华南中心仓需要补安全库存。',
    agents: ['主控智能体', '采购建议智能体', '销量预测智能体', '供应商评估智能体'],
    findings: ['USB-C扩展坞可用库存为 0，近 7 日需求仍在上升。', '热敏标签纸安全库存缺口 40 卷，华南中心仓已无可用库存。', '森纸纸业集团准时率 97.4%，适合作为纸品补货首选供应商。'],
    suggestions: ['生成 USB-C扩展坞补货草稿 8 个。', '热敏标签纸建议补货 40 卷。', '采购前复核拓联数码配件近期履约下降原因。'],
    metrics: [
      { label: '高优先级商品', value: '2 个', tone: 'risk' },
      { label: '建议补货量', value: '126 件', tone: 'watch' },
      { label: '预计覆盖', value: '14 天', tone: 'good' },
      { label: '供应商风险', value: '1 项', tone: 'watch' },
    ],
    charts: mockPurchaseCharts,
    sections: [
      { sectionId: 'scope', title: '分析范围', items: ['商品：A4复印纸、USB-C扩展坞、热敏标签纸。', '仓库：华东中心仓、华南中心仓。', '预测窗口：未来 14 天销量与安全库存缺口。'] },
      { sectionId: 'purchase', title: '采购建议明细', items: ['USB-C扩展坞：建议补货 8 个，优先华东中心仓，原因是可用库存为 0 且近 7 日订单需求未下降。', '热敏标签纸：建议补货 40 卷，华南中心仓优先，需覆盖安全库存缺口。', 'A4复印纸：华南中心仓建议补 78 包，华东中心仓暂不补，避免重复占用库存。'] },
      { sectionId: 'supplier', title: '供应商评估', items: ['森纸纸业集团纸品准时率 97.4%，报价稳定，可作为 A4复印纸首选。', '拓联数码配件近 30 日交付延迟 1 次，USB-C扩展坞下单前建议复核交期承诺。'] },
    ],
    nextActions: [
      { actionId: 'open-purchase', title: '进入采购订单', description: '复核后生成采购草稿', route: '/purchase/orders' },
      { actionId: 'open-stock', title: '查看库存余额', description: '核对低库存 SKU', route: '/warehouse/stocks' },
    ],
  },
  {
    executionId: 'exec-20260701-0930',
    taskId: 'task-inventory-health',
    taskName: '库存健康巡检',
    startedAt: '2026-07-01 09:30:01',
    finishedAt: '2026-07-01 09:30:08',
    status: 'SUCCESS',
    resultSummary: '发现 8 个库存风险 SKU，其中 4 个无可用库存，2 个存在高锁定占用。',
    agents: ['主控智能体', '库存分析智能体', '采购建议智能体'],
    findings: ['南京备货仓 USB-C扩展坞可用库存为 0。', '华南中心仓热敏标签纸低于安全库存。'],
    suggestions: ['优先生成补货建议。', '复核是否存在待确认入库单。'],
    metrics: [
      { label: '风险 SKU', value: '8 个', tone: 'risk' },
      { label: '无可用库存', value: '4 个', tone: 'risk' },
      { label: '高锁定占用', value: '2 个', tone: 'watch' },
      { label: '建议调拨', value: '3 条', tone: 'good' },
    ],
    charts: mockStockCharts,
    sections: [
      { sectionId: 'risk', title: '库存风险', items: ['USB-C扩展坞在南京备货仓无可用库存，且销售订单仍有未出库需求。', '热敏标签纸华南中心仓低于安全库存，建议从华东中心仓调拨或追加采购。'] },
      { sectionId: 'operation', title: '处理建议', items: ['先复核待确认入库单，避免重复采购。', '对锁定超过 24 小时的销售单做出库或释放库存处理。'] },
    ],
    nextActions: [
      { actionId: 'open-stock', title: '打开库存管理', description: '查看风险 SKU', route: '/warehouse/stocks' },
    ],
  },
  {
    executionId: 'exec-20260630-1000',
    taskId: 'task-purchase-advice',
    taskName: '采购补货建议',
    startedAt: '2026-06-30 10:00:04',
    finishedAt: '2026-06-30 10:00:13',
    status: 'SUCCESS',
    resultSummary: '建议补货 4 个商品，A4复印纸和热敏标签纸优先级最高；USB-C扩展坞库存将在 3 天内触发风险。',
    agents: ['主控智能体', '采购建议智能体', '销量预测智能体', '供应商评估智能体'],
    findings: ['A4复印纸华南中心仓低于安全库存。', '热敏标签纸华南中心仓安全库存缺口 32 卷。', 'USB-C扩展坞预计 3 天内低于安全库存。'],
    suggestions: ['A4复印纸建议补货 60 包。', '热敏标签纸建议补货 32 卷。', '提前复核 USB-C扩展坞供应商交期。'],
    metrics: [
      { label: '高优先级商品', value: '2 个', tone: 'risk' },
      { label: '建议补货量', value: '92 件', tone: 'watch' },
      { label: '预计覆盖', value: '12 天', tone: 'good' },
      { label: '供应商风险', value: '0 项', tone: 'good' },
    ],
    charts: mockPurchaseCharts.slice(1),
    sections: [
      { sectionId: 'scope', title: '分析范围', items: ['商品：A4复印纸、USB-C扩展坞、热敏标签纸。', '仓库：华东中心仓、华南中心仓。', '预测窗口：未来 14 天销量与安全库存缺口。'] },
      { sectionId: 'purchase', title: '采购建议明细', items: ['A4复印纸：华南中心仓建议补 60 包。', '热敏标签纸：华南中心仓建议补 32 卷。', 'USB-C扩展坞：暂不下单，先复核供应商交期。'] },
    ],
    nextActions: [
      { actionId: 'open-purchase', title: '进入采购订单', description: '复核后生成采购草稿', route: '/purchase/orders' },
      { actionId: 'open-stock', title: '查看库存余额', description: '核对低库存 SKU', route: '/warehouse/stocks' },
    ],
  },
  {
    executionId: 'exec-20260630-0930',
    taskId: 'task-inventory-health',
    taskName: '库存健康巡检',
    startedAt: '2026-06-30 09:30:02',
    finishedAt: '2026-06-30 09:30:11',
    status: 'SUCCESS',
    resultSummary: '发现 7 个库存风险 SKU，其中 3 个无可用库存，3 个存在高锁定占用。',
    agents: ['主控智能体', '库存分析智能体', '采购建议智能体'],
    findings: ['华南中心仓热敏标签纸低于安全库存。', '无线办公鼠标存在高锁定占用。'],
    suggestions: ['热敏标签纸建议补货或调拨。', '复核无线办公鼠标锁定库存。'],
    metrics: [
      { label: '风险 SKU', value: '7 个', tone: 'risk' },
      { label: '无可用库存', value: '3 个', tone: 'risk' },
      { label: '高锁定占用', value: '3 个', tone: 'watch' },
      { label: '建议调拨', value: '2 条', tone: 'good' },
    ],
    charts: mockStockCharts,
    sections: [
      { sectionId: 'risk', title: '库存风险', items: ['热敏标签纸华南中心仓低于安全库存。', '无线办公鼠标锁定库存偏高，建议复核销售单状态。'] },
      { sectionId: 'operation', title: '处理建议', items: ['先处理热敏标签纸调拨。', '释放超过 24 小时未出库的锁定库存。'] },
    ],
    nextActions: [
      { actionId: 'open-stock', title: '打开库存管理', description: '查看风险 SKU', route: '/warehouse/stocks' },
    ],
  },
];

function delay(ms = 220) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

function normalizeStringArray(value: unknown) {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : [];
}

function normalizeSource(item: AiContextSource): AiContextSource {
  const sourceType = ['TOOL', 'WORKFLOW', 'KNOWLEDGE', 'TASK'].includes(item.sourceType) ? item.sourceType : 'TOOL';
  return {
    sourceId: normalizeStringId(item.sourceId, 'sourceId'),
    sourceType,
    title: String(item.title || ''),
    description: String(item.description || ''),
    freshness: String(item.freshness || ''),
  };
}

function normalizeTrace(item: AiAgentTrace): AiAgentTrace {
  return {
    traceId: normalizeStringId(item.traceId, 'traceId'),
    agentCode: normalizeStringId(item.agentCode, 'agentCode'),
    agentName: String(item.agentName || ''),
    summary: String(item.summary || ''),
    status: item.status === 'PLANNED' || item.status === 'RUNNING' || item.status === 'FAILED' ? item.status : 'DONE',
  };
}

function normalizeActionCard(item: AiActionCard): AiActionCard {
  return {
    actionId: normalizeStringId(item.actionId, 'actionId'),
    title: String(item.title || ''),
    description: String(item.description || ''),
    actionType: item.actionType === 'NAVIGATE' || item.actionType === 'PREVIEW' ? item.actionType : 'WORKFLOW',
    route: normalizeNullableStringId(item.route, 'route'),
    riskLevel: item.riskLevel === 'HIGH' || item.riskLevel === 'MEDIUM' ? item.riskLevel : 'LOW',
  };
}

function normalizeTaskCard(card: AiTaskCard | null | undefined): AiTaskCard | null {
  if (!card) return null;
  return {
    title: String(card.title || ''),
    description: String(card.description || ''),
    parameters: Array.isArray(card.parameters)
      ? card.parameters.map(item => ({ label: String(item.label || ''), value: String(item.value || '') }))
      : [],
  };
}

function normalizeChartRow(row: unknown) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return {};
  return Object.entries(row).reduce<Record<string, string | number | null>>((result, [key, value]) => {
    if (typeof value === 'number' && Number.isFinite(value)) {
      result[key] = value;
      return result;
    }
    if (typeof value === 'string') {
      result[key] = value;
      return result;
    }
    result[key] = value == null ? null : String(value);
    return result;
  }, {});
}

function normalizeChart(chart: AiChartSpec): AiChartSpec {
  const type = chart.type === 'bar' || chart.type === 'pie' ? chart.type : 'line';
  return {
    chartId: normalizeStringId(chart.chartId, 'chartId'),
    type,
    title: String(chart.title || ''),
    description: String(chart.description || ''),
    xField: chart.xField ? String(chart.xField) : null,
    yFields: Array.isArray(chart.yFields) ? chart.yFields.map(String).filter(Boolean) : [],
    fieldLabels: chart.fieldLabels && typeof chart.fieldLabels === 'object' ? Object.fromEntries(Object.entries(chart.fieldLabels).map(([key, value]) => [String(key), String(value)])) : undefined,
    yUnit: chart.yUnit ? String(chart.yUnit) : null,
    nameField: chart.nameField ? String(chart.nameField) : null,
    valueField: chart.valueField ? String(chart.valueField) : null,
    data: Array.isArray(chart.data) ? chart.data.map(normalizeChartRow) : [],
  };
}

function normalizeMessage(item: AiChatMessage): AiChatMessage {
  return {
    messageId: normalizeStringId(item.messageId, 'messageId'),
    role: item.role === 'user' ? 'user' : 'assistant',
    content: String(item.content || ''),
    createdAt: String(item.createdAt || ''),
    charts: Array.isArray(item.charts) ? item.charts.map(normalizeChart) : [],
    actionCards: Array.isArray(item.actionCards) ? item.actionCards.map(normalizeActionCard) : [],
    sources: Array.isArray(item.sources) ? item.sources.map(normalizeSource) : [],
    agentTraces: Array.isArray(item.agentTraces) ? item.agentTraces.map(normalizeTrace) : [],
    taskCard: normalizeTaskCard(item.taskCard),
  };
}

function normalizePromptField(item: AiPromptField): AiPromptField {
  const fieldType = ['PRODUCT_MULTI', 'WAREHOUSE_MULTI', 'DAYS', 'TEXT'].includes(item.fieldType) ? item.fieldType : 'TEXT';
  return {
    fieldKey: normalizeStringId(item.fieldKey, 'fieldKey'),
    label: String(item.label || ''),
    fieldType,
    required: Boolean(item.required),
    placeholder: String(item.placeholder || ''),
    defaultValue: Array.isArray(item.defaultValue) ? item.defaultValue.map(String) : String(item.defaultValue || ''),
  };
}

function normalizeQuickPrompt(prompt: AiQuickPrompt): AiQuickPrompt {
  return {
    promptId: normalizeStringId(prompt.promptId, 'promptId'),
    title: String(prompt.title || ''),
    description: String(prompt.description || ''),
    intentCode: String(prompt.intentCode || ''),
    promptTemplate: String(prompt.promptTemplate || ''),
    fields: Array.isArray(prompt.fields) ? prompt.fields.map(normalizePromptField) : [],
  };
}

function normalizeConversation(conversation: AiConversationSummary): AiConversationSummary {
  return {
    conversationId: normalizeStringId(conversation.conversationId, 'conversationId'),
    title: String(conversation.title || ''),
    description: String(conversation.description || ''),
    updatedAt: String(conversation.updatedAt || ''),
  };
}

function normalizeOverview(data: AiAssistantOverview): AiAssistantOverview {
  return {
    refreshedAt: String(data.refreshedAt || ''),
    conversations: data.conversations.map(normalizeConversation),
    quickPrompts: data.quickPrompts.map(normalizeQuickPrompt),
  };
}

function buildMockAssistantReply(request: AiChatRequest): AiChatResponse {
  const conversationId = request.conversationId || `conv-${Date.now()}`;
  const isTransfer = request.message.includes('调拨');
  const isRelease = request.message.includes('释放') || request.message.includes('锁定');
  const isPurchase = request.message.includes('采购') || request.message.includes('补货');
  const isSupplier = request.message.includes('供应商') || request.message.includes('履约');
  const isSales = request.message.includes('销量') || request.message.includes('预测');
  const isStock = request.message.includes('库存') || request.message.includes('风险');
  const content = isTransfer
    ? '已完成跨仓库存分析。热敏标签纸建议从华东中心仓调拨到华南中心仓，A4复印纸建议从南京备货仓调拨到华南中心仓。右侧已生成调拨草稿工作框，可修改调出仓、调入仓和调拨数量。'
    : isRelease
      ? '已识别可复核的锁定库存。两条销售单锁定超过 48 小时且未进入出库确认，右侧已生成锁定释放工作框，可修改释放数量和复核原因。'
      : isSupplier
        ? '已完成近 30 天供应商履约复盘。森纸纸业集团准时率保持 97.4%，可作为纸品供货优先供方；拓联数码配件准时率降至 84.6%，建议下单前先复核延期原因和备选供方。'
      : isPurchase
    ? '已完成初步拆解：先看库存缺口和销量预测，再用供应商评分校验采购可行性。当前建议 USB-C扩展坞和热敏标签纸优先补货，A4复印纸只补华南中心仓安全库存。'
    : isSales
      ? '销量预测显示：A4复印纸仍保持稳定增长，USB-C扩展坞受缺货影响导致销量被压制。建议先恢复可用库存，再观察未来 7 天销量回弹。'
      : '我会按经营目标自动选择数据分析、采购、库存、销量或供应商方向，并把结果汇总为可执行建议。';
  const charts = isTransfer
    ? mockPurchaseCharts.slice(1)
    : isRelease
      ? mockLockReleaseCharts
      : isSupplier
        ? mockSupplierCharts
      : isPurchase
        ? mockPurchaseCharts
        : isSales
          ? mockSalesCharts
          : isStock
            ? mockStockCharts
            : [];

  return {
    conversationId,
    message: {
      messageId: `msg-${Date.now()}-${mockConversationSeq++}`,
      role: 'assistant',
      content,
      createdAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
      charts,
      actionCards: [
        { actionId: isTransfer ? 'preview-transfer' : isRelease ? 'preview-release' : isSupplier ? 'view-supplier' : 'view-stock', title: isTransfer ? '预览调拨草稿' : isRelease ? '预览释放清单' : isSupplier ? '查看供应商资料' : '打开库存管理', description: isTransfer ? '在右侧工作框复核调拨明细' : isRelease ? '在右侧工作框复核锁定来源' : isSupplier ? '查看履约、价格和异常记录' : '查看风险 SKU 的库存余额', actionType: isTransfer || isRelease ? 'PREVIEW' : 'NAVIGATE', route: isTransfer || isRelease ? null : isSupplier ? '/purchase/suppliers' : '/warehouse/stocks', riskLevel: isTransfer || isRelease ? 'MEDIUM' : 'LOW' },
        { actionId: 'view-task', title: '查看任务结果', description: '进入经营任务中心查看最近执行报告', actionType: 'NAVIGATE', route: '/ai/tasks', riskLevel: 'LOW' },
      ],
      sources: isPurchase || isSupplier ? [mockSources[0], mockSources[2]] : [mockSources[0], mockSources[1]],
      agentTraces: [
        { traceId: 'trace-router', agentCode: 'chief-router-agent', agentName: '主控智能体', summary: '识别经营目标并选择专家智能体。', status: 'DONE' },
        { traceId: 'trace-inventory', agentCode: 'inventory-analysis-agent', agentName: '库存分析智能体', summary: '读取库存余额、安全库存和锁定库存。', status: 'DONE' },
        { traceId: isSales ? 'trace-sales' : isSupplier ? 'trace-supplier' : isTransfer || isRelease ? 'trace-stock-action' : 'trace-purchase', agentCode: isSales ? 'sales-forecast-agent' : isSupplier ? 'supplier-evaluation-agent' : isTransfer || isRelease ? 'inventory-analysis-agent' : 'purchase-advice-agent', agentName: isSales ? '销量预测智能体' : isSupplier ? '供应商评估智能体' : isTransfer || isRelease ? '库存分析智能体' : '采购建议智能体', summary: isSales ? '分析销量趋势并预测未来需求。' : isSupplier ? '按准时率、延期次数和异常反馈识别履约风险。' : isTransfer ? '结合跨仓可用库存和安全库存缺口生成调拨建议。' : isRelease ? '识别长期锁定且未进入出库确认的库存。' : '结合库存缺口和供应商评分生成补货建议。', status: 'DONE' },
      ],
      taskCard: null,
    },
  };
}

function normalizeSummary(summary: AiScheduledTaskSummary): AiScheduledTaskSummary {
  return {
    totalCount: normalizeFiniteNumber(summary.totalCount, 'totalCount'),
    enabledCount: normalizeFiniteNumber(summary.enabledCount, 'enabledCount'),
    failedCount: normalizeFiniteNumber(summary.failedCount, 'failedCount'),
    nextRunCount: normalizeFiniteNumber(summary.nextRunCount, 'nextRunCount'),
  };
}

function normalizeTaskStatus(value: unknown): AiScheduledTaskStatus {
  return value === 'DISABLED' || value === 'RUNNING' || value === 'FAILED' ? value : 'ENABLED';
}

function normalizeOutputFormat(value: unknown): AiScheduledTaskOutputFormat {
  return value === 'CHAT_CARD' || value === 'NOTIFICATION' ? value : 'REPORT';
}

function normalizeTask(task: AiScheduledTask): AiScheduledTask {
  return {
    ...task,
    taskId: normalizeStringId(task.taskId, 'taskId'),
    taskName: String(task.taskName || ''),
    category: task.category,
    frequency: task.frequency,
    cronExpression: String(task.cronExpression || ''),
    productScope: normalizeStringArray(task.productScope),
    warehouseScope: normalizeStringArray(task.warehouseScope),
    analysisGoal: String(task.analysisGoal || ''),
    nextRunAt: String(task.nextRunAt || ''),
    lastRunAt: normalizeNullableStringId(task.lastRunAt, 'lastRunAt'),
    lastResultSummary: String(task.lastResultSummary || ''),
    recipients: normalizeStringArray(task.recipients),
    status: normalizeTaskStatus(task.status),
    agentCodes: normalizeStringArray(task.agentCodes),
    painPoint: String(task.painPoint || ''),
    outputFormat: normalizeOutputFormat(task.outputFormat),
    editable: Boolean(task.editable),
  };
}

function normalizeMetric(metric: AiTaskExecutionMetric): AiTaskExecutionMetric {
  return {
    label: String(metric.label || ''),
    value: String(metric.value || ''),
    tone: metric.tone === 'good' || metric.tone === 'watch' || metric.tone === 'risk' ? metric.tone : 'neutral',
  };
}

function normalizeSection(section: AiTaskExecutionSection): AiTaskExecutionSection {
  return {
    sectionId: normalizeStringId(section.sectionId, 'sectionId'),
    title: String(section.title || ''),
    items: normalizeStringArray(section.items),
  };
}

function normalizeExecutionAction(action: AiTaskExecutionAction): AiTaskExecutionAction {
  return {
    actionId: normalizeStringId(action.actionId, 'actionId'),
    title: String(action.title || ''),
    description: String(action.description || ''),
    route: normalizeNullableStringId(action.route, 'route'),
  };
}

function normalizeExecution(execution: AiTaskExecution): AiTaskExecution {
  return {
    executionId: normalizeStringId(execution.executionId, 'executionId'),
    taskId: normalizeStringId(execution.taskId, 'taskId'),
    taskName: String(execution.taskName || ''),
    startedAt: String(execution.startedAt || ''),
    finishedAt: normalizeNullableStringId(execution.finishedAt, 'finishedAt'),
    status: execution.status === 'FAILED' || execution.status === 'RUNNING' ? execution.status : 'SUCCESS',
    resultSummary: String(execution.resultSummary || ''),
    agents: normalizeStringArray(execution.agents),
    findings: normalizeStringArray(execution.findings),
    suggestions: normalizeStringArray(execution.suggestions),
    metrics: Array.isArray(execution.metrics) ? execution.metrics.map(normalizeMetric) : [],
    sections: Array.isArray(execution.sections) ? execution.sections.map(normalizeSection) : [],
    charts: Array.isArray(execution.charts) ? execution.charts.map(normalizeChart) : [],
    nextActions: Array.isArray(execution.nextActions) ? execution.nextActions.map(normalizeExecutionAction) : [],
  };
}

function normalizeTaskPage(data: AiScheduledTaskPage): AiScheduledTaskPage {
  return {
    refreshedAt: String(data.refreshedAt || ''),
    summary: normalizeSummary(data.summary),
    templates: data.templates.map((template: AiScheduledTaskTemplate) => ({
      templateId: normalizeStringId(template.templateId, 'templateId'),
      taskName: String(template.taskName || ''),
      category: template.category,
      frequency: template.frequency,
      description: String(template.description || ''),
      defaultCronExpression: String(template.defaultCronExpression || ''),
      agentCodes: normalizeStringArray(template.agentCodes),
      defaultProductScope: normalizeStringArray(template.defaultProductScope),
      defaultWarehouseScope: normalizeStringArray(template.defaultWarehouseScope),
      defaultAnalysisGoal: String(template.defaultAnalysisGoal || ''),
      outputFormat: normalizeOutputFormat(template.outputFormat),
    })),
    tasks: data.tasks.map(normalizeTask),
    recentExecutions: data.recentExecutions.map(normalizeExecution),
  };
}

function buildTaskPage(): AiScheduledTaskPage {
  const enabledCount = mockTasks.filter(task => task.status === 'ENABLED' || task.status === 'RUNNING').length;
  const failedCount = mockTasks.filter(task => task.status === 'FAILED').length;
  return normalizeTaskPage({
    refreshedAt: '2026-07-01 10:40:00',
    summary: {
      totalCount: mockTasks.length,
      enabledCount,
      failedCount,
      nextRunCount: mockTasks.filter(task => task.status === 'ENABLED').length,
    },
    templates: mockTemplates,
    tasks: mockTasks,
    recentExecutions: mockExecutions,
  });
}

function buildTaskFromPayload(payload: AiScheduledTaskUpdateRequest): AiScheduledTask {
  const agentCodes = payload.category === 'PURCHASE'
    ? ['chief-router-agent', 'purchase-advice-agent', 'inventory-analysis-agent', 'sales-forecast-agent', 'supplier-evaluation-agent']
    : payload.category === 'SALES'
      ? ['chief-router-agent', 'data-analysis-agent', 'sales-forecast-agent']
      : payload.category === 'INVENTORY'
        ? ['chief-router-agent', 'inventory-analysis-agent', 'purchase-advice-agent']
        : ['chief-router-agent', 'data-analysis-agent', 'inventory-analysis-agent'];
  return {
    taskId: `task-custom-${mockTaskSeq++}`,
    taskName: payload.taskName,
    category: payload.category,
    frequency: payload.frequency,
    cronExpression: payload.cronExpression,
    productScope: payload.productScope,
    warehouseScope: payload.warehouseScope,
    analysisGoal: payload.analysisGoal,
    nextRunAt: '2026-07-02 09:00:00',
    lastRunAt: null,
    lastResultSummary: '尚未执行',
    recipients: payload.recipients,
    status: 'ENABLED',
    agentCodes,
    painPoint: '自定义经营任务，减少重复查询和人工整理。',
    outputFormat: payload.outputFormat,
    editable: true,
  };
}

export async function getAiAssistantOverview() {
  if (useMockApi) {
    await delay();
    return normalizeOverview({ ...mockOverview, conversations: mockConversations });
  }
  return getResult<AiAssistantOverview>('/ai/assistant/overview').then(normalizeOverview);
}

export async function sendAiAssistantMessage(request: AiChatRequest) {
  if (useMockApi) {
    await delay(420);
    return buildMockAssistantReply(request);
  }
  return http.post<Result<AiChatResponse>>('/ai/assistant/messages', request).then(response => ({
    conversationId: normalizeStringId(response.data.data.conversationId, 'conversationId'),
    message: normalizeMessage(response.data.data.message),
  }));
}

export async function createAiConversation() {
  if (useMockApi) {
    await delay(180);
    const conversation: AiConversationSummary = {
      conversationId: `conv-local-${Date.now()}`,
      title: `新的经营会话 ${mockConversationSeq++}`,
      description: '尚未开始分析',
      updatedAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
    };
    mockConversations = [conversation, ...mockConversations];
    return normalizeConversation(conversation);
  }
  return http.post<Result<AiConversationSummary>>('/ai/assistant/conversations').then(response => normalizeConversation(response.data.data));
}

export async function updateAiConversation(conversationId: string, payload: AiConversationUpdateRequest) {
  if (useMockApi) {
    await delay(180);
    mockConversations = mockConversations.map(item => item.conversationId === conversationId ? { ...item, title: payload.title } : item);
    const conversation = mockConversations.find(item => item.conversationId === conversationId);
    if (!conversation) throw new Error('会话不存在');
    return normalizeConversation(conversation);
  }
  return http.patch<Result<AiConversationSummary>>(`/ai/assistant/conversations/${conversationId}`, payload).then(response => normalizeConversation(response.data.data));
}

export async function deleteAiConversation(conversationId: string) {
  if (useMockApi) {
    await delay(180);
    mockConversations = mockConversations.filter(item => item.conversationId !== conversationId);
    return;
  }
  await http.delete(`/ai/assistant/conversations/${conversationId}`);
}

export async function getAiScheduledTaskPage() {
  if (useMockApi) {
    await delay();
    return buildTaskPage();
  }
  return getResult<AiScheduledTaskPage>('/ai/scheduled-tasks').then(normalizeTaskPage);
}

export async function runAiScheduledTask(taskId: string) {
  if (useMockApi) {
    await delay(460);
    const task = mockTasks.find(item => item.taskId === taskId);
    if (!task) throw new Error('定时任务不存在');
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    task.lastRunAt = now;
    task.lastResultSummary = `已手动执行 ${task.taskName}，生成最新多智能体分析报告。`;
    const execution: AiTaskExecution = {
      executionId: `exec-${Date.now()}`,
      taskId: task.taskId,
      taskName: task.taskName,
      startedAt: now,
      finishedAt: now,
      status: 'SUCCESS',
      resultSummary: task.lastResultSummary,
      agents: task.agentCodes.map(code => agentNameMap[code] || code),
      findings: [`本次分析范围：${task.productScope.join('、') || '全部商品'}。`, `关注仓库：${task.warehouseScope.join('、') || '全部仓库'}。`, '本次报告已结合库存余额、销售趋势、采购在途和供应商评分生成。'],
      suggestions: ['建议进入业务模块复核后生成草稿。', '下次任务可缩小商品范围以提升建议准确度。', '若需要自动通知，可在任务配置中增加接收岗位。'],
      metrics: [
        { label: '分析商品', value: `${task.productScope.length || 1} 项`, tone: 'neutral' },
        { label: '风险提示', value: '3 条', tone: 'watch' },
        { label: '建议动作', value: '3 条', tone: 'good' },
        { label: '执行耗时', value: '11s', tone: 'neutral' },
      ],
      charts: task.category === 'SALES' ? mockSalesCharts : task.category === 'INVENTORY' ? mockStockCharts : mockPurchaseCharts,
      sections: [
        { sectionId: 'scope', title: '范围和数据', items: [`商品范围：${task.productScope.join('、') || '全部商品'}。`, `仓库范围：${task.warehouseScope.join('、') || '全部仓库'}。`, `分析目标：${task.analysisGoal}`] },
        { sectionId: 'agent', title: '智能体结论', items: ['主控智能体完成任务拆解，并确认本次不直接写入业务单据。', '数据分析智能体识别销售波动，库存分析智能体识别可用库存缺口。', '采购建议智能体将缺口、在途采购和供应商评分合并为建议动作。'] },
        { sectionId: 'action', title: '下一步处理', items: ['高风险商品优先进入对应业务模块复核。', '需要补货的商品先生成采购草稿，人工确认后再提交。', '建议保留本次报告，作为明日晨会处理依据。'] },
      ],
      nextActions: [
        { actionId: 'open-stock', title: '查看库存管理', description: '核对库存和锁定占用', route: '/warehouse/stocks' },
        { actionId: 'open-purchase', title: '查看采购订单', description: '复核采购草稿或在途订单', route: '/purchase/orders' },
      ],
    };
    mockExecutions = [execution, ...mockExecutions].slice(0, 8);
    return normalizeTask(task);
  }
  return http.post<Result<AiScheduledTask>>(`/ai/scheduled-tasks/${taskId}/run`).then(response => normalizeTask(response.data.data));
}

export async function updateAiScheduledTaskStatus(taskId: string, status: AiScheduledTaskStatus) {
  if (useMockApi) {
    await delay(260);
    mockTasks = mockTasks.map(task => task.taskId === taskId ? { ...task, status } : task);
    const task = mockTasks.find(item => item.taskId === taskId);
    if (!task) throw new Error('定时任务不存在');
    return normalizeTask(task);
  }
  return http.patch<Result<AiScheduledTask>>(`/ai/scheduled-tasks/${taskId}/status`, { status }).then(response => normalizeTask(response.data.data));
}

export async function createAiScheduledTask(payload: AiScheduledTaskUpdateRequest) {
  if (useMockApi) {
    await delay(320);
    const task = buildTaskFromPayload(payload);
    mockTasks = [task, ...mockTasks];
    return normalizeTask(task);
  }
  return http.post<Result<AiScheduledTask>>('/ai/scheduled-tasks', payload).then(response => normalizeTask(response.data.data));
}

export async function updateAiScheduledTask(taskId: string, payload: AiScheduledTaskUpdateRequest) {
  if (useMockApi) {
    await delay(320);
    mockTasks = mockTasks.map(task => task.taskId === taskId ? { ...task, ...payload } : task);
    const task = mockTasks.find(item => item.taskId === taskId);
    if (!task) throw new Error('定时任务不存在');
    return normalizeTask(task);
  }
  return http.patch<Result<AiScheduledTask>>(`/ai/scheduled-tasks/${taskId}`, payload).then(response => normalizeTask(response.data.data));
}
