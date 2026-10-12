import { withCorrelationHeaders } from './correlation';
import { ApiError, problemFrom } from './errors';
import { absolute, type ApiClientOptions, joinUrl } from './http';
import { type LolaChatRequest, type LolaEvent, LolaEventSchema } from './schemas/shopper';

/** One raw server-sent event. */
export interface SseMessage {
  event: string;
  data: string;
}

/** Parses a text/event-stream body into events as bytes arrive (multi-line data, comments and CRLF handled). */
export async function* readSse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseMessage> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data: string[] = [];
  const flush = (): SseMessage | null => {
    const out = data.length ? { event, data: data.join('\n') } : null;
    event = 'message';
    data = [];
    return out;
  };
  try {
    for (;;) {
      const { value, done } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      let nl = buffer.search(/\r?\n/);
      while (nl >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(buffer[nl] === '\r' ? nl + 2 : nl + 1);
        if (line === '') {
          const msg = flush();
          if (msg) yield msg;
        } else if (!line.startsWith(':')) {
          const i = line.indexOf(':');
          const field = i < 0 ? line : line.slice(0, i);
          const value = i < 0 ? '' : line.slice(i + 1).replace(/^ /, '');
          if (field === 'event') event = value;
          else if (field === 'data') data.push(value);
        }
        nl = buffer.search(/\r?\n/);
      }
      if (done) {
        const msg = flush();
        if (msg) yield msg;
        return;
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** Decodes a raw event into a typed Lola event (null for unknown events or bad payloads). */
export function toLolaEvent(msg: SseMessage): LolaEvent | null {
  let data: unknown;
  try {
    data = JSON.parse(msg.data);
  } catch {
    return null;
  }
  const parsed = LolaEventSchema.safeParse({ type: msg.event, data });
  return parsed.success ? parsed.data : null;
}

/**
 * Streams one Lola turn (POST /v1/lola/chat via the BFF) and calls onEvent for every event as it arrives.
 * Problems before the stream starts (401 sign-in, 429 budget, 503) throw ApiError.
 */
export async function streamLola(
  options: Pick<ApiClientOptions, 'baseUrl' | 'fetch' | 'headers'>,
  request: LolaChatRequest,
  onEvent: (e: LolaEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const doFetch = options.fetch ?? ((req: Request) => fetch(req));
  const res = await doFetch(
    new Request(absolute(joinUrl(options.baseUrl, '/v1/lola/chat')), {
      method: 'POST',
      headers: withCorrelationHeaders({ Accept: 'text/event-stream', 'Content-Type': 'application/json', ...options.headers }),
      body: JSON.stringify(request),
      credentials: 'include',
      signal
    })
  );
  if (!res.ok) throw new ApiError(await problemFrom(res));
  if (!res.body) return;
  for await (const msg of readSse(res.body)) {
    const e = toLolaEvent(msg);
    if (e) onEvent(e);
  }
}
