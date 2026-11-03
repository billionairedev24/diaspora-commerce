/* Mock gateway-BFF pre-order endpoints for the storefront (S273): resolve, open list, waitlist, the cart's door/pickup
   choice, and the dev-only preview the storefront's "Preview pre-order city" control drives. */
import { http } from 'msw';
import { z } from 'zod';

import { PreorderFallbackSchema, PreorderFulfilmentRequestSchema, PreorderPlaceSchema, PreorderStageSchema, PreorderWaitlistLeaveRequestSchema, PreorderWaitlistRequestSchema } from '../preorders';
import { body, json, route } from './http-kit';
import { openPreorders, placeOf, preorderState, resolvePreorder } from './preorders';
import { buildCart, MockProblem } from './service';

const PreviewSchema = z.object({
  place: PreorderPlaceSchema.nullable().optional(),
  stage: PreorderStageSchema.nullable().optional(),
  enabled: z.boolean().nullable().optional(),
  fallback: PreorderFallbackSchema.nullable().optional(),
  run: z.object({ minOrders: z.number().int().min(0), ordersSoFar: z.number().int().min(0) }).nullable().optional()
});

export const preorderHandlers = [
  http.get('*/v1/preorders/resolve', route((ctx) => {
    const q = new URL(ctx.request.url).searchParams;
    const country = q.get('country');
    const region = q.get('region');
    const city = q.get('city');
    const explicit = country && region && city ? { country, region, city } : null;
    return json(resolvePreorder(ctx.session, explicit, ctx.now.getTime()));
  })),
  http.get('*/v1/preorders/open', route((ctx) => json(openPreorders(ctx.now.getTime(), ctx.session.preorderPreview?.enabled)))),
  http.post('*/v1/preorders/waitlist', route(async (ctx) => {
    const req = await body(ctx.request, PreorderWaitlistRequestSchema);
    const place = req.place ?? placeOf(ctx.session);
    const key = `${place.country}/${place.region}/${place.city}`;
    const st = preorderState(ctx.now.getTime());
    if (!st.waitlist.some((w) => w.email === req.email && w.key === key)) st.waitlist.push({ email: req.email, key });
    return json({ status: 'subscribed', city: place.city }, 201);
  })),
  /* Unsubscribe link of a waitlist email. Mock tokens: anything of 8+ characters works; `invalid…` acts like a token
     delivery-runs does not know (422 on token), as the real BFF answers. */
  http.post('*/v1/preorders/waitlist/unsubscribe', route(async (ctx) => {
    const req = await body(ctx.request, PreorderWaitlistLeaveRequestSchema);
    if (req.token.length < 8 || req.token.startsWith('invalid'))
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'This unsubscribe link is not valid.', { errors: { token: 'This unsubscribe link is not valid or was already replaced' } });
    return json({ status: 'unsubscribed' });
  })),
  http.put('*/v1/cart/preorder-fulfilment', route(async (ctx) => {
    const req = await body(ctx.request, PreorderFulfilmentRequestSchema);
    const cart = buildCart(ctx.session, ctx.now);
    const pkg = cart.packages.find((p) => p.preorder?.windowId === req.windowId);
    if (!pkg?.preorder) throw new MockProblem(409, 'PREORDER_NOT_IN_CART', 'No pre-order in your cart', 'Your cart has no items in that pre-order.');
    if (!pkg.preorder.options.some((o) => o.fulfilment === req.fulfilment))
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Not offered', 'That delivery choice isn’t offered for this pre-order.', { errors: { fulfilment: 'Not offered for this pre-order' } });
    ctx.session.preorderFulfilment = req.fulfilment;
    return json(buildCart(ctx.session, ctx.now));
  })),

  /* Dev / mocks only (never in the real BFF): "Preview pre-order city" — place, stage, master switch and fallback. */
  http.get('*/v1/mock/preorder-preview', route((ctx) => json(ctx.session.preorderPreview ?? {}))),
  http.put('*/v1/mock/preorder-preview', route(async (ctx) => {
    const p = await body(ctx.request, PreviewSchema);
    ctx.session.preorderPreview = {
      ...(p.place ? { place: p.place } : {}),
      ...(p.stage ? { stage: p.stage } : {}),
      ...(typeof p.enabled === 'boolean' ? { enabled: p.enabled } : {}),
      ...(p.fallback ? { fallback: p.fallback } : {}),
      ...(p.run ? { run: p.run } : {})
    };
    return json(ctx.session.preorderPreview);
  }))
];
