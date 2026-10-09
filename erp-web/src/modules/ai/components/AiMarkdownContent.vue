<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';

const props = defineProps<{ content: string }>();
// 模型输出属于不可信文本；只允许解析器生成的标签，保留默认危险协议校验。
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true });
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank');
  tokens[index].attrSet('rel', 'noopener noreferrer');
  return renderer.renderToken(tokens, index, options);
};
const renderImage = markdown.renderer.rules.image!;
markdown.renderer.rules.image = (tokens, index, options, env, renderer) => {
  const token = tokens[index];
  const label = markdown.utils.escapeHtml(renderer.renderInlineAsText(token.children || [], options, env) || '图片');
  const source = String(token.attrGet('src') || '');
  // 图片仅使用 HTTP(S) 或相对地址，不允许 data/file 等协议。
  try {
    if (!source || !/^https?:$/.test(new URL(source, 'https://markdown.invalid').protocol)) return label;
  } catch {
    // 流式正文可能暂时包含未完整的图片地址，保持文本可读并等待后续内容。
    return label;
  }
  token.attrSet('loading', 'lazy');
  token.attrSet('decoding', 'async');
  token.attrSet('referrerpolicy', 'no-referrer');
  // 图片可能被 Markdown 链接包裹，此时沿用外层链接，避免嵌套 a 导致浏览器拆散失败提示。
  const insideLink = tokens.slice(0, index).reduce((depth, item) => depth + (item.type === 'link_open' ? 1 : item.type === 'link_close' ? -1 : 0), 0) > 0;
  const originalLink = insideLink ? '' : ` · <a href="${markdown.utils.escapeHtml(source)}" target="_blank" rel="noopener noreferrer">打开原图</a>`;
  return `<span class="ai-markdown-image">${renderImage(tokens, index, options, env, renderer)}<span class="ai-markdown-image-error" hidden>图片加载失败：${label}${originalLink}</span></span>`;
};
markdown.renderer.rules.table_open = () => '<div class="ai-markdown-table" tabindex="0" role="region" aria-label="回复表格"><table>';
markdown.renderer.rules.table_close = () => '</table></div>';
const renderedContent = computed(() => markdown.render(props.content));

function handleImageError(event: Event) {
  const image = event.target;
  if (!(image instanceof HTMLImageElement)) return;
  // error 不冒泡，使用捕获监听；隐藏损坏图片，保留可访问的原图链接。
  const fallback = image.parentElement?.querySelector<HTMLElement>('.ai-markdown-image-error');
  if (!fallback) return;
  image.style.display = 'none';
  fallback.hidden = false;
}
</script>

<template>
  <div class="ai-markdown-content" @error.capture="handleImageError" v-html="renderedContent" />
</template>

<style scoped>
.ai-markdown-content {
  min-width: 0;
  margin-top: 8px;
  color: #344054;
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.ai-markdown-content :deep(p),
.ai-markdown-content :deep(ul),
.ai-markdown-content :deep(ol),
.ai-markdown-content :deep(blockquote),
.ai-markdown-content :deep(pre),
.ai-markdown-content :deep(.ai-markdown-table) { margin: 10px 0; }
.ai-markdown-content :deep(> :first-child) { margin-top: 0; }
.ai-markdown-content :deep(> :last-child) { margin-bottom: 0; }
.ai-markdown-content :deep(h1),
.ai-markdown-content :deep(h2),
.ai-markdown-content :deep(h3),
.ai-markdown-content :deep(h4),
.ai-markdown-content :deep(h5),
.ai-markdown-content :deep(h6) { margin: 16px 0 8px; color: var(--foreground); font-weight: 600; line-height: 1.5; }
.ai-markdown-content :deep(h1) { font-size: 20px; }
.ai-markdown-content :deep(h2) { font-size: 18px; }
.ai-markdown-content :deep(h3) { font-size: 16px; }
.ai-markdown-content :deep(h4),
.ai-markdown-content :deep(h5),
.ai-markdown-content :deep(h6) { font-size: 14px; }
.ai-markdown-content :deep(ul) { padding-left: 24px; list-style: disc; }
.ai-markdown-content :deep(ol) { padding-left: 24px; list-style: decimal; }
.ai-markdown-content :deep(li > p) { margin: 4px 0; }
.ai-markdown-content :deep(strong) { font-weight: 600; }
.ai-markdown-content :deep(em) { font-style: italic; }
.ai-markdown-content :deep(blockquote) { padding-left: 12px; border-left: 3px solid var(--border); color: var(--muted-foreground); }
.ai-markdown-content :deep(a) { color: var(--primary); text-decoration: underline; text-underline-offset: 3px; }
.ai-markdown-content :deep(.ai-markdown-image) { display: block; margin: 10px 0; }
.ai-markdown-content :deep(img) { display: block; max-width: 100%; height: auto; border-radius: 6px; }
.ai-markdown-content :deep(.ai-markdown-image-error) { display: block; padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; color: var(--muted-foreground); }
.ai-markdown-content :deep(.ai-markdown-image-error[hidden]) { display: none; }
.ai-markdown-content :deep(code) { padding: 2px 5px; border-radius: 4px; background: var(--muted); font-family: ui-monospace, Consolas, monospace; font-size: 13px; }
.ai-markdown-content :deep(pre) { max-width: 100%; overflow-x: auto; padding: 12px; border: 1px solid var(--border); border-radius: 6px; background: var(--muted); white-space: pre; overflow-wrap: normal; }
.ai-markdown-content :deep(pre code) { padding: 0; background: transparent; }
.ai-markdown-content :deep(.ai-markdown-table) { max-width: 100%; overflow-x: auto; border: 1px solid var(--border); border-radius: 6px; }
.ai-markdown-content :deep(table) { width: 100%; border-collapse: collapse; white-space: nowrap; }
.ai-markdown-content :deep(th),
.ai-markdown-content :deep(td) { padding: 7px 12px; border-bottom: 1px solid var(--border); text-align: left; }
.ai-markdown-content :deep(th) { background: var(--muted); font-weight: 600; }
.ai-markdown-content :deep(tr:last-child td) { border-bottom: 0; }
.ai-markdown-content :deep(hr) { margin: 14px 0; border: 0; border-top: 1px solid var(--border); }
</style>
