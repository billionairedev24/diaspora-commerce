'use client';

import { isApiError, type NotificationTemplateDetail, staffKeys, staffQueries } from '@afrotransact/api-client';
import { Button, Field, fieldStyles, useToast } from '@afrotransact/ui';
import { ArrowLeft } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useDebounced } from '@/lib/use-debounced';

import { AREA_LABEL, CATEGORY_LABEL, CATEGORY_TONE, CHANNEL_LABEL, SOURCE_LABEL, variantId } from './labels';
import { PreviewPane } from './PreviewPane';
import { TemplateEditor } from './TemplateEditor';
import { VersionHistory } from './VersionHistory';

const sampleText = (d: NotificationTemplateDetail) => JSON.stringify(d.sample.data, null, 2);

/** Parses the editable sample data: an object, or the parse error. */
function parseData(text: string): { data: Record<string, unknown> } | { error: string } {
  try {
    const v: unknown = JSON.parse(text);
    if (v && typeof v === 'object' && !Array.isArray(v)) return { data: v as Record<string, unknown> };
    return { error: 'object' };
  } catch (e) {
    return { error: e instanceof Error ? e.message : String(e) };
  }
}

function Detail({ detail }: { detail: NotificationTemplateDetail }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const api = staffApi();
  const [selected, setSelected] = useState(variantId(detail.variants[0] ?? { locale: 'en', channel: 'email' }));
  const variant = detail.variants.find((v) => variantId(v) === selected) ?? detail.variants[0];
  const [version, setVersion] = useState(0);
  const [editing, setEditing] = useState(false);
  const [dataText, setDataText] = useState(() => sampleText(detail));
  const parsed = useMemo(() => parseData(dataText), [dataText]);
  // The preview keeps the last data that parsed while the JSON is being typed.
  const [lastGood, setLastGood] = useState<Record<string, unknown>>(detail.sample.data);
  useEffect(() => {
    if ('data' in parsed) setLastGood(parsed.data);
  }, [parsed]);
  const data = useDebounced(lastGood, 300);
  const shown = version || variant?.latestVersion || 0;
  const previewBody = { channel: variant?.channel ?? 'email', locale: variant?.locale ?? 'en', version: shown, data };
  const preview = useQuery({ ...staffQueries.templatePreview(api, detail.key, previewBody), enabled: !!variant && !editing });
  const testSend = useMutation({
    mutationFn: () => api.testSendNotificationTemplate(detail.key, previewBody),
    onSuccess: (r) => toast.show(t('st.ntf.test.sent', { to: r.to }))
  });
  if (!variant) return <ErrorState error={null} />;
  const canTest = detail.canTestSend && variant.channel !== 'push';
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <div className="flex flex-wrap items-start gap-3">
          <div className="min-w-0 flex-1">
            <h2 className="m-0 font-mono text-xl font-bold break-all" data-testid="template-key">
              {detail.key}
            </h2>
            <p className="m-0 mt-1 flex flex-wrap items-center gap-2 text-sm text-muted">
              <StatusPill tone={CATEGORY_TONE[detail.category]}>{t(CATEGORY_LABEL[detail.category])}</StatusPill>
              <span>{t(AREA_LABEL[detail.sample.area])}</span>
            </p>
          </div>
          {detail.canEdit && !editing ? (
            <Button type="button" onClick={() => setEditing(true)}>
              {t('st.ntf.detail.newVersion')}
            </Button>
          ) : null}
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <span className="text-xs font-bold text-muted">{t('st.ntf.detail.sentOn')}</span>
          {detail.sample.eventTypes.length ? (
            <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0" aria-label={t('st.ntf.detail.sentOn')}>
              {detail.sample.eventTypes.map((e) => (
                <li key={e} className="rounded-pill bg-surface-muted px-2 py-0.5 font-mono text-xs">
                  {e}
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-muted">{t('st.ntf.detail.noEvent')}</span>
          )}
        </div>
      </Card>

      <div role="group" aria-label={t('st.ntf.detail.variants')} className="flex flex-wrap gap-2">
        {detail.variants.map((v) => (
          <button
            key={variantId(v)}
            type="button"
            aria-pressed={variantId(v) === selected}
            onClick={() => {
              setSelected(variantId(v));
              setVersion(0);
              setEditing(false);
            }}
            className={
              variantId(v) === selected
                ? 'rounded-pill border border-ink-1000 bg-ink-1000 px-3 py-1.5 text-sm font-semibold text-inverse'
                : 'rounded-pill border border-border bg-surface px-3 py-1.5 text-sm font-semibold'
            }
          >
            {t('st.ntf.detail.variant', { channel: t(CHANNEL_LABEL[v.channel]), locale: v.locale, version: v.latestVersion })}
          </button>
        ))}
      </div>

      {editing ? (
        <TemplateEditor
          key={variantId(variant)}
          templateKey={detail.key}
          variant={variant}
          data={data}
          checkPlaceholders={detail.sample.source !== 'none'}
          canTestSend={detail.canTestSend}
          onDone={(created) => {
            setEditing(false);
            setVersion(0);
            if (created) void qc.invalidateQueries({ queryKey: staffKeys.template(detail.key) });
          }}
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <Card
            title={t('st.ntf.detail.preview', { version: shown })}
            actions={
              detail.canTestSend ? (
                <Button type="button" variant="neutral" size="sm" disabled={!canTest} loading={testSend.isPending} onClick={() => testSend.mutate()} title={canTest ? undefined : t('st.ntf.test.noPush')}>
                  {t('st.ntf.test.send')}
                </Button>
              ) : null
            }
          >
            {testSend.isError ? (
              <Notice tone="danger" title={t('st.ntf.test.failed')} role="alert">
                {problemMessage(testSend.error)}
              </Notice>
            ) : null}
            {preview.isPending ? <Spinner /> : null}
            {preview.isError ? (
              <Notice tone="danger" title={t('st.ntf.detail.renderFailed')} role="alert">
                {problemMessage(preview.error)}
              </Notice>
            ) : null}
            {preview.data ? <PreviewPane channel={variant.channel} preview={preview.data} templateName={variant.versions.find((v) => v.version === shown)?.subject} /> : null}
            <details className="border-t border-border pt-3">
              <summary className="cursor-pointer text-sm font-semibold">{t('st.ntf.detail.sampleData', { source: t(SOURCE_LABEL[detail.sample.source]) })}</summary>
              <div className="mt-2 flex flex-col gap-2">
                <Field label={t('st.ntf.detail.sampleJson')} error={'error' in parsed ? t('st.ntf.detail.badJson') : null} help={t('st.ntf.detail.sampleHelp')}>
                  {(a11y) => (
                    <textarea {...a11y} className={`${fieldStyles.control} font-mono text-xs`} rows={10} spellCheck={false} value={dataText} onChange={(e) => setDataText(e.currentTarget.value)} />
                  )}
                </Field>
                <div>
                  <Button type="button" variant="neutral" size="sm" onClick={() => setDataText(sampleText(detail))}>
                    {t('st.ntf.detail.resetSample')}
                  </Button>
                </div>
              </div>
            </details>
          </Card>
          <VersionHistory key={`${variantId(variant)}:${variant.latestVersion}`} variant={variant} selected={shown} onSelect={setVersion} />
        </div>
      )}
    </div>
  );
}

/** One template key: preview per locale and channel with editable sample data, version history with diffs, new versions and test-sends. */
export function TemplateDetail({ templateKey }: { templateKey: string }) {
  const t = useT();
  const q = useQuery(staffQueries.template(staffApi(), templateKey));
  return (
    <div className="flex flex-col gap-4">
      <Link href="/notifications" className="inline-flex items-center gap-1 text-sm font-semibold">
        <ArrowLeft size={16} aria-hidden /> {t('st.ntf.detail.back')}
      </Link>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? (
        isApiError(q.error) && q.error.status === 404 ? (
          <Notice tone="warning" title={t('st.ntf.detail.notFound')}>
            {templateKey}
          </Notice>
        ) : (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        )
      ) : null}
      {q.data ? <Detail key={q.data.key} detail={q.data} /> : null}
    </div>
  );
}
