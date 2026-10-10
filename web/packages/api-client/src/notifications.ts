/* Notification template rules shared by the staff console's editor and the mock BFF. They mirror the Go BFF
   (go/services/gateway-bff/internal/domain/staff_notifications.go) and notifications' domain.CountSmsSegments, so the
   editor's live checks agree with what the server enforces on save. */
import { type SmsSegments } from './schemas/staff';

/** Longest SMS a new template version may render to with the key's sample data. */
export const MAX_SMS_SEGMENTS = 3;

const GSM7_BASIC =
  '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&\'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà';
const GSM7_EXTENSION = '^{}\\[~]|€\f';

/** Encoding and segment count of an SMS body: GSM-7 (160 / 153 per part) unless a character is outside GSM 03.38, then UCS-2 (70 / 67). */
export function countSmsSegments(body: string): SmsSegments {
  const chars = [...body];
  let septets = 0;
  let gsm = true;
  for (const c of chars) {
    if (GSM7_BASIC.includes(c)) septets += 1;
    else if (GSM7_EXTENSION.includes(c)) septets += 2;
    else {
      gsm = false;
      break;
    }
  }
  const split = (encoding: SmsSegments['encoding'], units: number, single: number, multi: number): SmsSegments => {
    if (units === 0) return { encoding, characters: 0, units: 0, segments: 0, perSegment: single };
    if (units <= single) return { encoding, characters: chars.length, units, segments: 1, perSegment: single };
    return { encoding, characters: chars.length, units, segments: Math.ceil(units / multi), perSegment: multi };
  };
  return gsm ? split('GSM-7', septets, 160, 153) : split('UCS-2', body.length, 70, 67);
}

const ACTION = /\{\{-?\s*([\s\S]*?)\s*-?\}\}/g;
const FIELD_REF = /(^|[^\w$])(\$?)\.([A-Za-z_][A-Za-z0-9_]*)/g;
const BLOCKS = new Set(['if', 'range', 'with', 'block', 'define']);

/**
 * Top-level data fields a Go template reads ({{.firstName}}, {{if .note}}, {{$.total}}), sorted and unique. Fields
 * inside {{range}} / {{with}} are relative to the element and skipped unless they start at the root ($.).
 */
export function templatePlaceholders(src: string): string[] {
  const out = new Set<string>();
  const stack: string[] = [];
  for (const m of src.matchAll(ACTION)) {
    const action = (m[1] ?? '').trim();
    if (action.startsWith('/*')) continue;
    const word = action.split(/\s+/)[0] ?? '';
    if (word === 'end') {
      stack.pop();
      continue;
    }
    const scoped = stack.includes('range') || stack.includes('with');
    for (const ref of action.matchAll(FIELD_REF)) {
      if (scoped && ref[2] !== '$') continue;
      if (ref[3]) out.add(ref[3]);
    }
    if (BLOCKS.has(word)) stack.push(word);
  }
  return [...out].sort();
}

/** Placeholders of src that the sample data does not carry. */
export function missingPlaceholders(src: string, data: Record<string, unknown>): string[] {
  return templatePlaceholders(src).filter((p) => !(p in data));
}
