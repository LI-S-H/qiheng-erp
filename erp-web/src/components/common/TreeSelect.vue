<script setup lang="ts">
import { computed, ref } from 'vue';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ChevronDown } from 'lucide-vue-next';
import TreeSelectNode from './TreeSelectNode.vue';
import { useExclusiveDropdown } from '@/shared/composables/use-exclusive-dropdown';

interface TreeNode {
  deptId: string;
  deptName: string;
  parentId: string;
  status: number;
  children?: TreeNode[];
}

interface Props {
  modelValue: string;
  options: TreeNode[];
  placeholder?: string;
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '请选择',
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const isOpen = ref(false);
const { setOpen } = useExclusiveDropdown(isOpen);
const expandedIds = ref<Set<string>>(new Set(collectAllIds(props.options)));

function collectAllIds(nodes: TreeNode[]): string[] {
  const ids: string[] = [];
  function walk(items: TreeNode[]) {
    items.forEach(item => {
      if (item.children?.length) {
        ids.push(item.deptId);
        walk(item.children);
      }
    });
  }
  walk(nodes);
  return ids;
}

const selectedLabel = computed(() => {
  function find(nodes: TreeNode[]): string | null {
    for (const node of nodes) {
      if (node.deptId === props.modelValue) return node.deptName;
      if (node.children) {
        const found = find(node.children);
        if (found) return found;
      }
    }
    return null;
  }
  return find(props.options) || props.placeholder;
});

function toggleExpand(id: string) {
  const next = new Set(expandedIds.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  expandedIds.value = next;
}

function selectNode(id: string) {
  emit('update:modelValue', id);
  isOpen.value = false;
}
</script>

<template>
  <Popover :open="isOpen" @update:open="setOpen">
    <PopoverTrigger as-child>
      <Button
        variant="outline"
        role="combobox"
        :aria-expanded="isOpen"
        class="w-full justify-between font-normal"
      >
        <span :class="{ 'text-muted-foreground': selectedLabel === props.placeholder }">
          {{ selectedLabel }}
        </span>
        <ChevronDown class="ml-2 h-4 w-4 shrink-0 opacity-50" />
      </Button>
    </PopoverTrigger>
    <PopoverContent data-tree-select-content class="w-[--reka-popover-trigger-width] p-0" align="start">
      <ScrollArea class="h-[250px] p-2">
        <template v-for="node in props.options" :key="node.deptId">
          <TreeSelectNode
            :node="node"
            :level="0"
            :selected-id="props.modelValue"
            :expanded-ids="expandedIds"
            @toggle="toggleExpand"
            @select="selectNode"
          />
        </template>
      </ScrollArea>
    </PopoverContent>
  </Popover>
</template>
