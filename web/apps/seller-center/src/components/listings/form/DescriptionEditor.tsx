'use client';

import { Button, Field, fieldStyles } from '@afrotransact/ui';
import { Sparkle } from '@phosphor-icons/react/ssr';
import { useMutation } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { fieldId, MAX_DESCRIPTION } from '../form-model';

export interface Suggestion {
  description: string;
  bullets: string[];
}

interface Props {
  value: string;
  onChange: (value: string) => void;
  error?: string;
  /** The AI listing writer: available on the seller's plan, not on the plan (offer the upgrade), or absent. */
  ai: 'available' | 'plan' | 'none';
  /** The writer needs a title to work from. */
  canWrite: boolean;
  write: () => Promise<Suggestion>;
  onFailure: (error: unknown) => void;
}

/** The description: a large editor with the AI writer inline above it; a suggestion is reviewed under the editor before it replaces anything. */
export function DescriptionEditor({ value, onChange, error, ai, canWrite, write, onFailure }: Props) {
  const t = useT();
  const [suggestion, setSuggestion] = useState<Suggestion | null>(null);
  const writer = useMutation({ mutationFn: write, onSuccess: setSuggestion, onError: onFailure });
  return (
    <div className="flex flex-col gap-2">
      {ai === 'available' ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <Button size="sm" variant="neutral" loading={writer.isPending} disabled={!canWrite} onClick={() => writer.mutate()}>
            <Sparkle size={16} aria-hidden /> {t('sc.pf.ai.write')}
          </Button>
          <span className="min-w-0 flex-1 basis-56 text-xs text-muted">{t('sc.pf.ai.hint')}</span>
        </div>
      ) : ai === 'plan' ? (
        <p className="m-0 text-xs text-muted"><Sparkle size={14} aria-hidden /> {t('sc.pf.ai.plan')} <Link href="/billing">{t('sc.pf.ai.upgrade')}</Link></p>
      ) : null}
      <Field id={fieldId.description} label={t('sc.pf.field.description')} error={error} help={t('sc.pf.descriptionHelp', { count: value.length, max: MAX_DESCRIPTION })}>
        {(a11y) => <textarea {...a11y} rows={10} className={`${fieldStyles.control} min-h-56 py-2 sm:min-h-64`} value={value} onChange={(e) => onChange(e.target.value)} />}
      </Field>
      {suggestion ? (
        <div className="flex flex-col gap-2 rounded-sm bg-surface-muted p-3" data-testid="ai-suggestion">
          <p className="m-0 text-sm">{suggestion.description}</p>
          {suggestion.bullets.length ? <ul className="m-0 pl-5 text-sm">{suggestion.bullets.map((b) => <li key={b}>{b}</li>)}</ul> : null}
          <div className="flex gap-2">
            <Button size="sm" onClick={() => { onChange([suggestion.description, ...suggestion.bullets.map((b) => `• ${b}`)].join('\n')); setSuggestion(null); }}>{t('sc.pf.ai.use')}</Button>
            <Button size="sm" variant="neutral" onClick={() => setSuggestion(null)}>{t('sc.pf.ai.discard')}</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
