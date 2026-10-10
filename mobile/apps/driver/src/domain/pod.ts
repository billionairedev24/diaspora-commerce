/* S085 proof of delivery (pure; unit-tested): handover method rules, GPS distance and the photo stamp. */
import { type HandoverMethod, type PhotoCapture } from './types';

export const POD_MAX_DISTANCE_M = 150;

export const RELATIONSHIPS = ['Family member', 'Neighbour', 'Roommate', 'Front desk / concierge', 'Colleague'] as const;
export const SAFE_PLACES = ['Front door', 'Back porch', 'Garage', 'Mailroom'] as const;

/** Great-circle distance in metres. */
export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6_371_000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return Math.round(2 * R * Math.asin(Math.sqrt(a)));
}

export interface PodInput {
  method: HandoverMethod;
  code: string;
  recipient: string;
  relationship: string;
  signatureLocalUri: string | null;
  safePlace: string;
  photo: PhotoCapture | null;
  perishable: boolean;
  safePlaceAllowed: boolean;
  /** Stop coordinates (null when the route has none: distance then checked by the server only). */
  stop: { lat: number | null; lon: number | null };
}

export interface MethodOption {
  method: HandoverMethod;
  label: string;
  hint: string;
  disabled: boolean;
}

export function handoverOptions(perishable: boolean, safePlaceAllowed: boolean, allowedPlace?: string): MethodOption[] {
  return [
    { method: 'CUSTOMER', label: 'Handed to customer', hint: 'Ask for their 4-digit code', disabled: false },
    { method: 'SOMEONE_ELSE', label: 'Handed to someone else', hint: 'Name, relationship and signature', disabled: false },
    {
      method: 'SAFE_PLACE',
      label: 'Left in a safe place',
      hint: perishable ? 'Not allowed — perishable items' : safePlaceAllowed ? `Customer allowed: ${allowedPlace ?? 'door'} · photo required` : 'Customer didn’t allow safe drop',
      disabled: perishable || !safePlaceAllowed
    }
  ];
}

/** First error (design copy), or null. The handover code itself is verified by orders-service on sync. */
export function podCheck(i: PodInput): string | null {
  if (i.method === 'SAFE_PLACE' && i.perishable) return 'Perishable items must be handed to a person.';
  if (i.method === 'SAFE_PLACE' && !i.safePlaceAllowed) return 'The customer didn’t allow a safe place. Hand it over or report a problem.';
  if (i.method === 'CUSTOMER' && !/^\d{4}$/.test(i.code)) return 'Ask for the 4-digit code from their SMS.';
  if (i.method === 'SOMEONE_ELSE' && (i.recipient.trim().length < 2 || !i.relationship)) return 'Enter who received it and their relationship.';
  if (i.method === 'SOMEONE_ELSE' && !i.signatureLocalUri) return 'Ask the recipient to sign.';
  if (i.method === 'SAFE_PLACE' && !i.safePlace) return 'Choose where you left it.';
  if (!i.photo) return 'Take a proof-of-delivery photo.';
  if (i.photo.source !== 'LIVE_CAMERA') return 'Proof photos must be taken live in the app.';
  if (i.stop.lat !== null && i.stop.lon !== null && i.photo.lat !== null && i.photo.lon !== null) {
    const d = distanceMeters(i.photo.lat, i.photo.lon, i.stop.lat, i.stop.lon);
    if (d > POD_MAX_DISTANCE_M) return `Take the photo at the address — you’re ${d} m away (max ${POD_MAX_DISTANCE_M} m).`;
  }
  return null;
}

/** Text burned into the photo: time · GPS · route id. */
export function stampText(capturedAt: Date, lat: number | null, lon: number | null, routeId: string): string {
  const time = capturedAt.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' });
  const gps = lat !== null && lon !== null ? `${lat.toFixed(5)}, ${lon.toFixed(5)}` : 'GPS unavailable';
  return `${time} · ${gps} · ${routeId}`;
}

export function podSummary(method: HandoverMethod, recipient: string, relationship: string, safePlace: string): string {
  if (method === 'CUSTOMER') return 'Handed to customer · code entered';
  if (method === 'SOMEONE_ELSE') return `Received by ${recipient} (${relationship}) · signed`;
  return `Left at ${safePlace} · photo`;
}
