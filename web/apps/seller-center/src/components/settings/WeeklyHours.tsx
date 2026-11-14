'use client';

import { useI18n } from '@afrotransact/i18n/react';
import { Button, cx, fieldStyles } from '@afrotransact/ui';

import { SwitchOnly } from '@/components/ui/Switch';
import { useT } from '@/i18n/t';
import { copyToWeekdays, type DayRow, sameEveryDay, sourceRow, timeOptions } from '@/lib/weekly-hours';

interface TimeSelectProps {
  label: string;
  placeholder: string;
  value: string;
  locale: string;
  invalid: boolean;
  disabled: boolean;
  onChange: (v: string) => void;
}

/** A native select in 30-minute steps. Not a Field: no label or message row, so its height is exactly the control height. */
function TimeSelect({ label, placeholder, value, locale, invalid, disabled, onChange }: TimeSelectProps) {
  return (
    <div className="min-w-0 flex-1 sm:w-36 sm:flex-none">
      <select
        aria-label={label}
        aria-invalid={invalid ? true : undefined}
        className={fieldStyles.control}
        disabled={disabled}
        value={value}
        onChange={(e) => {
          const v = e.currentTarget.value;
          onChange(v);
        }}
      >
        <option value="">{placeholder}</option>
        {timeOptions(value, locale).map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * Opening hours as a week: Monday to Sunday, one row each, with an open/closed switch and one "from" and "to" on the same row.
 * Every control in a row is the same height and shares a vertical centre. Nothing is filled in until the seller opens a day.
 */
export function WeeklyHours({ week, onChange, disabled, invalidDays = [], legend }: { week: DayRow[]; onChange: (week: DayRow[]) => void; disabled: boolean; invalidDays?: DayRow['day'][]; legend: string }) {
  const t = useT();
  const { locale } = useI18n();
  const set = (day: DayRow['day'], patch: Partial<DayRow>) => onChange(week.map((r) => (r.day === day ? { ...r, ...patch } : r)));
  const canCopy = sourceRow(week) !== null;
  return (
    <div role="group" aria-label={legend} className="flex flex-col gap-2" data-testid="weekly-hours">
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {week.map((r) => {
          const full = t(`sc.day.${r.day}`);
          const bad = invalidDays.includes(r.day);
          return (
            <li key={r.day} className="flex min-h-(--at-size-control) items-center gap-2" data-testid="weekly-row" data-day={r.day}>
              <SwitchOnly ariaLabel={full} checked={r.open} disabled={disabled} onChange={(open) => set(r.day, { open })} />
              <span className={cx('w-10 flex-none text-sm font-semibold', !r.open && 'text-muted')} aria-hidden>
                {t(`sc.dayShort.${r.day}`)}
              </span>
              {r.open ? (
                <>
                  <TimeSelect label={t('sc.weekly.fromDay', { day: full })} placeholder={t('sc.weekly.from')} value={r.from} locale={locale} invalid={bad && (r.from === '' || r.from >= r.to)} disabled={disabled} onChange={(from) => set(r.day, { from })} />
                  <span aria-hidden className="flex-none text-muted">–</span>
                  <TimeSelect label={t('sc.weekly.toDay', { day: full })} placeholder={t('sc.weekly.to')} value={r.to} locale={locale} invalid={bad} disabled={disabled} onChange={(to) => set(r.day, { to })} />
                </>
              ) : (
                <span className="text-sm text-muted">{t('sc.weekly.closed')}</span>
              )}
            </li>
          );
        })}
      </ul>
      {!disabled ? (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="neutral" disabled={!canCopy} onClick={() => onChange(copyToWeekdays(week))}>
            {t('sc.weekly.copyWeekdays')}
          </Button>
          <Button size="sm" variant="neutral" disabled={!canCopy} onClick={() => onChange(sameEveryDay(week))}>
            {t('sc.weekly.sameEveryDay')}
          </Button>
        </div>
      ) : null}
    </div>
  );
}
