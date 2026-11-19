const elements = new Set([
  'a', 'b', 'blockquote', 'br', 'caption', 'code', 'del', 'details', 'div', 'em', 'h1', 'h2', 'h3', 'h4', 'h5',
  'h6', 'hr', 'i', 'img', 'li', 'ol', 'p', 'pre', 's', 'span', 'strong', 'table', 'tbody', 'td',
  'summary', 'tfoot', 'th', 'thead', 'tr', 'ul',
]);
const discarded = new Set(['script', 'style', 'iframe', 'object', 'embed', 'template', 'noscript', 'form']);
const anchorPrefix = 'ai-report-section-';
const anchorId = /^[\p{L}\p{N}_-]{1,100}$/u;

function safeUrl(value: string): boolean {
  try {
    return /^https?:$/.test(new URL(value, window.location.href).protocol);
  } catch {
    return false;
  }
}

/** 报告可混排少量 HTML，使用白名单剔除脚本、样式、事件和危险协议。 */
export function sanitizeReportHtml(html: string): string {
  const template = document.createElement('template');
  template.innerHTML = html;
  const opening = template.content.firstElementChild;
  if (opening?.localName === 'h1') {
    // 页面已展示报告标题，兼容模型把标题重复写在正文开头的情况。
    const titleAnchor = document.createElement('a');
    const id = opening.getAttribute('id');
    if (id && anchorId.test(id)) {
      titleAnchor.id = id;
      opening.replaceWith(titleAnchor);
    } else {
      opening.remove();
    }
  }
  for (const node of template.content.querySelectorAll('*')) {
    const tag = node.localName;
    if (!node.isConnected && !template.content.contains(node)) {
      continue;
    }
    if (discarded.has(tag) || node.namespaceURI !== 'http://www.w3.org/1999/xhtml') {
      node.remove();
      continue;
    }
    if (!elements.has(tag)) {
      node.replaceWith(...node.childNodes);
      continue;
    }
    for (const attribute of [...node.attributes]) {
      const { name, value } = attribute;
      const shared = name === 'title' || (name === 'class' && /^ai-markdown-(table|image|image-error)$/.test(value));
      const link = tag === 'a' && name === 'href' && safeUrl(value);
      const section = (tag === 'a' || /^h[1-6]$/.test(tag)) && name === 'id' && anchorId.test(value);
      const image = tag === 'img' && (name === 'alt' || (name === 'src' && safeUrl(value)));
      const span = (tag === 'th' || tag === 'td') && ['colspan', 'rowspan'].includes(name) && /^[1-9]\d?$/.test(value);
      const imageFallback = tag === 'span' && name === 'hidden' && node.classList.contains('ai-markdown-image-error');
      if (section) {
        node.setAttribute('id', anchorPrefix + value);
      } else if (!(shared || link || image || span || imageFallback)) {
        node.removeAttribute(name);
      }
    }
    if (tag === 'a' && node.hasAttribute('href')) {
      const href = node.getAttribute('href')!;
      if (href.startsWith('#')) {
        const section = href.slice(1);
        // Markdown 可能已经对中文片段做了 URL 编码。
        try {
          const decoded = decodeURIComponent(section);
          if (anchorId.test(decoded)) {
            node.setAttribute('href', '#' + anchorPrefix + encodeURIComponent(decoded));
          } else {
            node.removeAttribute('href');
          }
        } catch {
          node.removeAttribute('href');
        }
      } else {
        node.setAttribute('target', '_blank');
        node.setAttribute('rel', 'noopener noreferrer');
      }
    } else if (tag === 'img') {
      node.setAttribute('loading', 'lazy');
      node.setAttribute('referrerpolicy', 'no-referrer');
    } else if (tag === 'table' && !node.parentElement?.classList.contains('ai-markdown-table')) {
      const wrapper = document.createElement('div');
      wrapper.className = 'ai-markdown-table';
      node.before(wrapper);
      wrapper.append(node);
    }
  }
  for (const wrapper of template.content.querySelectorAll('.ai-markdown-table')) {
    wrapper.setAttribute('tabindex', '0');
    wrapper.setAttribute('role', 'region');
    wrapper.setAttribute('aria-label', '报告表格');
  }
  return template.innerHTML;
}
