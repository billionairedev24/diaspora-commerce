/* Minimal JWT payload reader (no verification — the app only reads its own ID/access token claims for display and
   expiry; the BFF and services verify signatures with identity's JWKS). */

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function base64UrlDecode(input: string): string {
  const s = input.replace(/-/g, '+').replace(/_/g, '/');
  let bits = 0;
  let value = 0;
  const bytes: number[] = [];
  for (const ch of s) {
    if (ch === '=') break;
    const idx = B64.indexOf(ch);
    if (idx < 0) throw new Error('Invalid base64');
    value = (value << 6) | idx;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((value >> bits) & 0xff);
    }
  }
  // UTF-8 decode
  let out = '';
  for (let i = 0; i < bytes.length; ) {
    const b = bytes[i] ?? 0;
    if (b < 0x80) { out += String.fromCharCode(b); i += 1; }
    else if (b < 0xe0) { out += String.fromCharCode(((b & 0x1f) << 6) | ((bytes[i + 1] ?? 0) & 0x3f)); i += 2; }
    else if (b < 0xf0) { out += String.fromCharCode(((b & 0x0f) << 12) | (((bytes[i + 1] ?? 0) & 0x3f) << 6) | ((bytes[i + 2] ?? 0) & 0x3f)); i += 3; }
    else {
      const cp = ((b & 0x07) << 18) | (((bytes[i + 1] ?? 0) & 0x3f) << 12) | (((bytes[i + 2] ?? 0) & 0x3f) << 6) | ((bytes[i + 3] ?? 0) & 0x3f);
      out += String.fromCodePoint(cp);
      i += 4;
    }
  }
  return out;
}

export interface Claims {
  sub?: string;
  exp?: number;
  email?: string;
  given_name?: string;
  family_name?: string;
  phone_number?: string;
  roles?: string[];
  perms?: string[];
  pt?: string;
}

export function decodeJwt(token: string): Claims | null {
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const parsed: unknown = JSON.parse(base64UrlDecode(part));
    return parsed && typeof parsed === 'object' ? (parsed as Claims) : null;
  } catch {
    return null;
  }
}

/** True when the token expires within `skewSeconds` (refresh ahead of expiry). */
export function isExpiring(expiresAt: number, nowMs: number, skewSeconds = 60): boolean {
  return expiresAt * 1000 - nowMs <= skewSeconds * 1000;
}

export function initialsOf(first?: string, last?: string): string {
  return `${(first ?? '').charAt(0)}${(last ?? '').charAt(0)}`.toUpperCase() || 'AT';
}
