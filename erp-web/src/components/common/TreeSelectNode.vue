<script setup lang="ts">
import { computed, type PropType } from 'vue';
import { ChevronRight, ChevronDown, Check } from 'lucide-vue-next';

interface TreeNode {
  deptId: string;
  deptName: string;
  parentId: string;
  status: number;
  children?: TreeNode[];
}

const props = defineProps<{
  node: TreeNode;
  level: number;
  selectedId: string;
  expandedIds: Set<string>;
}>();

const emit = defineEmits<{
  toggle: [id: string];
  select: [id: string];
}>();

const hasChildren = computed(() => Boolean(props.node.children?.length));
const isExpanded = computed(() => props.expandedIds.has(props.node.deptId));
const isSelected = computed(() => props.node.deptId === props.selectedId);
</script>

<template>
  <div>
    <button
      type="button"
      class="flex w-full items-center gap-1 rounded-sm px-2 py-1.5 text-sm hover:bg-accent cursor-pointer"
      :style="{ paddingLeft: `${props.level * 16 + 8}px` }"
      :class="{ 'bg-accent font-medium': isSelected }"
      @click="emit('select', props.node.deptId)"
    >
      <button
        v-if="hasChildren"
        type="button"
        class="p-0.5 hover:bg-accent rounded shrink-0"
        @click.stop="emit('toggle', props.node.deptId)"
      >
        <ChevronDown v-if="isExpanded" class="h-3.5 w-3.5" />
        <ChevronRight v-else class="h-3.5 w-3.5" />
      </button>
      <span v-else class="w-4 shrink-0" />
      <Check v-if="isSelected" class="h-3.5 w-3.5 text-primary shrink-0" />
      <span class="flex-1 text-left truncate">{{ props.node.deptName }}</span>
    </button>
    <div v-if="hasChildren && isExpanded">
      <TreeSelectNode
        v-for="child in props.node.children"
        :key="child.deptId"
        :node="child"
        :level="props.level + 1"
        :selected-id="props.selectedId"
        :expanded-ids="props.expandedIds"
        @toggle="emit('toggle', $event)"
        @select="emit('select', $event)"
      />
    </div>
  </div>
</template>
