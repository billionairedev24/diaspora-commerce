/* Mock Lola (ai-gateway through the BFF): one turn as Server-Sent Events in ai-gateway's order — tool_call,
   tool_result, action_proposal, token…, handoff?, done — grounded in the mock catalogue and services. */
import { SearchQuerySchema } from '../schemas/search';
import { type LolaChatRequest } from '../schemas/shopper';
import { type MockSession } from './db';
import { metroOf } from './delivery';
import { search, sessionLocation } from './service';
import { SERVICES } from './shopper-state';

type Emit = (event: string, data: unknown) => void;

const SUGGESTIONS = ['Jollof for 20', 'Diwali dinner for 8', 'Track my order', 'Book knotless braids'];
const STOP = new Set(['a', 'an', 'the', 'for', 'me', 'i', 'need', 'want', 'some', 'find', 'show', 'buy', 'get', 'please', 'add', 'to', 'cart', 'my', 'of', 'and', 'with', 'plan', 'what', 'do']);

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

let sessions = 0;

function turn(req: LolaChatRequest, session: MockSession, now: Date, emit: Emit): void {
  const text = req.message.toLowerCase();
  const sessionId = req.sessionId ?? `lola_mock_${(++sessions).toString().padStart(8, '0')}`;
  const proposals: unknown[] = [];
  let products: unknown[] = [];
  let answer: string;
  const propose = (p: Record<string, unknown>) => {
    const proposal = { requiresConfirmation: true, ...p };
    proposals.push(proposal);
    emit('tool_result', { id: `t${proposals.length}`, tool: 'proposal', kind: 'proposal', data: proposal });
    emit('action_proposal', { proposal });
  };
  if (/\b(human|agent|person|support)\b/.test(text)) {
    emit('handoff', { handoffId: `ho_${sessionId}`, reason: 'customer-request', summary: req.message.slice(0, 140) });
    answer = 'I’ve passed our chat to the support team with the context. An agent will join shortly.';
  } else if (/\b(return|refund)\b/.test(text)) {
    emit('tool_call', { id: 't1', tool: 'start_return', label: 'Looking at your delivered orders', arguments: {} });
    propose({ id: 'p_return', type: 'START_RETURN', label: 'Start a return for order AT-2026-104221', payload: { orderId: 'AT-2026-104221', reason: 'DAMAGED', items: [] } });
    answer = 'Your most recent delivered order is AT-2026-104221. Tap the button to choose the items and a reason, and you’ll see the refund before you confirm.';
  } else if (/\b(track|where)\b/.test(text)) {
    emit('tool_call', { id: 't1', tool: 'track_order', label: 'Checking your latest order', arguments: {} });
    emit('tool_result', { id: 't1', tool: 'track_order', kind: 'order', data: { orderId: 'AT-2026-104221', availability: 'FOUND', status: 'delivered' } });
    answer = 'Order AT-2026-104221 was delivered. You can see every step under Your orders.';
  } else if (/\b(checkout|pay)\b/.test(text)) {
    propose({ id: 'p_checkout', type: 'CHECKOUT', label: 'Go to checkout', payload: {} });
    answer = 'Your cart is ready. Tap “Go to checkout” when you are.';
  } else if (/\b(book|braid|hair|tailor|cater|tax|appointment)\b/.test(text)) {
    emit('tool_call', { id: 't1', tool: 'find_service', label: 'Looking for services near you', arguments: { zip: req.zip ?? session.zip } });
    const svc = SERVICES.find((s) => text.split(/\W+/).some((w) => w.length > 3 && `${s.name} ${s.category}`.toLowerCase().includes(w))) ?? SERVICES[0];
    if (svc) {
      emit('tool_result', { id: 't1', tool: 'find_service', kind: 'services', data: { services: [{ serviceId: svc.id, name: svc.name, provider: svc.providerName, priceFromMinor: svc.priceMinor }] } });
      propose({ id: 'p_book', type: 'BOOK_SERVICE', label: `Book ${svc.name}`, payload: { serviceId: svc.id, preferredTime: '', notes: '' } });
      answer = `${svc.providerName} offers ${svc.name} from $${(svc.priceMinor / 100).toFixed(2)} with a $${(svc.depositMinor / 100).toFixed(2)} deposit. Tap to pick a time.`;
    } else answer = 'I couldn’t find a provider for that near you yet.';
  } else {
    const terms = text.split(/[^a-z]+/).filter((w) => w.length > 2 && !STOP.has(w) && !/^\d+$/.test(w));
    const loc = sessionLocation(session);
    emit('tool_call', { id: 't1', tool: 'search_products', label: `Searching for ${terms.join(' ') || 'popular items'}`, arguments: { query: terms.join(' ') } });
    const seen = new Set<string>();
    const hits = (terms.length ? terms : ['jollof'])
      .flatMap((term) => search(SearchQuerySchema.parse({ q: term, size: 3 }), loc, now).hits)
      .filter((h) => h.inStock && !seen.has(h.atin) && seen.add(h.atin))
      .slice(0, 4);
    products = hits.map((h) => ({ atin: h.atin, title: h.title, brand: h.brand, sellerName: h.sellerName, priceMinor: h.priceMinor, currency: 'USD', inStock: h.inStock,
      availableQty: h.availableQty, lowStock: h.lowStock, deliveryDays: h.delivery.days, rating: h.rating, imageUrl: null, offerId: h.offerId }));
    emit('tool_result', { id: 't1', tool: 'search_products', kind: 'products', data: { products, metro: metroOf(loc) } });
    if (hits.length) {
      propose({ id: 'p_cart', type: 'ADD_TO_CART', label: hits.length > 1 ? 'Add all to cart' : `Add 1 × ${hits[0]?.title} to cart`,
        payload: { lines: hits.map((h) => ({ variantAtin: h.atin, offerId: h.offerId, quantity: 1, title: h.title, priceMinor: h.priceMinor, currency: 'USD' })) } });
      answer = `I found ${hits.length} item${hits.length > 1 ? 's' : ''} for you, all in stock and delivering to ${loc.label}. Add them all with one tap, or pick what you like.`;
    } else answer = 'I couldn’t find that in the catalogue yet. Try another name — gari, egusi, bissap and suya all work.';
  }
  for (const piece of answer.match(/\S+\s*/g) ?? []) emit('token', { text: piece });
  emit('done', { sessionId, message: answer, products, proposals, suggestions: SUGGESTIONS.filter((s) => s.toLowerCase() !== text).slice(0, 3), dropped: [], models: ['mock'], promptTokens: 0, completionTokens: 0 });
}

/** The SSE body of one mock turn; events are paced like a streamed model answer. */
export function lolaStream(req: LolaChatRequest, session: MockSession, now: Date, delayMs = 25): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  const events: Array<[string, unknown]> = [];
  turn(req, session, now, (event, data) => events.push([event, data]));
  return new ReadableStream<Uint8Array>({
    async start(controller) {
      for (const [event, data] of events) {
        controller.enqueue(enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
        if (delayMs > 0) await sleep(event === 'token' ? delayMs : delayMs * 4);
      }
      controller.close();
    }
  });
}
