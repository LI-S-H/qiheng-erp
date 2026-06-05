<template>
  <section class="page-shell dashboard-page">
    <h1 class="page-title">工作台</h1>

    <el-alert
      title="启衡 ERP 基础工作台"
      type="success"
      :closable="false"
      show-icon
    />

    <div class="dashboard-grid">
      <el-card shadow="never">
        <template #header>业务主线</template>
        <p class="muted-text">围绕产品 仓库 采购 销售形成进销存闭环</p>
      </el-card>

      <el-card shadow="never">
        <template #header>权限策略</template>
        <p class="muted-text">菜单结构先固定 接口访问以后端权限码校验为准</p>
      </el-card>

      <el-card shadow="never">
        <template #header>智能能力</template>
        <p class="muted-text">AI 查询复用业务接口和权限上下文 不直接绕过业务服务</p>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.dashboard-page {
  min-height: 100%;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.dashboard-grid :deep(.el-card) {
  border-radius: 8px;
}

@media (max-width: 960px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
