<script setup lang="ts">
import { computed, ref } from 'vue';
import { getLocalTimeZone, parseDate, today, type DateValue } from '@internationalized/date';
import { CalendarDays, ChevronLeft, ChevronRight, X } from 'lucide-vue-next';
import {
  DatePickerCalendar,
  DatePickerAnchor,
  DatePickerCell,
  DatePickerCellTrigger,
  DatePickerContent,
  DatePickerField,
  DatePickerGrid,
  DatePickerGridBody,
  DatePickerGridHead,
  DatePickerGridRow,
  DatePickerHeadCell,
  DatePickerHeader,
  DatePickerHeading,
  DatePickerInput,
  DatePickerNext,
  DatePickerPrev,
  DatePickerRoot,
  DatePickerTrigger,
} from 'reka-ui';

const props = withDefaults(defineProps<{
  modelValue: string;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
}>(), {
  placeholder: '请选择日期',
  disabled: false,
  invalid: false,
});

const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const open = ref(false);

const selectedDate = computed<DateValue | undefined>({
  get: () => /^\d{4}-\d{2}-\d{2}$/.test(props.modelValue) ? parseDate(props.modelValue) : undefined,
  set: value => {
    emit('update:modelValue', value?.toString() || '');
    if (value) open.value = false;
  },
});

const calendarPlaceholder = computed(() => selectedDate.value || today(getLocalTimeZone()));
</script>

<template>
  <DatePickerRoot v-model="selectedDate" v-model:open="open" :placeholder="calendarPlaceholder" locale="zh-CN" :disabled="disabled" :close-on-select="true">
    <DatePickerAnchor as-child>
    <div class="order-date-picker" :data-empty="!modelValue || undefined" :data-invalid="invalid || undefined">
      <DatePickerField v-slot="{ segments }" class="order-date-picker__field" :aria-label="placeholder">
        <template v-for="segment in segments" :key="segment.part">
          <DatePickerInput v-if="segment.part !== 'literal'" :part="segment.part" class="order-date-picker__segment">{{ segment.value }}</DatePickerInput>
          <span v-else class="order-date-picker__literal">{{ segment.value }}</span>
        </template>
      </DatePickerField>
      <span v-if="!modelValue" class="pointer-events-none absolute left-3 text-sm text-muted-foreground">{{ placeholder }}</span>
      <button v-if="modelValue && !disabled" class="order-date-picker__clear" type="button" aria-label="清空日期" @click="emit('update:modelValue', '')"><X class="size-3.5" /></button>
      <DatePickerTrigger as-child>
        <button class="order-date-picker__trigger" type="button" aria-label="打开日期选择器"><CalendarDays class="size-4" /></button>
      </DatePickerTrigger>
    </div>
    </DatePickerAnchor>
    <DatePickerContent class="order-date-picker__content" :side-offset="6" align="start">
      <DatePickerCalendar v-slot="{ weekDays, grid }">
        <DatePickerHeader class="order-date-picker__header">
          <DatePickerPrev as-child><button type="button" class="order-date-picker__nav" aria-label="上个月"><ChevronLeft class="size-4" /></button></DatePickerPrev>
          <DatePickerHeading class="text-sm font-medium" />
          <DatePickerNext as-child><button type="button" class="order-date-picker__nav" aria-label="下个月"><ChevronRight class="size-4" /></button></DatePickerNext>
        </DatePickerHeader>
        <DatePickerGrid v-for="month in grid" :key="month.value.toString()" class="order-date-picker__grid">
          <DatePickerGridHead><DatePickerGridRow><DatePickerHeadCell v-for="day in weekDays" :key="day" class="order-date-picker__weekday">{{ day }}</DatePickerHeadCell></DatePickerGridRow></DatePickerGridHead>
          <DatePickerGridBody>
            <DatePickerGridRow v-for="(week, weekIndex) in month.rows" :key="weekIndex">
              <DatePickerCell v-for="day in week" :key="day.toString()" :date="day">
                <DatePickerCellTrigger v-slot="{ dayValue, selected, today, outsideView }" :day="day" :month="month.value" as-child>
                  <button type="button" class="order-date-picker__day" :class="{ 'is-selected': selected, 'is-today': today, 'is-outside': outsideView }">{{ dayValue }}</button>
                </DatePickerCellTrigger>
              </DatePickerCell>
            </DatePickerGridRow>
          </DatePickerGridBody>
        </DatePickerGrid>
      </DatePickerCalendar>
    </DatePickerContent>
  </DatePickerRoot>
