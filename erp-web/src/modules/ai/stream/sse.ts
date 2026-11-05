export interface SseFrame {
  event: string;
  data: string;
}

/** SSE 的分块边界与行边界无关，保留未完成的行并兼容 LF、CRLF 和 CR。 */
export function createSseParser(onFrame: (frame: SseFrame) => void) {
  let buffer = '';
  let event = '';
  let data: string[] = [];
  let skipLf = false;
  const acceptLine = (line: string) => {
    if (!line) {
      if (data.length && data.join('\n').trim()) onFrame({ event: event || 'message', data: data.join('\n') });
      event = '';
      data = [];
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    if (field === 'data') data.push(value);
  };
  return {
    push(chunk: string) {
      for (const char of chunk) {
        if (skipLf) {
          skipLf = false;
          if (char === '\n') continue;
        }
        if (char === '\r' || char === '\n') {
          acceptLine(buffer);
          buffer = '';
          skipLf = char === '\r';
        } else buffer += char;
      }
    },
  };
}

export async function readSseStream(body: ReadableStream<Uint8Array>, onFrame: (frame: SseFrame) => void, signal?: AbortSignal) {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const parser = createSseParser(onFrame);
  const abort = () => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener('abort', abort, { once: true });
  try {
    for (;;) {
      if (signal?.aborted) throw new DOMException('生成已停止', 'AbortError');
      const { done, value } = await reader.read();
      if (signal?.aborted) throw new DOMException('生成已停止', 'AbortError');
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    // 未以空行结尾的事件视为截断，不能把半条消息当作最终成功。
  } finally {
    signal?.removeEventListener('abort', abort);
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
