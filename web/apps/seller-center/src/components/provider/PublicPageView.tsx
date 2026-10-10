'use client';

import { type PublicPage, type PublicPageInput, sellerKeys, sellerQueries, type SocialNetworkKey } from '@afrotransact/api-client';
import { Button, cx, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { storefrontUrl } from '@/lib/config';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

const NETWORKS: SocialNetworkKey[] = ['instagram', 'tiktok', 'facebook', 'youtube', 'whatsapp', 'website'];
const LABEL: Record<string, string> = { instagram: 'Instagram', tiktok: 'TikTok', facebook: 'Facebook', youtube: 'YouTube', whatsapp: 'WhatsApp catalogue', x: 'X', linkedin: 'LinkedIn', website: 'Website' };

type Form = Required<Pick<PublicPageInput, 'name' | 'tagline' | 'primaryColor' | 'accentColor' | 'layout' | 'header' | 'buttonShape' | 'gallerySize'>> & { social: Record<string, string> };

const formOf = (p: PublicPage): Form => ({
  name: p.name, tagline: p.tagline, primaryColor: p.primaryColor, accentColor: p.accentColor, layout: p.layout, header: p.header, buttonShape: p.buttonShape,
  gallerySize: p.gallerySize, social: Object.fromEntries(p.social.map((s) => [s.network, s.url]))
});

/** Public page (providers) / Storefront & theme (sellers): layout, colours, links (pending until approved) and publish. */
export function PublicPageView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, isProvider, can } = useSeller();
  const module = isProvider ? 'public-page' : 'storefront';
  const editable = can(module);
  const q = useQuery(sellerQueries.publicPage(api, orgId));
  const [form, setForm] = useState<Form | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => {
    if (q.data && !form) setForm(formOf(q.data));
  }, [q.data, form]);
  const onSaved = (p: PublicPage) => {
    qc.setQueryData(sellerKeys.publicPage(orgId), p);
    setForm(formOf(p));
  };
  const save = useMutation({
    mutationFn: (f: Form) =>
      api.savePublicPage({ ...f, social: Object.entries(f.social).filter(([, url]) => url.trim()).map(([network, url]) => ({ network: network as SocialNetworkKey, url: url.trim() })) }),
    onSuccess: (p) => { onSaved(p); setErrors({}); toast.show('Draft saved'); },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const publish = useMutation({ mutationFn: () => api.publishPublicPage(), onSuccess: (p) => { onSaved(p); toast.show('Published'); }, onError: (e) => toast.show(problemMessage(e), 'error') });
  if (q.isPending || !form) return <ModulePage module={module}>{q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <Spinner />}</ModulePage>;
  const page = q.data;
  if (!page) return null;
  const set = (p: Partial<Form>) => setForm({ ...form, ...p });
  const validate = () => {
    const e: Record<string, string> = {};
    if (form.name.trim().length < 2) e.name = '2–80 characters';
    if (form.tagline.length > 80) e.tagline = 'At most 80 characters';
    Object.entries(form.social).forEach(([n, url]) => { if (url.trim() && !url.trim().startsWith('https://')) e[`social.${n}`] = 'Use a https link'; });
    setErrors(e);
    return Object.keys(e).length === 0;
  };
  return (
    <ModulePage
      module={module}
      actions={<a href={`${storefrontUrl()}${page.publicPath}`} target="_blank" rel="noreferrer">View my public page</a>}
    >
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
        <form className="flex min-w-0 flex-col gap-4" onSubmit={(e) => { e.preventDefault(); if (validate()) save.mutate(form); }}>
          <fieldset disabled={!editable} className="m-0 flex flex-col gap-1 rounded-md border border-border p-4">
            <legend className="px-1 text-md font-semibold">Page design</legend>
            <TextField label="Name" value={form.name} error={errors.name} onChange={(e) => set({ name: e.target.value })} />
            <TextField label={`Tagline (${form.tagline.length}/80)`} value={form.tagline} error={errors.tagline} onChange={(e) => set({ tagline: e.target.value })} />
            <div className="grid gap-x-3 sm:grid-cols-3">
              {isProvider ? <Select label="Layout" value={form.layout} onChange={(e) => set({ layout: e.target.value as Form['layout'] })} options={[{ value: 'CLASSIC', label: 'Classic' }, { value: 'GALLERY_FIRST', label: 'Gallery first' }, { value: 'STORY', label: 'Story' }]} /> : null}
              <Select label="Header" value={form.header} onChange={(e) => set({ header: e.target.value as Form['header'] })} options={[{ value: 'COVER', label: 'Cover photo' }, { value: 'BAND', label: 'Colour band' }]} />
              <Select label="Buttons" value={form.buttonShape} onChange={(e) => set({ buttonShape: e.target.value as Form['buttonShape'] })} options={[{ value: 'ROUNDED', label: 'Rounded' }, { value: 'SQUARE', label: 'Square' }]} />
            </div>
            <div className="flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-2">Brand colour <input type="color" value={form.primaryColor} onChange={(e) => set({ primaryColor: e.target.value })} className="h-8 w-12" /></label>
              <label className="flex items-center gap-2">Accent colour <input type="color" value={form.accentColor} onChange={(e) => set({ accentColor: e.target.value })} className="h-8 w-12" /></label>
              {isProvider ? <TextField label="Photos in gallery (plan limit 24)" inputMode="numeric" value={String(form.gallerySize)} onChange={(e) => set({ gallerySize: Math.min(24, Number(e.target.value.replace(/\D/g, '')) || 0) })} /> : null}
            </div>
            <p className="m-0 text-xs text-muted">Colours are checked for contrast (WCAG 2.2 AA) when you publish; checkout and prices always stay AfroTransact-styled.</p>
          </fieldset>
          <fieldset disabled={!editable} className="m-0 flex flex-col gap-1 rounded-md border border-border p-4">
            <legend className="px-1 text-md font-semibold">Social &amp; catalogue links</legend>
            {NETWORKS.map((n) => {
              const current = page.social.find((s) => s.network === n);
              return (
                <div key={n} className="flex items-end gap-2">
                  <div className="flex-1"><TextField label={LABEL[n] ?? n} placeholder="https://" value={form.social[n] ?? ''} error={errors[`social.${n}`]} onChange={(e) => set({ social: { ...form.social, [n]: e.target.value } })} /></div>
                  {current ? <span className="pb-6"><StatusPill tone={current.status === 'APPROVED' ? 'good' : current.status === 'REJECTED' ? 'bad' : 'warn'}>{current.status === 'PENDING' ? 'Pending approval' : current.status.toLowerCase()}</StatusPill></span> : null}
                </div>
              );
            })}
            <p className="m-0 text-xs text-muted">Only approved links appear on your page. Edited links go back to pending approval.</p>
          </fieldset>
          {editable ? (
            <div className="flex flex-wrap gap-2">
              <Button type="submit" variant="neutral" loading={save.isPending}>Save draft</Button>
              <Button onClick={() => publish.mutate()} loading={publish.isPending}>Publish</Button>
            </div>
          ) : null}
        </form>
        <aside aria-label="Preview" className="sticky top-20 flex flex-col gap-3 overflow-hidden rounded-md border border-border">
          <div className={cx('flex h-24 items-end p-3', form.header === 'COVER' ? 'bg-surface-muted' : '')} style={form.header === 'BAND' ? { background: form.primaryColor } : undefined}>
            <span className="rounded-xs bg-surface px-2 py-0.5 text-2xs font-bold">{page.status === 'PUBLISHED' ? `Published ${shortDate(page.publishedAt)}` : 'Draft'}</span>
          </div>
          <div className="flex flex-col gap-2 px-4 pb-4">
            <strong className="text-xl">{form.name}</strong>
            <span className="text-sm text-muted">{form.tagline}</span>
            <span className={cx('self-start px-4 py-2 text-sm font-bold', form.buttonShape === 'ROUNDED' ? 'rounded-pill' : 'rounded-xs')} style={{ background: form.accentColor, color: 'var(--at-color-text-inverse)' }}>
              {isProvider ? 'Book now' : 'Shop the store'}
            </span>
            {isProvider && page.featured.length ? (
              <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
                {page.featured.map((f) => <li key={f.id}><strong>{f.headline}</strong> <StatusPill tone={toneOf(f.published ? 'PUBLISHED' : 'DRAFT')}>{f.published ? 'Published' : 'Draft'}</StatusPill></li>)}
              </ul>
            ) : null}
          </div>
        </aside>
      </div>
    </ModulePage>
  );
}
