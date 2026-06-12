<script setup lang="ts">
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { CheckCircle } from 'lucide-vue-next';
</script>

<template>
  <section class="page-shell dashboard-page">
    <h1 class="page-title">工作台</h1>

    <Alert>
      <CheckCircle class="h-4 w-4" />
      <AlertTitle>启衡 ERP 基础工作台</AlertTitle>
      <AlertDescription>系统已就绪，可通过左侧导航进入各业务模块。</AlertDescription>
    </Alert>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mt-5">
      <Card>
        <CardHeader>
          <CardTitle>业务主线</CardTitle>
        </CardHeader>
        <CardContent>
          <p class="muted-text">围绕产品 仓库 采购 销售形成进销存闭环</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>权限策略</CardTitle>
        </CardHeader>
        <CardContent>
          <p class="muted-text">菜单结构先固定 接口访问以后端权限码校验为准</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>智能能力</CardTitle>
        </CardHeader>
        <CardContent>
          <p class="muted-text">AI 查询复用业务接口和权限上下文 不直接绕过业务服务</p>
        </CardContent>
      </Card>
    </div>
  </section>
</template>
