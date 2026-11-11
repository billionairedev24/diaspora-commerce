'use client';

import { type ListingRules } from '@afrotransact/api-client';
import { CheckCircle, Circle } from '@phosphor-icons/react/ssr';

import { useT } from '@/i18n/t';

import { type QualityItem } from '../form-model';

interface Props {
  items: QualityItem[];
  rules: ListingRules | undefined;
  /** Section titles, for the groups. */
  onGo: (id: string) => void;
  labelOf: (item: QualityItem) => string;
}

/** What is required, in one flat list at the top of the form: every required item of this category with a check when done. Each opens its field. */
export function RequiredSummary({ items, rules, onGo, labelOf }: Props) {
  const t = useT();
  const required = items.filter((i) => i.required);
  const done = required.filter((i) => i.done).length;
  return (
    <section aria-labelledby="pf-required-h" data-testid="required-summary" className="flex flex-col gap-2 border-b border-border pb-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="pf-required-h" className="m-0 text-base font-semibold">{t('sc.pf.required.title')}</h2>
        {rules ? <span className="text-xs text-muted">{rules.kindName} · {t('sc.pf.required.progress', { done, total: required.length })}</span> : null}
      </div>
      {rules ? (
        <ul className="m-0 grid list-none grid-cols-1 gap-x-6 gap-y-1 p-0 sm:grid-cols-2 xl:grid-cols-3">
          {required.map((i) => (
            <li key={`${i.id}-${i.section}`} data-done={i.done}>
              <button type="button" onClick={() => onGo(i.id)} className="flex w-full items-center gap-1.5 border-0 bg-transparent p-0 text-left text-sm hover:underline">
                {i.done ? <CheckCircle size={16} weight="fill" className="flex-none text-success" aria-hidden /> : <Circle size={16} className="flex-none text-muted" aria-hidden />}
                <span className={i.done ? 'text-muted' : undefined}>{labelOf(i)}</span>
                <span className="sr-only">{i.done ? ' (done)' : ' (to do)'}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : <p className="m-0 text-sm text-muted">{t('sc.pf.required.none')}</p>}
    </section>
  );
}
