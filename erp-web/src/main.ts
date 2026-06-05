import { createApp } from 'vue';
import { createPinia } from 'pinia';
import {
  ElAlert,
  ElAside,
  ElAvatar,
  ElButton,
  ElCard,
  ElContainer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElInput,
  ElMain,
  ElTag,
} from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import { router } from './router';
import './styles/global.css';

const app = createApp(App);
const elementComponents = [
  ElAlert,
  ElAside,
  ElAvatar,
  ElButton,
  ElCard,
  ElContainer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElIcon,
  ElInput,
  ElMain,
  ElTag,
];

app.use(createPinia());
app.use(router);
elementComponents.forEach(component => app.use(component));

app.mount('#app');
