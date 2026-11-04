/** Seconds as m:ss ("0:45", "4:05"). */
export function clockText(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

interface DurationFormatLike {
  format: (d: { minutes?: number; seconds?: number }) => string;
}
type DurationFormatCtor = new (locale: string, options: { style: 'long' }) => DurationFormatLike;

/** Seconds as words for a screen reader ("45 seconds", "1 minute, 5 seconds"); m:ss where Intl.DurationFormat is missing. */
export function spokenDuration(totalSeconds: number, locale: string): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const Ctor = (Intl as unknown as { DurationFormat?: DurationFormatCtor }).DurationFormat;
  if (Ctor) {
    try {
      const parts = s >= 60 ? { minutes: Math.floor(s / 60), seconds: s % 60 } : { seconds: s };
      return new Ctor(locale, { style: 'long' }).format(parts);
    } catch {
      /* unsupported locale: fall through */
    }
  }
  return clockText(s);
}

/** A wall-clock time in the reader's language ("4:15 PM"). */
export function localTimeText(epochMs: number, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(new Date(epochMs));
  } catch {
    return new Date(epochMs).toLocaleTimeString();
  }
}

/** Replaces `{name}` placeholders in a translated label. */
export function fill(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole);
}
