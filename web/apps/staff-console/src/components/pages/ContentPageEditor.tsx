'use client';

import { type ContentDocument, type ContentKind, type ContentLocale, type ContentPage, isApiError, staffContentKeys, staffContentQueries, staffKeys } from '@afrotransact/api-client';
import { Button, Checkbox, Select, Tabs, TextField, useToast } from '@afrotransact/ui';
import { ArrowSquareOut, CaretRight } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { personName } from '@/lib/names';
import { useStaff } from '@/lib/session';
import { useDebounced } from '@/lib/use-debounced';

import { type DeviceKey } from './chrome';
import { ContentDiff } from './ContentDiff';
import { ContentPreview, LOCALE_NAME, PreviewControls,type PreviewDoc } from './ContentPreview';
import { ALL_LOCALES, blocksPublish, blocksSave, checkForm, type EditorForm, fieldProblems, initialForm, isDirty, toInput } from './form';
import { HistoryPanel } from './HistoryPanel';
import { MarkdownEditor } from './MarkdownEditor';
import { PublishDialog } from './PublishDialog';

type Mode = 'preview' | 'edit' | 'history';
type Source = { kind: 'published' } | { kind: 'draft' } | { kind: 'version'; version: string };

const fieldErrors = (e: unknown): Record<string, string> => (isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { _: problemMessage(e) });

const toPreview = (doc: ContentDocument, title: string): PreviewDoc => ({
  version: doc.version,
  effectiveDate: doc.effectiveDate,
  draftBanner: doc.draftBanner,
  title: doc.title || title,
  bodies: doc.bodies,
  needsReview: doc.needsReview
});

/** The live link of a page: an absolute storefront address, opened in a new tab. */
export function LiveLink({ url, path }: { url: string | null; path: string | null }) {
  const t = useT();
  if (!url) return <span className="text-sm text-muted">{t('st.pages.live.none')}</span>;
  return (
    <a href={url} target="_blank" rel="noreferrer" className="inline-flex min-h-9 items-center gap-1 text-sm font-semibold">
      {t('st.pages.live.open')}
      <span className="font-mono text-xs font-normal text-muted">{path ?? url}</span>
      <ArrowSquareOut size={14} aria-hidden />
      <span className="sr-only">{t('st.pages.live.newTab')}</span>
    </a>
  );
}

