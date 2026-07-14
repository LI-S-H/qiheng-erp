<script setup lang="ts">
withDefaults(defineProps<{
  open: boolean;
  persist?: boolean;
}>(), {
  persist: true,
});
</script>

<template>
  <div
    v-if="persist || open"
    class="submenu-collapse"
    :class="{ 'is-open': open }"
    :aria-hidden="!open"
    :inert="!open"
  >
    <div class="submenu-collapse__inner">
      <slot />
    </div>
  </div>
</template>
