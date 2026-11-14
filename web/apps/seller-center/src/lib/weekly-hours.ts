import { PICKUP_DAYS, type PickupDay, type PickupWindow, validatePickupHours } from '@afrotransact/api-client';

/**
 * Opening hours as a week: seven rows, Monday to Sunday, each open or closed with one "from" and one "to".
 * The API keeps its model (a list of windows, one or more per day); this maps rows to windows and back:
 * one window per open day, closed days send nothing. Extra windows a day already has on the server are kept
 * (`more`) so a save from this simple list never drops them.
 */
export interface DayRow {
  day: PickupDay;
  open: boolean;
  /** HH:MM, 24 hour; '' until chosen (nothing is filled in by guess). */
  from: string;
  to: string;
  more: PickupWindow[];
}

export const WEEKDAYS: readonly PickupDay[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];

/** All seven days closed. */
export function closedWeek(): DayRow[] {
  return PICKUP_DAYS.map((day) => ({ day, open: false, from: '', to: '', more: [] }));
}

/** The week a saved list of windows describes: a day with a window is open with that window's times. */
export function weekOf(windows: readonly PickupWindow[]): DayRow[] {
  return closedWeek().map((row) => {
    const mine = windows.filter((w) => w.day === row.day);
    const first = mine[0];
    return first ? { ...row, open: true, from: first.opens, to: first.closes, more: mine.slice(1) } : row;
  });
}

/** The windows to send: one per open day (in week order), then any extra windows that day already had. Closed days send none. */
export function windowsOf(week: readonly DayRow[]): PickupWindow[] {
  return week.flatMap((r) => (r.open ? [{ day: r.day, opens: r.from, closes: r.to }, ...r.more] : []));
}

export function sameWeek(a: readonly DayRow[], b: readonly DayRow[]): boolean {
  return JSON.stringify(windowsOf(a)) === JSON.stringify(windowsOf(b));
}

/** Messages for the week's validation, in the seller's language. */
export interface WeekMessages {
  /** No day is open. */
  none: string;
  /** An open day with no times chosen yet. */
  missing: string;
  /** "To" is not later than "from". */
  order: string;
}

export interface WeekCheck {
  ok: boolean;
  /** The first problem, in the order the seller fills the list; null when valid. */
  message: string | null;
  /** Days to mark invalid. */
  days: PickupDay[];
}

/** Needs at least one open day, every open day with a from and a to, and every to later than its from (the server's rules). */
export function checkWeek(week: readonly DayRow[], msg: WeekMessages): WeekCheck {
  const open = week.filter((r) => r.open);
  if (open.length === 0) return { ok: false, message: msg.none, days: [] };
  const missing = open.filter((r) => r.from === '' || r.to === '');
  if (missing.length > 0) return { ok: false, message: msg.missing, days: missing.map((r) => r.day) };
  const errors = validatePickupHours(true, windowsOf(week));
  if (Object.keys(errors).length > 0) {
    const bad = open.filter((r) => r.from >= r.to).map((r) => r.day);
    return { ok: false, message: msg.order, days: bad };
  }
  return { ok: true, message: null, days: [] };
}

/** The first open day that has both times: what "Copy to weekdays" and "Same every day" copy from. */
export function sourceRow(week: readonly DayRow[]): DayRow | null {
  return week.find((r) => r.open && r.from !== '' && r.to !== '' && r.from < r.to) ?? null;
}

function copyTo(week: readonly DayRow[], days: readonly PickupDay[]): DayRow[] {
  const src = sourceRow(week);
  if (!src) return [...week];
  return week.map((r) => (days.includes(r.day) && r.day !== src.day ? { ...r, open: true, from: src.from, to: src.to, more: [] } : r));
}

/** Monday to Friday get the first open day's hours; Saturday and Sunday stay as they are. */
export function copyToWeekdays(week: readonly DayRow[]): DayRow[] {
  return copyTo(week, WEEKDAYS);
}

/** Every day gets the first open day's hours. */
export function sameEveryDay(week: readonly DayRow[]): DayRow[] {
  return copyTo(week, PICKUP_DAYS);
}

/** "HH:MM" every 30 minutes from 00:00 to 23:30. */
export const HALF_HOURS: readonly string[] = Array.from({ length: 48 }, (_, i) => `${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 === 0 ? '00' : '30'}`);

/** "9:00 AM" in English (a plain space: Intl uses a narrow no-break space); other languages use their own clock format. */
export function timeLabel(hhmm: string, locale: string): string {
  const [h = 0, m = 0] = hhmm.split(':').map(Number);
  if (locale === 'en' || locale.startsWith('en-')) return `${h % 12 === 0 ? 12 : h % 12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(new Date(Date.UTC(2000, 0, 1, h, m)));
}

/** The choices for a time select: the 30-minute steps, plus a saved time that is not on the grid so it still shows. */
export function timeOptions(current: string, locale: string): { value: string; label: string }[] {
  const times = current !== '' && !HALF_HOURS.includes(current) ? [...HALF_HOURS, current].sort() : HALF_HOURS;
  return times.map((v) => ({ value: v, label: timeLabel(v, locale) }));
}

/** How soon after an order it is ready to collect: hours, as the API keeps them. */
export const READY_CHOICES = [0, 1, 2, 4, 24] as const;