</template>

<style scoped>
.order-date-picker { position: relative; display: flex; height: 2.25rem; width: 100%; align-items: center; border: 1px solid var(--input); border-radius: calc(var(--radius) - 2px); background: var(--background); color: var(--foreground); }
.order-date-picker:focus-within { border-color: color-mix(in srgb, var(--ring) 62%, var(--input)); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ring) 15%, transparent); }
.order-date-picker[data-invalid] { border-color: var(--destructive); }
.order-date-picker__field { display: flex; min-width: 0; flex: 1; align-items: center; padding-left: 11px; font-size: .875rem; line-height: 1.25rem; }
.order-date-picker__segment { min-width: 1.4ch; padding: 0; color: inherit; text-align: center; outline: none; }
.order-date-picker__literal { color: var(--muted-foreground); }
.order-date-picker[data-empty] .order-date-picker__segment, .order-date-picker[data-empty] .order-date-picker__literal { color: transparent; }
.order-date-picker__trigger, .order-date-picker__clear { display: inline-flex; align-items: center; justify-content: center; border: 0; background: transparent; color: var(--muted-foreground); cursor: pointer; }
.order-date-picker__trigger { width: 2.25rem; height: 2.25rem; }
.order-date-picker__clear { width: 1.5rem; height: 1.5rem; border-radius: .25rem; }
.order-date-picker__trigger:hover, .order-date-picker__clear:hover { color: var(--foreground); background: color-mix(in srgb, var(--muted) 70%, transparent); }
:global(.order-date-picker__content) { z-index: 2147483000 !important; width: 18.25rem; border: 1px solid var(--border); border-radius: calc(var(--radius) + 2px); background: var(--popover); padding: .75rem; color: var(--popover-foreground); box-shadow: 0 12px 28px color-mix(in srgb, #0f172a 20%, transparent), 0 2px 6px color-mix(in srgb, #0f172a 12%, transparent); }
.order-date-picker__header { display: flex; align-items: center; justify-content: space-between; padding: 0 .125rem .5rem; }
.order-date-picker__nav { display: inline-flex; width: 1.875rem; height: 1.875rem; align-items: center; justify-content: center; border: 0; border-radius: .375rem; background: transparent; color: var(--muted-foreground); cursor: pointer; }
.order-date-picker__nav:hover { background: var(--muted); color: var(--foreground); }
.order-date-picker__grid { width: 100%; border-collapse: collapse; table-layout: fixed; }
.order-date-picker__weekday { height: 1.875rem; color: var(--muted-foreground); font-size: .75rem; font-weight: 500; text-align: center; }
.order-date-picker__day { display: inline-flex; width: 2rem; height: 2rem; align-items: center; justify-content: center; border: 0; border-radius: .375rem; background: transparent; color: var(--foreground); font-size: .8125rem; cursor: pointer; }
.order-date-picker__day:hover { background: color-mix(in srgb, var(--primary) 11%, transparent); }
.order-date-picker__day.is-selected { background: var(--primary); color: var(--primary-foreground); font-weight: 600; }
.order-date-picker__day.is-today:not(.is-selected) { box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 55%, transparent); }
.order-date-picker__day.is-outside { color: color-mix(in srgb, var(--muted-foreground) 45%, transparent); }
</style>
