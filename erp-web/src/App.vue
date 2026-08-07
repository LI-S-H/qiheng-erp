<script setup lang="ts">
import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
</script>

<template>
  <TooltipProvider :delay-duration="300">
    <RouterView />
    <Toaster position="top-center" :duration="3000" rich-colors />
  </TooltipProvider>
</template>
