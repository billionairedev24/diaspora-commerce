/* Push notifications (S168): deep-link routing and per-category preferences (pure; unit-tested). */

export type NotificationCategory = 'orders' | 'deliveries' | 'bookings' | 'restock' | 'deals' | 'lola';

/** Local per-category switches (Notifications settings). Server-side switches (push/sms/email/marketing) live in
    identity's /v1/me/notification-preferences; the categories below refine "push" on this device. */
export interface CategoryPrefs {
  orders: boolean;
  restock: boolean;
  deals: boolean;
  lola: boolean;
}

export const DEFAULT_CATEGORY_PREFS: CategoryPrefs = { orders: true, restock: true, deals: false, lola: true };

export interface PushData {
  category?: string;
  type?: string;
  orderNumber?: string;
  shipmentId?: string;
  bookingId?: string;
  atin?: string;
  url?: string;
}

/** Route (expo-router href) for a tapped notification. Unknown payloads open the inbox. */
export function routeForNotification(data: PushData): string {
  if (data.url && /^\/[a-z0-9/_-]*$/i.test(data.url)) return data.url;
  const cat = (data.category ?? '').toLowerCase();
  if ((cat === 'orders' || cat === 'deliveries') && data.orderNumber) return `/orders/${encodeURIComponent(data.orderNumber)}`;
  if (cat === 'orders' || cat === 'deliveries') return '/orders';
  if (cat === 'bookings' && data.bookingId) return `/bookings/${encodeURIComponent(data.bookingId)}`;
  if (cat === 'bookings') return '/bookings';
  if (cat === 'restock' && data.atin) return `/product/${encodeURIComponent(data.atin)}`;
  if (cat === 'lola') return '/lola';
  return '/inbox';
}

/** Server switches (identity) relevant to the device. */
export interface ServerPrefs {
  push: boolean;
  marketingOptIn: boolean;
}

/** Whether a received push should be shown, respecting both the server switches and the device categories. */
export function shouldDisplay(category: string | undefined, server: ServerPrefs, local: CategoryPrefs): boolean {
  if (!server.push) return false;
  switch ((category ?? '').toLowerCase()) {
    case 'orders':
    case 'deliveries':
    case 'bookings':
      return local.orders;
    case 'restock':
      return local.restock;
    case 'deals':
      return local.deals && server.marketingOptIn;
    case 'lola':
      return local.lola;
    default:
      return true;
  }
}

const APP_SCHEME = 'com.afrotransact.customer://';

/** Deep links (com.afrotransact.customer://… and the environment's universal links <webUrl>/app/…) → in-app path. */
export function pathFromDeepLink(url: string, webUrl?: string): string | null {
  const universal = webUrl ? `${webUrl.replace(/\/+$/, '')}/app/` : null;
  let rest: string;
  if (url.startsWith(APP_SCHEME)) rest = url.slice(APP_SCHEME.length);
  else if (universal && url.toLowerCase().startsWith(universal.toLowerCase())) rest = url.slice(universal.length);
  else return null;
  const path = `/${(rest.split(/[?#]/)[0] ?? '').replace(/^\/+/, '')}`;
  return /^\/(orders|bookings|product|cart|inbox|lola|services)(\/[^/]+)?$|^\/$/.test(path) ? path : '/';
}