function Editor({ page, locale, setLocale, device, setDevice }: { page: ContentPage; locale: ContentLocale; setLocale: (l: ContentLocale) => void; device: DeviceKey; setDevice: (d: DeviceKey) => void }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { session } = useStaff();
  const api = staffApi().content;
  const legal = page.kind === 'legal';
  const mode: Mode = (['preview', 'edit', 'history'] as const).find((m) => m === params.get('mode')) ?? 'preview';
  const setMode = (m: Mode) => router.replace(`${pathname}?mode=${m}`, { scroll: false });

  const baseline = useMemo(() => initialForm(page), [page]);
  const [form, setForm] = useState<EditorForm>(baseline);
  const [source, setSource] = useState<Source>(page.draft ? { kind: 'draft' } : { kind: 'published' });
  const [publishing, setPublishing] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const dirty = isDirty(form, baseline);
  const set = <K extends keyof EditorForm>(k: K, v: EditorForm[K]) => setForm((f) => ({ ...f, [k]: v }));

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const issues = useMemo(() => checkForm(page.kind, form, page.placeholders), [page.kind, page.placeholders, form]);
  const problems = useMemo(() => fieldProblems(page.kind, form), [page.kind, form]);
  const saveBlocked = blocksSave(issues, problems);
  const publishBlocked = blocksPublish(issues, problems);
  const blockingIssues = Object.values(issues).flat().filter((i) => i.level === 'error');
  const myName = `${session.user.firstName} ${session.user.lastName}`;
  const isLastEditor = page.draft?.updatedByName === myName;
  const rowVersion = page.draft?.rowVersion ?? null;

  const refresh = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: staffContentKeys.all }),
      qc.invalidateQueries({ queryKey: staffKeys.resource(legal ? 'legal-documents' : 'help-articles') })
    ]);

  const save = useMutation({
    mutationFn: () => api.saveDraft(page.kind, page.key, toInput(page.kind, form, rowVersion)),
    onSuccess: async () => {
      setErrors({});
      toast.show(t('st.pages.saved'));
      await refresh();
      setSource({ kind: 'draft' });
    },
    onError: (e) => setErrors(fieldErrors(e))
  });

  const discard = useMutation({
    mutationFn: () => api.discardDraft(page.kind, page.key),
    onSuccess: async () => {
      toast.show(t('st.pages.discarded'));
      await refresh();
      setSource({ kind: 'published' });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const publish = useMutation({
    mutationFn: async (reason: string) => {
      // Help pages: Publish saves what is on screen first, so one click publishes the text the person is looking at.
      let version = rowVersion;
      if (!legal && (dirty || version === null)) {
        const saved = await api.saveDraft(page.kind, page.key, toInput(page.kind, form, version));
        version = saved.rowVersion;
      }
      return api.publish(page.kind, page.key, { reason, rowVersion: version ?? 0 });
    },
    onSuccess: async () => {
      setErrors({});
      setPublishing(false);
      toast.show(t('st.pages.published'));
      await refresh();
      setSource({ kind: 'published' });
      setMode('preview');
    },
    onError: (e) => setErrors(fieldErrors(e))
  });

  const rollback = useMutation({
    mutationFn: ({ version, reason }: { version: string; reason: string }) => api.rollback(page.kind, page.key, { version, reason: reason || undefined }),
    onSuccess: async () => {
      setErrors({});
      toast.show(legal ? t('st.pages.rolledBackLegal') : t('st.pages.rolledBack'));
      await refresh();
      setSource(legal ? { kind: 'draft' } : { kind: 'published' });
      setMode(legal ? 'edit' : 'preview');
    },
    onError: (e) => setErrors(fieldErrors(e))
  });

  const versionDoc = useQuery({ ...staffContentQueries.version(api, page.kind, page.key, source.kind === 'version' ? source.version : ''), enabled: source.kind === 'version' });
  const preview: PreviewDoc | null =
    source.kind === 'published' ? toPreview(page.published, page.title) : source.kind === 'draft' && page.draft ? toPreview(page.draft, page.title) : source.kind === 'version' && versionDoc.data ? toPreview(versionDoc.data, page.title) : null;
  // The edit tab previews what is typed, a moment after typing stops.
  const typed = useDebounced(form, 250);
  const typedPreview: PreviewDoc = { version: typed.version || page.published.version, effectiveDate: typed.effectiveDate || null, draftBanner: legal ? typed.draftBanner : null, title: legal ? page.title : typed.title, bodies: typed.bodies, needsReview: typed.needsReview };

  const translated = Object.keys(mode === 'edit' ? form.bodies : (preview?.bodies ?? page.published.bodies));
  const needsReview = mode === 'edit' ? form.needsReview : (preview?.needsReview ?? []);
  const controls = (
    <PreviewControls locales={page.locales} locale={locale} onLocale={setLocale} device={device} onDevice={setDevice} translated={translated} needsReview={needsReview} />
  );

  const bodyForLocale = form.bodies[locale];
  const markersTotal = Object.values(issues).flat().filter((i) => i.code === 'legal-marker').reduce((n, i) => n + (i.count ?? 0), 0);

  const sourceItems: { value: string; label: string }[] = [
    { value: 'published', label: t('st.pages.source.published', { version: page.published.version }) },
    ...(page.draft ? [{ value: 'draft', label: t('st.pages.source.draft') }] : []),
    ...(source.kind === 'version' ? [{ value: `v:${source.version}`, label: t('st.pages.source.version', { version: source.version }) }] : [])
  ];

  const publishSummary = [
    legal ? t('st.pages.publish.sumVersion', { version: form.version }) : t('st.pages.publish.sumRevision'),
    ...(legal ? [t('st.pages.publish.sumEffective', { date: form.effectiveDate }), form.draftBanner ? t('st.pages.publish.sumBanner') : t('st.pages.publish.sumFinal')] : []),
    t('st.pages.publish.sumLanguages', { n: Object.keys(form.bodies).length })
  ];

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <nav aria-label={t('st.pages.breadcrumb')} className="flex items-center gap-1 text-sm text-muted">
        <Link href="/settings">{t('st.pages.settings')}</Link>
        <CaretRight size={12} aria-hidden />
        <Link href="/settings?section=published-pages">{t('st.pages.title')}</Link>
        <CaretRight size={12} aria-hidden />
        <span aria-current="page" className="text-text">{page.title}</span>
      </nav>

      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="m-0 text-2xl font-bold">{page.title}</h1>
          <p className="m-0 flex flex-wrap items-center gap-2 text-sm text-muted">
            <span>{page.group}</span>
            <StatusPill tone="good">{legal ? t('st.pages.versionLabel', { version: page.published.version }) : t('st.pages.revisionLabel', { version: page.published.version })}</StatusPill>
            {page.published.draftBanner ? <StatusPill tone="warn">{t('st.pages.status.DRAFT_FOR_COUNSEL')}</StatusPill> : null}
            {page.published.updatedByName ? <span>{t('st.pages.updatedBy', { name: personName(t, page.published.updatedByName), when: page.published.updatedAt ? dateTime(page.published.updatedAt) : '' })}</span> : null}
          </p>
          {page.draft ? (
            <p className="m-0 flex flex-wrap items-center gap-2 text-sm">
              <StatusPill tone="info">{t('st.pages.hasDraft')}</StatusPill>
              <span className="text-muted">{t('st.pages.draftBy', { name: personName(t, page.draft.updatedByName), when: page.draft.updatedAt ? dateTime(page.draft.updatedAt) : '' })}</span>
            </p>
          ) : null}
        </div>
        <LiveLink url={page.liveUrl} path={page.livePath} />
      </header>

      {!page.access.canEdit ? (
        <Notice tone="info" icon="eye" title={t('st.pages.readOnly')}>
          {page.access.reason ?? t('st.pages.readOnlyBody')}
        </Notice>
      ) : null}

      <Tabs
        label={t('st.pages.modes')}
        value={mode}
        onValueChange={(v) => setMode(v as Mode)}
        items={[
          {
            value: 'preview',
            label: t('st.pages.mode.preview'),
            content: (
              <div className="flex flex-col gap-3 pt-3">
                <div className="flex flex-wrap items-end gap-3">
                  <Select
                    id="preview-source"
                    label={t('st.pages.source.label')}
                    value={source.kind === 'version' ? `v:${source.version}` : source.kind}
                    onChange={(e) => {
                      const v = e.currentTarget.value;
                      setSource(v === 'draft' ? { kind: 'draft' } : v.startsWith('v:') ? { kind: 'version', version: v.slice(2) } : { kind: 'published' });
                    }}
                    options={sourceItems}
                  />
                  {page.access.canEdit ? (
                    <Button variant="neutral" onClick={() => setMode('edit')}>
                      {t('st.pages.mode.edit')}
                    </Button>
                  ) : null}
                </div>
                {controls}
                {preview ? (
                  <ContentPreview kind={page.kind} doc={preview} placeholders={page.placeholders} locale={locale} device={device} />
                ) : source.kind === 'version' && versionDoc.isError ? (
                  <ErrorState error={versionDoc.error} />
                ) : (
                  <Spinner label={t('st.pages.loading')} />
                )}
              </div>
            )
          },
          {
            value: 'edit',
            label: t('st.pages.mode.edit'),
            content: page.access.canEdit ? (
              <div className="flex flex-col gap-4 pt-3">
                {legal && page.draft && !isLastEditor ? (
                  <Notice tone="info" title={t('st.pages.draftWaiting', { name: personName(t, page.draft.updatedByName) })}>
                    {t('st.pages.draftWaitingBody')}
                  </Notice>
                ) : null}
                <div className="grid min-w-0 grid-cols-1 items-start gap-4 xl:grid-cols-2">
                  <div className="flex min-w-0 flex-col gap-3">
                    <Card title={t('st.pages.settingsCard')}>
                      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-2">
                        {legal ? (
                          <>
                            <TextField id="pg-version" label={t('st.pages.field.version')} help={t('st.pages.field.versionHelp', { current: page.published.version })} value={form.version} error={errors.version ?? (problems.version ? t('st.pages.field.versionBad') : null)} onChange={(e) => set('version', e.currentTarget.value)} />
                            <TextField id="pg-effective" type="date" label={t('st.pages.field.effective')} value={form.effectiveDate} error={errors.effectiveDate ?? (problems.effectiveDate ? t('st.pages.required') : null)} onChange={(e) => set('effectiveDate', e.currentTarget.value)} />
                          </>
                        ) : (
                          <>
                            <TextField id="pg-title" label={t('st.pages.field.title')} maxLength={120} value={form.title} error={errors.title ?? (problems.title ? t('st.pages.required') : null)} onChange={(e) => set('title', e.currentTarget.value)} />
                            <Select id="pg-topic" label={t('st.pages.field.topic')} value={form.topic} error={errors.topic} onChange={(e) => set('topic', e.currentTarget.value)} options={page.topics.map((x) => ({ value: x.value, label: x.label }))} />
                            <TextField id="pg-summary" label={t('st.pages.field.summary')} maxLength={300} value={form.summary} error={errors.summary ?? (problems.summary ? t('st.pages.required') : null)} onChange={(e) => set('summary', e.currentTarget.value)} />
                            <TextField id="pg-order" type="number" min={0} max={1000} label={t('st.pages.field.order')} value={String(form.sortOrder)} onChange={(e) => set('sortOrder', Number(e.currentTarget.value) || 0)} />
                          </>
                        )}
                      </div>
                      {legal ? (
                        <Checkbox
                          id="pg-banner"
                          label={t('st.pages.field.banner')}
                          description={t('st.pages.field.bannerHelp')}
                          checked={form.draftBanner}
                          onChange={(e) => set('draftBanner', e.currentTarget.checked)}
                        />
                      ) : null}
                      <TextField id="pg-change" label={t('st.pages.field.change')} help={t('st.pages.field.changeHelp')} maxLength={1000} value={form.changeSummary} error={errors.changeSummary} onChange={(e) => set('changeSummary', e.currentTarget.value)} />
                    </Card>

                    {legal ? (
                      <div role="group" aria-label={t('st.pages.language')} className="flex flex-wrap items-center gap-2">
                        {ALL_LOCALES.filter((l) => l === 'en' || form.bodies[l] !== undefined).map((l) => (
                          <button
                            key={l}
                            type="button"
                            aria-pressed={locale === l}
                            onClick={() => setLocale(l)}
                            className={locale === l ? 'rounded-pill border border-ink-1000 bg-ink-1000 px-3 py-1.5 text-sm font-semibold text-inverse' : 'rounded-pill border border-border bg-surface px-3 py-1.5 text-sm font-semibold'}
                          >
                            {LOCALE_NAME[l]}
                            {l === 'en' ? ` · ${t('st.pages.binding')}` : form.needsReview.includes(l) ? ` · ${t('st.pages.locale.needsReview')}` : ''}
                          </button>
                        ))}
                        {ALL_LOCALES.some((l) => form.bodies[l] === undefined) ? (
                          <Select
                            id="pg-add-locale"
                            label={t('st.pages.addTranslation')}
                            value=""
                            onChange={(e) => {
                              const l = e.currentTarget.value as ContentLocale;
                              if (!l) return;
                              setForm((f) => ({ ...f, bodies: { ...f.bodies, [l]: f.bodies.en ?? '' }, needsReview: [...f.needsReview, l] }));
                              setLocale(l);
                            }}
                            options={[{ value: '', label: t('st.pages.chooseLanguage') }, ...ALL_LOCALES.filter((l) => form.bodies[l] === undefined).map((l) => ({ value: l, label: LOCALE_NAME[l] }))]}
                          />
                        ) : null}
                      </div>
                    ) : null}

                    {legal && locale !== 'en' && bodyForLocale !== undefined ? (
                      <div className="flex flex-wrap items-center gap-3">
                        <Checkbox
                          id="pg-review"
                          label={t('st.pages.locale.markReview', { language: LOCALE_NAME[locale] })}
                          checked={form.needsReview.includes(locale)}
                          onChange={(e) => set('needsReview', e.currentTarget.checked ? [...form.needsReview.filter((l) => l !== locale), locale] : form.needsReview.filter((l) => l !== locale))}
                        />
                        <Button
                          size="sm"
                          variant="neutral"
                          onClick={() => {
                            setForm((f) => {
                              const { [locale]: _gone, ...rest } = f.bodies;
                              return { ...f, bodies: rest, needsReview: f.needsReview.filter((l) => l !== locale) };
                            });
                            setLocale('en');
                          }}
                        >
                          {t('st.pages.removeTranslation', { language: LOCALE_NAME[locale] })}
                        </Button>
                      </div>
                    ) : null}

                    <MarkdownEditor
                      key={locale}
                      id={`pg-body-${locale}`}
                      label={legal ? t('st.pages.bodyLabel', { language: LOCALE_NAME[locale] }) : t('st.pages.bodyLabelHelp')}
                      value={bodyForLocale ?? form.bodies.en ?? ''}
                      locale={bodyForLocale === undefined ? 'en' : locale}
                      disabled={bodyForLocale === undefined && locale !== 'en'}
                      issues={issues[bodyForLocale === undefined ? 'en' : locale] ?? []}
                      onChange={(v) => setForm((f) => ({ ...f, bodies: { ...f.bodies, [bodyForLocale === undefined ? 'en' : locale]: v } }))}
                    />
                    {errors['bodies.en'] ? <p role="alert" className="m-0 text-sm text-danger">{errors['bodies.en']}</p> : null}
                    {Object.entries(errors)
                      .filter(([k]) => k.startsWith('bodies.') && k !== 'bodies.en')
                      .map(([k, v]) => (
                        <p key={k} role="alert" className="m-0 text-sm text-danger">
                          {k.slice(7)}: {v}
                        </p>
                      ))}
                    {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
                  </div>

                  <div className="flex min-w-0 flex-col gap-3 xl:sticky xl:top-20">
                    <h2 className="m-0 text-md font-bold">{t('st.pages.livePreview')}</h2>
                    {controls}
                    <ContentPreview kind={page.kind} doc={typedPreview} placeholders={page.placeholders} locale={locale} device={device} stageLabel={t('st.pages.livePreview')} />
                  </div>
                </div>

                <div className="sticky bottom-0 z-10 -mx-1 flex flex-wrap items-center justify-between gap-2 border-t border-border bg-surface px-1 py-3">
                  <p className="m-0 text-sm text-muted" aria-live="polite">
                    {dirty ? t('st.pages.unsaved') : page.draft ? t('st.pages.draftSaved') : t('st.pages.noChanges')}
                    {markersTotal > 0 ? ` · ${t('st.pages.markersLeft', { n: markersTotal })}` : ''}
                  </p>
                  <div className="flex flex-wrap items-center gap-2">
                    {page.draft ? (
                      <Button variant="neutral" className="text-danger" onClick={() => discard.mutate()} disabled={discard.isPending}>
                        {t('st.pages.discard')}
                      </Button>
                    ) : null}
                    {dirty ? (
                      <Button variant="neutral" onClick={() => setForm(baseline)}>
                        {t('st.pages.revert')}
                      </Button>
                    ) : null}
                    <Button variant="neutral" disabled={!dirty || saveBlocked || save.isPending} onClick={() => save.mutate()}>
                      {save.isPending ? t('st.pages.saving') : t('st.pages.saveDraft')}
                    </Button>
                    <Button
                      disabled={!page.access.canEdit || (legal ? dirty || !page.draft : !dirty && !page.draft)}
                      onClick={() => {
                        setErrors({});
                        setPublishing(true);
                      }}
                    >
                      {t('st.pages.publish.button')}
                    </Button>
                  </div>
                </div>
                {legal && dirty ? <p className="m-0 text-end text-xs text-muted">{t('st.pages.saveFirst')}</p> : null}
                <PublishDialog
                  key={publishing ? 'open' : 'closed'}
                  open={publishing}
                  onClose={() => setPublishing(false)}
                  kind={page.kind}
                  title={page.title}
                  summary={publishSummary}
                  blocking={publishBlocked ? blockingIssues : []}
                  needsReview={form.needsReview}
                  secondApprover={page.access.secondApprover}
                  isLastEditor={isLastEditor}
                  deniedReason={page.access.canPublish ? null : (page.access.reason ?? t('st.pages.readOnlyBody'))}
                  busy={publish.isPending}
                  errors={errors}
                  onPublish={(reason) => publish.mutate(reason)}
                />
              </div>
            ) : (
              <Notice tone="info" icon="eye" title={t('st.pages.readOnly')}>
                {page.access.reason ?? t('st.pages.readOnlyBody')}
              </Notice>
            )
          },
          {
            value: 'history',
            label: t('st.pages.mode.history'),
            content: (
              <div className="pt-3">
                <HistoryPanel
                  page={page}
                  canRollback={page.access.canEdit}
                  busy={rollback.isPending}
                  errors={errors}
                  onPreview={(version) => {
                    setSource({ kind: 'version', version });
                    setMode('preview');
                  }}
                  onRollback={async (version, reason) => {
                    await rollback.mutateAsync({ version, reason });
                  }}
                />
              </div>
            )
          }
        ]}
      />
      {page.draft && mode === 'history' ? (
        <Card title={t('st.pages.draftVsLive')}>
          <ContentDiff from={page.published} to={page.draft} fromLabel={t('st.pages.source.published', { version: page.published.version })} toLabel={t('st.pages.source.draft')} />
        </Card>
      ) : null}
    </div>
  );
}

/** One public page: preview what shoppers see (published, draft, any past version), edit it, publish it, go back. */
export function ContentPageEditor({ kind, pageKey }: { kind: ContentKind; pageKey: string }) {
  const t = useT();
  const api = staffApi().content;
  const page = useQuery(staffContentQueries.page(api, kind, pageKey));
  // The language and width being previewed outlive a save (which reloads the page).
  const [locale, setLocale] = useState<ContentLocale>('en');
  const [device, setDevice] = useState<DeviceKey>('desktop');
  if (page.isPending) return <Spinner label={t('st.pages.loading')} />;
  if (page.isError) return <ErrorState error={page.error} onRetry={() => void page.refetch()} />;
  // A new editor state whenever the server's page changes (a save, a publication), so the form never shows stale text.
  const stamp = `${page.data.published.version}|${page.data.draft?.rowVersion ?? '-'}|${page.data.draft?.updatedAt ?? ''}|${page.data.published.updatedAt ?? ''}`;
  return <Editor key={stamp} page={page.data} locale={locale} setLocale={setLocale} device={device} setDevice={setDevice} />;
}
