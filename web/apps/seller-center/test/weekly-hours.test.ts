import { type PickupWindow } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { checkWeek, closedWeek, copyToWeekdays, type DayRow, HALF_HOURS, sameEveryDay, sourceRow, timeLabel, timeOptions, weekOf, windowsOf } from '@/lib/weekly-hours';

const MSG = { none: 'none', missing: 'missing', order: 'order' };
const win = (day: PickupWindow['day'], opens = '09:00', closes = '17:00'): PickupWindow => ({ day, opens, closes });
const setDay = (week: DayRow[], day: DayRow['day'], patch: Partial<DayRow>) => week.map((r) => (r.day === day ? { ...r, ...patch } : r));
const openMon = (from = '09:00', to = '17:00') => setDay(closedWeek(), 'MONDAY', { open: true, from, to });

describe('weekly hours', () => {
  it('is seven closed rows, Monday to Sunday, with nothing prefilled', () => {
    const w = closedWeek();
    expect(w.map((r) => r.day)).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']);
    expect(w.every((r) => !r.open && r.from === '' && r.to === '')).toBe(true);
    expect(windowsOf(w)).toEqual([]);
  });

  it('maps rows to the existing windows model: one window per open day, closed days send none', () => {
    const w = setDay(openMon(), 'SATURDAY', { open: true, from: '10:00', to: '14:00' });
    expect(windowsOf(w)).toEqual([win('MONDAY'), win('SATURDAY', '10:00', '14:00')]);
    expect(windowsOf(setDay(w, 'MONDAY', { open: false }))).toEqual([win('SATURDAY', '10:00', '14:00')]);
  });

  it('reads saved windows back, and keeps a second window on a day so a save never drops it', () => {
    const saved = [win('TUESDAY', '08:00', '12:00'), win('TUESDAY', '14:00', '18:00'), win('SUNDAY', '10:00', '13:00')];
    const w = weekOf(saved);
    expect(w.find((r) => r.day === 'TUESDAY')).toMatchObject({ open: true, from: '08:00', to: '12:00' });
    expect(w.find((r) => r.day === 'MONDAY')?.open).toBe(false);
    expect(windowsOf(w)).toEqual(saved);
  });

  it('copies the first open day to the weekdays only, and to every day', () => {
    const w = setDay(openMon('08:30', '16:00'), 'SUNDAY', { open: true, from: '12:00', to: '13:00' });
    const wd = copyToWeekdays(w);
    expect(wd.filter((r) => r.open).map((r) => r.day)).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SUNDAY']);
    expect(wd.find((r) => r.day === 'FRIDAY')).toMatchObject({ from: '08:30', to: '16:00' });
    expect(wd.find((r) => r.day === 'SATURDAY')?.open).toBe(false);
    expect(wd.find((r) => r.day === 'SUNDAY')).toMatchObject({ from: '12:00', to: '13:00' });
    const all = sameEveryDay(w);
    expect(windowsOf(all)).toHaveLength(7);
    expect(all.every((r) => r.from === '08:30' && r.to === '16:00')).toBe(true);
  });

  it('copies nothing until a day has valid hours', () => {
    expect(sourceRow(closedWeek())).toBeNull();
    expect(copyToWeekdays(closedWeek())).toEqual(closedWeek());
    expect(sourceRow(openMon('09:00', ''))).toBeNull();
    expect(sourceRow(openMon('17:00', '09:00'))).toBeNull();
  });

  it('validates: at least one open day, both times on each, to later than from', () => {
    expect(checkWeek(closedWeek(), MSG)).toEqual({ ok: false, message: 'none', days: [] });
    expect(checkWeek(openMon('09:00', ''), MSG)).toEqual({ ok: false, message: 'missing', days: ['MONDAY'] });
    expect(checkWeek(openMon('17:00', '09:00'), MSG)).toEqual({ ok: false, message: 'order', days: ['MONDAY'] });
    expect(checkWeek(openMon('09:00', '09:00'), MSG).ok).toBe(false);
    expect(checkWeek(openMon(), MSG)).toEqual({ ok: true, message: null, days: [] });
  });

  it('offers 30-minute steps labelled like 9:00 AM, and keeps a saved time that is off the grid', () => {
    expect(HALF_HOURS).toHaveLength(48);
    expect(HALF_HOURS.slice(0, 3)).toEqual(['00:00', '00:30', '01:00']);
    expect(timeLabel('09:00', 'en')).toBe('9:00 AM');
    expect(timeLabel('00:30', 'en')).toBe('12:30 AM');
    expect(timeLabel('12:00', 'en')).toBe('12:00 PM');
    expect(timeLabel('17:30', 'en')).toBe('5:30 PM');
    expect(timeOptions('', 'en')).toHaveLength(48);
    const odd = timeOptions('10:15', 'en');
    expect(odd).toHaveLength(49);
    expect(odd.find((o) => o.value === '10:15')?.label).toBe('10:15 AM');
  });
});
