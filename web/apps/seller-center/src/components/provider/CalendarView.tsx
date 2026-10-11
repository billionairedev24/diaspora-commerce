'use client';

import { type BookingCard, type DaySummary, sellerQueries } from '@afrotransact/api-client';
import { Button, ChipGroup, cx } from '@afrotransact/ui';
import { CaretLeft, CaretRight } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { isoDay, minutesLabel, money, time } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { BookingActions } from './BookingActions';

const START_H = 7;
const END_H = 22;
const HOUR_PX = 44;
const DAY_KEYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const;
const ACTIVE = ['REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_PROPOSED'];

const startOfWeek = (d: Date) => {
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  x.setDate(x.getDate() - ((x.getDay() + 6) % 7));
  return x;
};
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));

/** Day summary shown on hover/focus: appointments, hours booked, expected revenue and what needs action. */
export function DaySummaryCard({ s }: { s: DaySummary | undefined }) {
  if (!s) return null;
  return (
    <span className="flex flex-col gap-0.5 text-xs">
      <strong>{s.appointments} appointment{s.appointments === 1 ? '' : 's'}</strong>
      <span>{minutesLabel(s.bookedMinutes)} booked · {money(s.expectedRevenueMinor)} expected</span>
      {s.needsAction ? <span className="font-semibold text-warning">{s.needsAction} waiting on you</span> : null}
    </span>
  );
}

/** Booking quick view: customer, time, price, deposit status and note, with Confirm and the other actions. */
function QuickView({ b }: { b: BookingCard }) {
  return (
    <div className="flex flex-col gap-1 text-xs">
      <strong className="text-sm">{b.customerName}</strong>
      <span>{b.serviceName}</span>
      <span>{time(b.startAt)} – {time(b.endAt)} · {money(b.priceMinor, b.currency)}</span>
      <span>Deposit {money(b.depositMinor, b.currency)} · {b.depositState}</span>
      <span className="font-semibold">{b.statusLabel}</span>
      {b.note ? <span className="text-muted">“{b.note}”</span> : null}
      <BookingActions booking={b} compact />
    </div>
  );
}

const TONE: Record<string, string> = {
  REQUESTED: 'border-gold-500 bg-gold-50', RESCHEDULE_REQUESTED: 'border-gold-500 bg-gold-50', RESCHEDULE_PROPOSED: 'border-gold-500 bg-gold-50',
  CONFIRMED: 'border-green-200 bg-green-50', COMPLETED: 'border-border bg-surface-muted', NO_SHOW: 'border-red-100 bg-danger-bg'
};

