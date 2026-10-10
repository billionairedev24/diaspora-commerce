/* Lola (design: LOLA chat). The BFF has no assistant route yet, so replies come from these on-device intents, matching
   the prototype; product cards reference catalogue ATINs and resolve through the BFF. */

export interface LolaReply {
  text: string;
  atins?: string[];
  action?: { label: string; href: string };
}

export const LOLA_PROMPTS = ['Jollof for 10 people', 'Where is my order?', 'Gift under $30', 'Return an item', 'Book a braider'];

export function lolaGreeting(first: string): string {
  return `Hi ${first}! I’m Lola. Ask me for a recipe basket, a product, gift ideas, your delivery or a return — or tap a suggestion below.`;
}

export function lolaReply(input: string): LolaReply {
  const t = input.toLowerCase();
  if (/jollof|party|recipe|cook/.test(t)) return { text: 'For party jollof for 10: 2 lb basmati, a jar of seasoning, palm oil and pepper sauce. All four arrive today by 8 pm.', atins: ['ATBASMAT05', 'ATJOLLOF02', 'ATPALMOI03', 'ATSHITO001'] };
  if (/where|track|order|deliver/.test(t)) return { text: 'Your latest order is on its way — open tracking for the live status, your driver and the delivery code.', action: { label: 'Track delivery', href: '/orders' } };
  if (/gift|present|birthday/.test(t)) return { text: 'Gift ideas under $30: a kente headwrap with black soap ($27.49), or suya spice with shito ($15.48).', atins: ['ATKENTE008', 'ATSOAP0007', 'ATSUYA0006'] };
  if (/return|refund|damaged|wrong/.test(t)) return { text: 'Sorry about that. Pick the items and a reason, and I’ll generate a free UPS QR code — refund lands once it’s scanned.', action: { label: 'Start a return', href: '/orders' } };
  if (/braid|hair|tailor|cater|book/.test(t)) return { text: 'There are verified pros near you with open slots this week. A deposit holds your booking.', action: { label: 'See pros', href: '/services' } };
  if (/^(hi|hello|hey)\b/.test(t)) return { text: 'Hey! What are we shopping for today?' };
  return { text: 'I couldn’t find that yet. I can build recipe baskets, find products, track orders, start returns or book a pro.', action: { label: 'Search the shop', href: `/search?q=${encodeURIComponent(input.trim())}` } };
}
