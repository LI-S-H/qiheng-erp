import { onBeforeUnmount, onMounted, type Ref } from 'vue';

const openEventName = 'erp:dropdown-open';
let dropdownSequence = 0;

export function useExclusiveDropdown(open: Ref<boolean>) {
  const instanceId = `erp-dropdown-${++dropdownSequence}`;

  function handleOtherDropdownOpen(event: Event) {
    if ((event as CustomEvent<string>).detail !== instanceId) open.value = false;
  }

  function setOpen(value: boolean) {
    open.value = value;
    if (value) window.dispatchEvent(new CustomEvent(openEventName, { detail: instanceId }));
  }

  onMounted(() => window.addEventListener(openEventName, handleOtherDropdownOpen));
  onBeforeUnmount(() => window.removeEventListener(openEventName, handleOtherDropdownOpen));

  return { setOpen };
}
