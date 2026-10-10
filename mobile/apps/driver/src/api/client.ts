/* Driver API clients: fulfilment (/v1/driver/**), media (photo uploads) and identity (push devices). In dev mocks
   every request is answered in-app by ./mock/server. */
import { File, UploadType } from 'expo-file-system';

import { env } from '@/config/env';
import { type QueuedEvent } from '@/domain/types';

import { type components as F } from './generated/fulfilment';
import { createJson, type Fetch, type TokenSource } from './http';
import { mapRoute } from './mapRoute';
import { mockFetch } from './mock/server';

export type SyncResult = F['schemas']['SyncResult'];
export type EventResult = F['schemas']['EventResult'];

/** Strips app-only fields (local file URIs) before an event leaves the device. */
export function toWire(e: QueuedEvent): Record<string, unknown> {
  return JSON.parse(JSON.stringify(e, (k, v: unknown) => (k === 'localUri' || k === 'signatureLocalUri' ? undefined : v))) as Record<string, unknown>;
}

export function createDriverApi(tokens: TokenSource, network: Fetch = (r) => fetch(r)) {
  const transport: Fetch = env.mocks ? mockFetch : network;
  const fulfilment = createJson(env.fulfilmentUrl, tokens, transport);
  const media = createJson(env.mediaUrl, tokens, transport);
  const identity = createJson(env.identityApiUrl, tokens, transport);

  return {
    /** Access check; resolves the HTTP status and body (403 problems are expected answers). */
    async me(): Promise<{ status: number; body: unknown }> {
      try {
        return { status: 200, body: await fulfilment<unknown>('/v1/driver/me') };
      } catch (e) {
        if (e && typeof e === 'object' && 'status' in e && 'body' in e) return { status: (e as { status: number }).status, body: (e as { body: unknown }).body };
        throw e;
      }
    },
    routes: async () => (await fulfilment<Array<F['schemas']['Route']>>('/v1/driver/routes')).map(mapRoute),
    sync: (deviceId: string, events: QueuedEvent[]) => fulfilment<SyncResult>('/v1/driver/sync', { method: 'POST', body: { deviceId, events: events.map(toWire) } }),
    sos: (deviceId: string, event: QueuedEvent) => fulfilment<EventResult>('/v1/driver/sos', { method: 'POST', body: { deviceId, event: toWire(event) } }),
    /** media-service direct upload: request → PUT the file → complete. Returns the asset id. */
    async uploadPhoto(localUri: string, purpose: 'proof-photo'): Promise<string> {
      const file = env.mocks ? null : new File(localUri);
      const size = file ? file.size || 1 : 1;
      const contentType = localUri.endsWith('.png') ? 'image/png' : 'image/jpeg';
      const req = await media<{ asset?: { id?: string }; upload?: { method?: string; url?: string; headers?: Record<string, string> } }>('/v1/uploads', {
        method: 'POST',
        body: { purpose, fileName: localUri.split('/').pop() ?? 'photo.jpg', contentType, size }
      });
      const id = req.asset?.id;
      if (!id || !req.upload?.url) throw new Error('Upload not available');
      if (file) {
        const r = await file.upload(req.upload.url, { httpMethod: req.upload.method === 'POST' ? 'POST' : 'PUT', uploadType: UploadType.BINARY_CONTENT, headers: req.upload.headers ?? {} });
        if (r.status >= 300) throw new Error(`Upload failed (${r.status})`);
      }
      await media(`/v1/assets/${encodeURIComponent(id)}/complete`, { method: 'POST' });
      return id;
    },
    registerDevice: (body: { platform: 'ios' | 'android'; token: string; deviceName?: string; provider: 'apns' | 'fcm' }) =>
      identity('/v1/me/devices', { method: 'POST', body: { ...body, app: 'driver' } }),
    unregisterDevice: (token: string) => identity('/v1/me/devices', { method: 'DELETE', body: { token } })
  };
}

export type DriverApi = ReturnType<typeof createDriverApi>;
