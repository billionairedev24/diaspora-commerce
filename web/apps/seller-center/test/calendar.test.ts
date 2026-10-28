import { describe, expect, it } from 'vitest';

import { bookingMinutes, visibleHours } from '@/components/provider/CalendarView';

const at = (h: number, m = 0) => {
  const d = new Date(2026, 9, 14, h, m);
  return d.toISOString();
};

describe('week calendar hours', () => {
  it('shows 7 a.m. to 10 p.m. when every booking and open range fits', () => {
    expect(visibleHours([{ startAt: at(10), durationMinutes: 120 }], [{ start: '09:00', end: '18:00' }])).toEqual({ start: 7, end: 22 });
  });

  it('widens to an early booking so its block is drawn inside the grid, never above it', () => {
    expect(visibleHours([{ startAt: at(5, 30), durationMinutes: 90 }])).toEqual({ start: 5, end: 22 });
    expect(visibleHours([], [{ start: '06:00', end: '12:00' }])).toEqual({ start: 6, end: 22 });
  });

  it('widens to a late booking and stops at midnight for one that runs overnight', () => {
    expect(visibleHours([{ startAt: at(21), durationMinutes: 90 }])).toEqual({ start: 7, end: 23 });
    expect(visibleHours([{ startAt: at(22), durationMinutes: 300 }])).toEqual({ start: 7, end: 24 });
    expect(bookingMinutes({ startAt: at(22), durationMinutes: 300 })).toEqual({ start: 22 * 60, end: 24 * 60 });
  });
});
