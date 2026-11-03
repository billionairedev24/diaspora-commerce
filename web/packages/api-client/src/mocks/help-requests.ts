/* Mock BFF for help requests (contracts/openapi/gateway-bff.yaml, tag "Help requests"): evidence photo uploads,
   opening a request on an order of the signed-in account, listing and reading. Staff resolve requests in the staff
   console, so new mock requests stay RECEIVED; the demo account is seeded with one that staff already resolved. */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import {
  CreateHelpRequestSchema,
  HELP_EVIDENCE_MAX_BYTES,
  HELP_EVIDENCE_MAX_FILES,
  HELP_EVIDENCE_TYPES,
  type HelpRequest
} from '../schemas/help-requests';
import { body, json, requireUser, route } from './http-kit';
import { MockProblem } from './service';
import { account, seq } from './shopper-state';

const iso = (d: Date) => d.toISOString();
const me = (userId: string) => account(userId);
const notFound = () => new MockProblem(404, 'NOT_FOUND', 'Request not found', 'We couldn’t find that help request.');

const UploadBodySchema = z.object({ fileName: z.string().min(1).max(200), contentType: z.string(), size: z.number().int() });
/** Request ids by account and Idempotency-Key: a retry returns the request it already created. */
const keys = new Map<string, string>();

export const helpRequestHandlers = [
  http.post('*/v1/me/help-requests/uploads', route(async (ctx) => {
    const user = requireUser(ctx);
    const b = await body(ctx.request, UploadBodySchema);
    const errors: Record<string, string> = {};
    if (!(HELP_EVIDENCE_TYPES as readonly string[]).includes(b.contentType)) errors.contentType = 'Upload a JPEG, PNG, WebP or HEIC photo';
    if (b.size < 1 || b.size > HELP_EVIDENCE_MAX_BYTES) errors.size = 'Each photo must be 10 MB or smaller';
    if (Object.keys(errors).length) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
    const id = `evidence-${seq()}`;
    me(user.id).evidence.set(id, false);
    return json({ assetId: id, maxBytes: HELP_EVIDENCE_MAX_BYTES, upload: { method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-evidence-uploads/${id}`,
      headers: { 'Content-Type': b.contentType }, expiresAt: iso(new Date(ctx.now.getTime() + 15 * 60_000)) } }, 201);
  })),
  http.put('*/mock-evidence-uploads/:id', () => new HttpResponse(null, { status: 200 })),
  http.post('*/v1/me/help-requests/uploads/:assetId/complete', route((ctx, p) => {
    const a = me(requireUser(ctx).id);
    const id = p.assetId ?? '';
    if (!a.evidence.has(id)) throw notFound();
    a.evidence.set(id, true);
    return json({ assetId: id, contentType: 'image/jpeg', bytes: 182_000, status: 'READY' });
  })),

  http.post('*/v1/me/help-requests', route(async (ctx) => {
    const user = requireUser(ctx);
    const a = me(user.id);
    const key = ctx.request.headers.get('idempotency-key') ?? '';
    if (key.length < 16 || key.length > 64) {
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors: { 'Idempotency-Key': 'Send an Idempotency-Key of 16 to 64 characters' } });
    }
    const replay = keys.get(`${user.id}|${key}`);
    const earlier = a.helpRequests.find((r) => r.id === replay);
    if (earlier) return json(earlier, 201);
    const b = await body(ctx.request, CreateHelpRequestSchema);
    const order = a.orders.find((o) => o.orderNumber === b.orderNumber);
    if (!order) throw new MockProblem(404, 'ORDER_NOT_FOUND', 'Order not found', 'We couldn’t find that order.');
    const shipment = b.shipmentId ? order.shipments.find((s) => s.shipmentId === b.shipmentId) : undefined;
    if (b.shipmentId && !shipment) throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'That item isn’t part of this order.', { errors: { shipmentId: 'That item isn’t part of this order' } });
    if (new Set(b.evidenceAssetIds).size !== b.evidenceAssetIds.length || b.evidenceAssetIds.length > HELP_EVIDENCE_MAX_FILES || b.evidenceAssetIds.some((id) => a.evidence.get(id) !== true)) {
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors: { evidenceAssetIds: 'One of the photos wasn’t uploaded completely. Remove it and add it again.' } });
    }
    const n = seq();
    const now = iso(ctx.now);
    const created: HelpRequest = {
      id: `help-${n}`, reference: `CLM-${String(n).padStart(4, '0')}`, orderNumber: order.orderNumber, type: b.type, status: 'RECEIVED',
      statusLabel: 'We’ve received your request', description: b.description, evidenceCount: b.evidenceAssetIds.length,
      claimedMinor: shipment ? shipment.lines.reduce((sum, l) => sum + l.itemsMinor, 0) : order.totalMinor, currency: order.currency, resolution: null, createdAt: now, updatedAt: now
    };
    a.helpRequests.unshift(created);
    keys.set(`${user.id}|${key}`, created.id);
    return json(created, 201);
  })),
  http.get('*/v1/me/help-requests', route((ctx) => json({ items: me(requireUser(ctx).id).helpRequests, nextCursor: null }))),
  http.get('*/v1/me/help-requests/:id', route((ctx, p) => {
    const r = me(requireUser(ctx).id).helpRequests.find((x) => x.id === p.id);
    if (!r) throw notFound();
    return json(r);
  }))
];
