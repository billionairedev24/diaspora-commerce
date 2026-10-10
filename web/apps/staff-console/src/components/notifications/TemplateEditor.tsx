'use client';

import {
  isApiError,
  MAX_SMS_SEGMENTS,
  missingPlaceholders,
  type NotificationCategory,
  NotificationCategorySchema,
  type NotificationDraft,
  type NotificationTemplateVariant,
  staffKeys,
  staffQueries
} from '@afrotransact/api-client';
import { Button, Field, fieldStyles, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { problemMessage, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useDebounced } from '@/lib/use-debounced';

import { CATEGORY_LABEL, CHANNEL_LABEL, SUBJECT_LABEL, TEXT_LABEL } from './labels';
import { PreviewPane } from './PreviewPane';

type Part = 'subject' | 'bodyHtml' | 'bodyText';

/** Field errors from a 422 (the BFF prefixes draft-preview fields with "draft."). */
function fieldErrorsOf(error: unknown): Record<string, string> {
  if (!isApiError(error)) return {};
  return Object.fromEntries(Object.entries(error.fieldErrors).map(([k, v]) => [k.replace(/^draft\./, ''), v]));
}

/**
 * Creates the next version of one locale and channel: edit the parts, see the live preview with the sample data, check
 * placeholders against the sample data (the fields the event carries) and the SMS segment count, test-send the draft to
 * yourself, then create it. The BFF repeats every check before notifications stores the version.
 */
export function TemplateEditor({
  templateKey,
  variant,
  data,
  checkPlaceholders,
  canTestSend,
  onDone
}: {
  templateKey: string;
  variant: NotificationTemplateVariant;
  data: Record<string, unknown>;
  /** False for keys without sample data (only the render is checked). */
  checkPlaceholders: boolean;
  canTestSend: boolean;
  onDone: (version: number | null) => void;
}) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const api = staffApi();
  const latest = variant.versions[0];
  const { channel, locale } = variant;
  const [category, setCategory] = useState<NotificationCategory>(latest?.category ?? 'transactional');
  const [subject, setSubject] = useState(latest?.subject ?? '');
  const [html, setHtml] = useState(latest?.bodyHtml ?? '');
  const [text, setText] = useState(latest?.bodyText ?? '');
  const [submitted, setSubmitted] = useState<Record<string, string>>({});

  const draft: NotificationDraft = useMemo(
    () => ({ category, bodyText: text, ...(channel !== 'sms' ? { subject } : {}), ...(channel === 'email' ? { bodyHtml: html } : {}) }),
    [category, subject, html, text, channel]
  );
  const live = useDebounced(draft, 400);
  const preview = useQuery({ ...staffQueries.templatePreview(api, templateKey, { channel, locale, data, draft: live }), enabled: live.bodyText.trim() !== '' });

  const local = useMemo(() => {
    const errs: Partial<Record<Part, string>> = {};
    const parts: [Part, string][] = [['bodyText', text], ...(channel !== 'sms' ? ([['subject', subject]] as [Part, string][]) : []), ...(channel === 'email' ? ([['bodyHtml', html]] as [Part, string][]) : [])];
    if (checkPlaceholders) {
      for (const [part, src] of parts) {
        const missing = missingPlaceholders(src, data);
        if (missing.length) errs[part] = t('st.ntf.edit.missing', { fields: missing.join(', ') });
      }
    }
    if (!text.trim()) errs.bodyText = t('st.ntf.edit.required');
    if (channel === 'email' && !subject.trim()) errs.subject = t('st.ntf.edit.required');
    if (channel === 'push' && !subject.trim()) errs.subject = t('st.ntf.edit.required');
    if (channel === 'email' && !html.trim()) errs.bodyHtml = t('st.ntf.edit.required');
    return errs;
  }, [text, subject, html, channel, data, checkPlaceholders, t]);

  const tooLong = channel === 'sms' && preview.data?.sms && preview.data.sms.segments > MAX_SMS_SEGMENTS;
  const previewErrors = preview.isError ? fieldErrorsOf(preview.error) : {};
  const errors: Record<string, string | undefined> = { ...previewErrors, ...local, ...submitted };

  const create = useMutation({
    mutationFn: () => api.createNotificationTemplateVersion(templateKey, { locale, channel, category, bodyText: text, ...(channel !== 'sms' ? { subject } : {}), ...(channel === 'email' ? { bodyHtml: html } : {}) }),
    onSuccess: (v) => {
      toast.show(t('st.ntf.edit.created', { version: v.version }));
      void qc.invalidateQueries({ queryKey: staffKeys.templates() });
      onDone(v.version);
    },
    onError: (e) => setSubmitted(fieldErrorsOf(e))
  });
  const testSend = useMutation({
    mutationFn: () => api.testSendNotificationTemplate(templateKey, { channel, locale, data, draft }),
    onSuccess: (r) => toast.show(t('st.ntf.test.sent', { to: r.to }))
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted({});
    if (Object.keys(local).length || tooLong) return;
    create.mutate();
  };

  const nextVersion = variant.latestVersion + 1;
  return (
    <form noValidate onSubmit={submit} className="grid grid-cols-1 gap-4 xl:grid-cols-2" aria-label={t('st.ntf.edit.form', { version: nextVersion })}>
      <Card title={t('st.ntf.edit.title', { version: nextVersion, channel: t(CHANNEL_LABEL[channel]), locale })}>
        <Select
          label={t('st.ntf.edit.category')}
          help={t('st.ntf.edit.categoryHelp')}
          options={NotificationCategorySchema.options.map((c) => ({ value: c, label: t(CATEGORY_LABEL[c]) }))}
          value={category}
          onChange={(e) => setCategory(NotificationCategorySchema.parse(e.currentTarget.value))}
        />
        {channel !== 'sms' ? (
          <TextField label={t(SUBJECT_LABEL[channel])} value={subject} maxLength={300} error={errors.subject} onChange={(e) => setSubject(e.currentTarget.value)} />
        ) : null}
        {channel === 'email' ? (
          <Field label={t('st.ntf.field.html')} error={errors.bodyHtml} help={t('st.ntf.field.htmlHelp')}>
            {(a11y) => (
              <textarea {...a11y} className={`${fieldStyles.control} font-mono text-xs`} rows={14} spellCheck={false} value={html} onChange={(e) => setHtml(e.currentTarget.value)} />
            )}
          </Field>
        ) : null}
        <Field label={t(TEXT_LABEL[channel])} error={errors.bodyText} help={t('st.ntf.field.textHelp')}>
          {(a11y) => <textarea {...a11y} className={`${fieldStyles.control} font-mono text-xs`} rows={channel === 'email' ? 6 : 4} value={text} onChange={(e) => setText(e.currentTarget.value)} />}
        </Field>
        {errors.data ? (
          <Notice tone="danger" title={t('st.ntf.edit.dataError')} role="alert">
            {errors.data}
          </Notice>
        ) : null}
        {tooLong ? (
          <Notice tone="danger" title={t('st.ntf.edit.tooLong', { max: MAX_SMS_SEGMENTS })} role="alert">
            {t('st.ntf.edit.tooLongHelp')}
          </Notice>
        ) : null}
        {create.isError && !Object.keys(fieldErrorsOf(create.error)).length ? (
          <Notice tone="danger" title={t('st.ntf.edit.notCreated')} role="alert">
            {problemMessage(create.error)}
          </Notice>
        ) : null}
        {testSend.isError ? (
          <Notice tone="danger" title={t('st.ntf.test.failed')} role="alert">
            {problemMessage(testSend.error)}
          </Notice>
        ) : null}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" loading={create.isPending}>
            {t('st.ntf.edit.create', { version: nextVersion })}
          </Button>
          {canTestSend && channel !== 'push' ? (
            <Button type="button" variant="neutral" loading={testSend.isPending} disabled={Object.keys(local).length > 0} onClick={() => testSend.mutate()}>
              {t('st.ntf.test.draft')}
            </Button>
          ) : null}
          <Button type="button" variant="neutral" onClick={() => onDone(null)}>
            {t('st.ntf.edit.cancel')}
          </Button>
        </div>
      </Card>
      <Card title={t('st.ntf.edit.livePreview')}>
        <p className="m-0 text-xs text-muted">{t('st.ntf.edit.livePreviewHelp')}</p>
        {preview.isPending && preview.fetchStatus !== 'idle' ? <Spinner /> : null}
        {preview.data ? <PreviewPane channel={channel} preview={preview.data} templateName={subject} /> : null}
        {preview.isError && !Object.keys(previewErrors).length ? <p className="m-0 text-sm text-danger">{problemMessage(preview.error)}</p> : null}
        {preview.isError && Object.keys(previewErrors).length ? <p className="m-0 text-sm text-muted">{t('st.ntf.edit.previewBlocked')}</p> : null}
      </Card>
    </form>
  );
}