/** Calendar: week view (hours down the side, open hours shaded, now line) and month view, with quick views and day summaries. */
export function CalendarView() {
  const { orgId } = useSeller();
  const [mode, setMode] = useState<'week' | 'month'>('week');
  const [anchor, setAnchor] = useState(() => new Date());
  const [selected, setSelected] = useState<string | null>(null);
  const range = useMemo(() => {
    if (mode === 'week') {
      const s = startOfWeek(anchor);
      return { from: s, to: addDays(s, 6) };
    }
    const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const s = startOfWeek(first);
    return { from: s, to: addDays(s, 41) };
  }, [mode, anchor]);
  const q = useQuery(sellerQueries.calendar(sellerApi(), orgId, isoDay(range.from), isoDay(range.to)));
  const days = Array.from({ length: mode === 'week' ? 7 : 42 }, (_, i) => addDays(range.from, i));
  const byDay = (d: Date) => (q.data?.bookings ?? []).filter((b) => isoDay(new Date(b.startAt)) === isoDay(d));
  const summary = (d: Date) => q.data?.days.find((s) => s.date === isoDay(d));
  const now = new Date();
  const sel = q.data?.bookings.find((b) => b.id === selected) ?? null;
  const step = (n: number) => setAnchor(mode === 'week' ? addDays(anchor, 7 * n) : new Date(anchor.getFullYear(), anchor.getMonth() + n, 1));
  const title = mode === 'week'
    ? `${range.from.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} – ${range.to.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}`
    : anchor.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  return (
    <ModulePage module="calendar">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="neutral" aria-label="Previous" onClick={() => step(-1)}><CaretLeft size={16} aria-hidden /></Button>
        <Button size="sm" variant="neutral" onClick={() => setAnchor(new Date())}>Today</Button>
        <Button size="sm" variant="neutral" aria-label="Next" onClick={() => step(1)}><CaretRight size={16} aria-hidden /></Button>
        <strong className="flex-1 text-lg" aria-live="polite">{title}</strong>
        <ChipGroup label="View" value={mode} onChange={setMode} options={[{ value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]} />
      </div>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && mode === 'week' ? (
        <div className="grid grid-cols-[48px_repeat(7,minmax(0,1fr))] rounded-md border border-border text-xs" aria-label="Week calendar" role="grid">
          {/* Rows use display: contents so the cells stay in the CSS grid (ARIA grid > row > cell, WCAG 1.3.1). */}
          <div role="row" className="contents">
          <div role="columnheader"><span className="sr-only">Time</span></div>
          {days.map((d) => (
            <div key={d.toISOString()} role="columnheader" tabIndex={0} className="group relative border-l border-border px-1 py-2 text-center outline-none">
              <span className={cx('font-semibold', isoDay(d) === isoDay(now) && 'rounded-pill bg-gold-500 px-2')}>{d.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric' })}</span>
              <span className="pointer-events-none absolute top-full left-1/2 z-20 hidden w-48 -translate-x-1/2 rounded-sm border border-border bg-surface p-2 text-left shadow-md group-hover:block group-focus:block">
                <DaySummaryCard s={summary(d)} />
              </span>
            </div>
          ))}
          </div>
          <div role="row" className="contents">
          <div role="rowheader" aria-label="Hours" className="relative" style={{ height: (END_H - START_H) * HOUR_PX }}>
            {Array.from({ length: END_H - START_H }, (_, i) => (
              <span key={i} aria-hidden className="absolute right-1 text-2xs text-muted" style={{ top: i * HOUR_PX - 6 }}>{((START_H + i + 11) % 12) + 1}{START_H + i < 12 ? 'a' : 'p'}</span>
            ))}
          </div>
          {days.map((d) => {
            const hours = q.data.openHours.find((h) => h.day === DAY_KEYS[d.getDay()]);
            return (
              <div key={d.toISOString()} role="gridcell" aria-label={d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })} className="relative border-t border-l border-border" style={{ height: (END_H - START_H) * HOUR_PX }}>
                {hours?.ranges.map((r) => (
                  <span key={r.start} aria-hidden className="absolute inset-x-0 bg-surface-sunken" style={{ top: ((toMin(r.start) - START_H * 60) / 60) * HOUR_PX, height: ((toMin(r.end) - toMin(r.start)) / 60) * HOUR_PX }} />
                ))}
                {isoDay(d) === isoDay(now) ? <span aria-hidden className="absolute inset-x-0 z-10 h-0.5 bg-danger" style={{ top: ((now.getHours() * 60 + now.getMinutes() - START_H * 60) / 60) * HOUR_PX }} /> : null}
                {byDay(d).map((b) => {
                  const s = new Date(b.startAt);
                  const top = ((s.getHours() * 60 + s.getMinutes() - START_H * 60) / 60) * HOUR_PX;
                  return (
                    <div key={b.id} className="group absolute inset-x-0.5 z-10" style={{ top, height: Math.max(24, (b.durationMinutes / 60) * HOUR_PX - 2) }}>
                      <button type="button" onClick={() => setSelected(b.id)} className={cx('h-full w-full overflow-hidden rounded-xs border-l-4 px-1 text-left text-2xs', TONE[b.status] ?? 'border-border bg-surface-muted', !ACTIVE.includes(b.status) && 'opacity-60')}>
                        <strong className="block truncate">{time(b.startAt)} {b.customerName}</strong>
                        <span className="block truncate">{b.serviceName}</span>
                      </button>
                      <div className="absolute top-0 left-full z-30 ml-1 hidden w-60 rounded-sm border border-border bg-surface p-3 shadow-md group-focus-within:block group-hover:block">
                        <QuickView b={b} />
                      </div>
                    </div>
                  );
                })}
              </div>
            );
          })}
          </div>
        </div>
      ) : null}
      {q.data && mode === 'month' ? (
        <div className="grid grid-cols-7 overflow-hidden rounded-md border border-border text-xs" role="grid" aria-label="Month calendar">
          <div role="row" className="contents">
            {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => <div key={d} role="columnheader" className="bg-surface-muted p-2 font-semibold">{d}</div>)}
          </div>
          {Array.from({ length: Math.ceil(days.length / 7) }, (_, w) => days.slice(w * 7, w * 7 + 7)).map((week) => (
          <div key={week[0]?.toISOString()} role="row" className="contents">
          {week.map((d) => {
            const s = summary(d);
            const list = byDay(d);
            return (
              <div key={d.toISOString()} role="gridcell" tabIndex={0} className={cx('group relative min-h-20 border-t border-l border-border p-1.5 outline-none', d.getMonth() !== anchor.getMonth() && 'bg-surface-sunken text-muted')}>
                <span className={cx('font-semibold', isoDay(d) === isoDay(now) && 'rounded-pill bg-gold-500 px-1.5')}>{d.getDate()}</span>
                {list.slice(0, 3).map((b) => (
                  <button key={b.id} type="button" onClick={() => setSelected(b.id)} className={cx('mt-0.5 block min-h-6 w-full truncate rounded-xs border-l-4 px-1 text-left text-2xs', TONE[b.status] ?? 'border-border bg-surface-muted')}>
                    {time(b.startAt)} {b.customerName}
                  </button>
                ))}
                {list.length > 3 ? <span className="text-2xs text-muted">+{list.length - 3} more</span> : null}
                {s && s.appointments ? (
                  <span className="pointer-events-none absolute top-full left-0 z-20 hidden w-48 rounded-sm border border-border bg-surface p-2 shadow-md group-hover:block group-focus:block"><DaySummaryCard s={s} /></span>
                ) : null}
              </div>
            );
          })}
          </div>
          ))}
        </div>
      ) : null}
      {sel ? (
        <section aria-label="Booking" className="flex flex-col gap-2 rounded-md border border-border p-4">
          <div className="flex items-center gap-2"><strong className="flex-1">{sel.reference}</strong><Button size="sm" variant="link" onClick={() => setSelected(null)}>Close</Button></div>
          <QuickView b={sel} />
        </section>
      ) : null}
      {q.data ? <p className="m-0 text-xs text-muted">Times shown in {q.data.timezone.replace('America/', '').replace('_', ' ')} time. Shaded = open hours.</p> : null}
    </ModulePage>
  );
}
