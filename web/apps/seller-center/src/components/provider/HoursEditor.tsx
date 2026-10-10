'use client';

import { type Availability, AvailabilityFormSchema, type BookingRules, sellerKeys, sellerQueries, type Weekday } from '@afrotransact/api-client';
import { Button, Checkbox, IconButton, TextField, useToast } from '@afrotransact/ui';
import { Plus, X } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

const DAY_LABEL: Record<Weekday, string> = { MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday', SUN: 'Sunday' };

const RULES: { key: Exclude<keyof BookingRules, 'instantBooking' | 'autoAcceptReschedule'>; label: string; hint: string }[] = [
  { key: 'leadMinutes', label: 'Minimum notice (minutes)', hint: 'How soon before a slot it can still be booked' },
  { key: 'horizonDays', label: 'Book up to (days ahead)', hint: '1–365' },
  { key: 'bufferMinutes', label: 'Buffer between bookings (minutes)', hint: 'Clean-up and travel time' },
  { key: 'stepMinutes', label: 'Start times every (minutes)', hint: 'e.g. 30 → 9:00, 9:30, 10:00' },
  { key: 'maxPerDay', label: 'Most bookings per day', hint: '1–50' }
];

/** Working hours: weekly ranges with breaks, blocked dates and booking rules (bookings availability). */
export function HoursEditor() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('hours');
  const q = useQuery(sellerQueries.hours(api, orgId));
  const [form, setForm] = useState<Availability | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [block, setBlock] = useState({ date: '', note: '' });
  useEffect(() => {
    if (q.data && !form) setForm(q.data);
  }, [q.data, form]);
  const save = useMutation({
    mutationFn: (a: Availability) => api.saveWorkingHours(a),
    onSuccess: (a) => {
      qc.setQueryData(sellerKeys.hours(orgId), a);
      void qc.invalidateQueries({ queryKey: sellerKeys.calendars(orgId) });
      setForm(a);
      toast.show('Working hours saved');
    },
    onError: (e) => setErrors([problemMessage(e)])
  });
  if (!form) return <ModulePage module="hours">{q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <Spinner />}</ModulePage>;

  const setDay = (i: number, ranges: Availability['weekly'][number]['ranges']) => setForm({ ...form, weekly: form.weekly.map((d, j) => (j === i ? { ...d, ranges } : d)) });
  const setRule = (k: keyof BookingRules, v: number | boolean) => setForm({ ...form, rules: { ...form.rules, [k]: v } });
  const submit = () => {
    const r = AvailabilityFormSchema.safeParse(form);
    if (!r.success) {
      setErrors(r.error.issues.map((i) => {
        const day = i.path[0] === 'weekly' && typeof i.path[1] === 'number' ? form.weekly[i.path[1]]?.day : undefined;
        return day ? `${DAY_LABEL[day]}: ${i.message}` : `${String(i.path.at(-1) ?? 'Hours')}: ${i.message}`;
      }));
      return;
    }
    setErrors([]);
    save.mutate(r.data);
  };

  return (
    <ModulePage module="hours">
      <p className="m-0 text-sm text-muted">Times are in {form.timezone}.</p>
      {errors.length ? (
        <ul role="alert" className="m-0 flex list-none flex-col gap-1 rounded-md border border-red-100 bg-danger-bg p-3 text-sm">
          {errors.map((e) => <li key={e}>{e}</li>)}
        </ul>
      ) : null}
      <fieldset disabled={!editable} className="m-0 grid items-start gap-4 border-0 p-0 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <Card title="Weekly hours">
          <ul className="m-0 flex list-none flex-col divide-y divide-border p-0">
            {form.weekly.map((d, i) => (
              <li key={d.day} className="flex flex-wrap items-start gap-3 py-3">
                <div className="w-32 flex-none pt-2">
                  <Checkbox label={DAY_LABEL[d.day]} checked={d.ranges.length > 0} onChange={(e) => setDay(i, e.target.checked ? [{ start: '09:00', end: '17:00' }] : [])} />
                </div>
                <div className="flex min-w-0 flex-1 flex-col gap-2">
                  {d.ranges.length === 0 ? <span className="pt-2 text-sm text-muted">Closed</span> : null}
                  {d.ranges.map((r, j) => (
                    <div key={j} className="flex items-center gap-2">
                      <input aria-label={`${DAY_LABEL[d.day]} range ${j + 1} start`} type="time" value={r.start} className="h-9 rounded-sm border border-border px-2"
                        onChange={(e) => setDay(i, d.ranges.map((x, k) => (k === j ? { ...x, start: e.target.value } : x)))} />
                      <span className="text-muted">–</span>
                      <input aria-label={`${DAY_LABEL[d.day]} range ${j + 1} end`} type="time" value={r.end} className="h-9 rounded-sm border border-border px-2"
                        onChange={(e) => setDay(i, d.ranges.map((x, k) => (k === j ? { ...x, end: e.target.value } : x)))} />
                      <IconButton label="Remove range" onClick={() => setDay(i, d.ranges.filter((_, k) => k !== j))}><X size={16} /></IconButton>
                    </div>
                  ))}
                  {d.ranges.length > 0 && d.ranges.length < 4 ? (
                    <button type="button" className="flex items-center gap-1 self-start text-sm font-semibold text-link"
                      onClick={() => {
                        const last = d.ranges.at(-1);
                        setDay(i, [...d.ranges, { start: last?.end ?? '13:00', end: '18:00' }]);
                      }}>
                      <Plus size={14} aria-hidden /> Add hours after a break
                    </button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </Card>
        <div className="flex min-w-0 flex-col gap-4">
          <Card title="Booking rules">
            {RULES.map((r) => (
              <TextField key={r.key} label={r.label} help={r.hint} inputMode="numeric" value={String(form.rules[r.key])}
                onChange={(e) => setRule(r.key, Number(e.target.value.replace(/\D/g, '')) || 0)} />
            ))}
            <Checkbox label="Instant booking (skip my approval)" checked={form.rules.instantBooking} onChange={(e) => setRule('instantBooking', e.target.checked)} />
            <Checkbox label="Accept reschedules automatically when the new time is free" checked={form.rules.autoAcceptReschedule} onChange={(e) => setRule('autoAcceptReschedule', e.target.checked)} />
          </Card>
          <Card title="Blocked dates">
            <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
              {form.blockedDates.length === 0 ? <li className="text-muted">No blocked dates.</li> : null}
              {form.blockedDates.map((b) => (
                <li key={b.date} className="flex items-center gap-2">
                  <span className="flex-1"><strong>{shortDate(b.date)}</strong> {b.note ? `· ${b.note}` : ''}</span>
                  <IconButton label={`Unblock ${b.date}`} onClick={() => setForm({ ...form, blockedDates: form.blockedDates.filter((x) => x.date !== b.date) })}><X size={16} /></IconButton>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-sm font-semibold">Date
                <input type="date" value={block.date} onChange={(e) => setBlock({ ...block, date: e.target.value })} className="h-9 rounded-sm border border-border px-2 font-normal" />
              </label>
              <div className="min-w-32 flex-1"><TextField label="Note" value={block.note} onChange={(e) => setBlock({ ...block, note: e.target.value })} /></div>
              <Button variant="neutral" size="sm" disabled={!block.date || form.blockedDates.some((b) => b.date === block.date)}
                onClick={() => {
                  setForm({ ...form, blockedDates: [...form.blockedDates, { date: block.date, note: block.note.trim() }].sort((a, b) => a.date.localeCompare(b.date)) });
                  setBlock({ date: '', note: '' });
                }}>
                Block date
              </Button>
            </div>
          </Card>
        </div>
      </fieldset>
      {editable ? (
        <div className="flex gap-2">
          <Button onClick={submit} loading={save.isPending}>Save working hours</Button>
          <Button variant="neutral" onClick={() => { setForm(q.data ?? form); setErrors([]); }}>Discard changes</Button>
        </div>
      ) : null}
    </ModulePage>
  );
}
