'use client';

import {
  isApiError,
  type ProductSummary,
  sellerKeys,
  sellerQueries,
  type StoreConfig,
  type StorePageSettings,
  type StoreSocialNetwork,
  THEME_PRESET_IDS
} from '@afrotransact/api-client';
import { accentIsSafe, contrastRatio, nearestSafeAccent, PRESETS, resolveTheme, type ThemePreset } from '@afrotransact/tokens';
import { Button, buttonClassName, Field, fieldStyles, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';

import { SettingsPage } from '@/components/settings/SettingsShell';
import { ControlRowAction } from '@/components/ui/ControlRowAction';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { sellerApi } from '@/lib/api';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';
import {
  addFeatured,
  localProblems,
  MAX_FEATURED,
  MAX_SOCIAL,
  moveFeatured,
  moveSection,
  removeFeatured,
  sameConfig,
  SECTION_COPY,
  toggleSection
} from '@/lib/store-page';

import { StoreImageField } from './StoreImageField';
import { StorePagePreview } from './StorePagePreview';

const NETWORKS: { id: StoreSocialNetwork; name: string; example: string }[] = [
  { id: 'instagram', name: 'Instagram', example: 'https://instagram.com/yourstore' },
  { id: 'facebook', name: 'Facebook', example: 'https://facebook.com/yourstore' },
  { id: 'tiktok', name: 'TikTok', example: 'https://tiktok.com/@yourstore' },
  { id: 'x', name: 'X', example: 'https://x.com/yourstore' },
  { id: 'youtube', name: 'YouTube', example: 'https://youtube.com/@yourstore' },
  { id: 'whatsapp', name: 'WhatsApp', example: 'https://wa.me/15551234567' }
];

/** A website link stored before the rule: it can stay or be removed, never added; the public page hides it until the store has earned it. */
const LEGACY_WEBSITE = { id: 'website' as const, name: 'Website', example: 'https://yourstore.com' };

/** The policy sentence shown beside every field of free text on the page. */
const DIVERSION_POLICY = 'Off-platform sales and steering buyers away from AfroTransact are not allowed. Do not put phone numbers, emails, web addresses or social handles in your text: customers reach you through AfroTransact messages.';

function Group({ title, help, children, id }: { title: string; help?: string; children: React.ReactNode; id: string }) {
  return (
    <section aria-labelledby={id} className="flex flex-col gap-4 border-t border-border pt-6 first:border-t-0 first:pt-0">
      <div className="flex flex-col gap-1">
        <h2 id={id} className="m-0 text-lg font-semibold">
          {title}
        </h2>
        {help ? <p className="m-0 text-sm text-muted">{help}</p> : null}
      </div>
      {children}
    </section>
  );
}

function Segmented<T extends string | number>({ label, value, options, onChange, disabled }: { label: string; value: T; options: { id: T; name: string }[]; onChange: (v: T) => void; disabled?: boolean }) {
  return (
    <div role="radiogroup" aria-label={label} className="flex flex-wrap gap-2">
      {options.map((o) => (
        <button
          key={String(o.id)}
          type="button"
          role="radio"
          aria-checked={value === o.id}
          disabled={disabled}
          onClick={() => onChange(o.id)}
          className={`min-h-11 cursor-pointer rounded-sm border px-4 text-sm font-semibold sm:min-h-9 ${value === o.id ? 'border-text bg-text text-canvas' : 'border-border-strong bg-surface'}`}
        >
          {o.name}
        </button>
      ))}
    </div>
  );
}

/** The preview could not be built. A missing store (404) is said in plain words; anything else uses the shared error state. Always offers Retry. */
function PreviewError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isApiError(error) && error.status === 404) {
    return (
      <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-border bg-surface px-6 py-10 text-center" data-testid="store-preview-missing">
        <strong className="text-lg">We could not show your preview</strong>
        <p className="m-0 max-w-[60ch] text-sm text-muted">Your store page could not be found. Your changes are safe. Try again in a moment, and if it keeps happening contact support.</p>
        <Button variant="neutral" size="sm" onClick={onRetry}>
          Try again
        </Button>
      </div>
    );
  }
  return <ErrorState error={error} onRetry={onRetry} />;
}

/** Settings > Store page: the look of a seller's or provider's branded page, with a live preview, Publish and Unpublish. A provider's has its own sections (services, booking, schedule, team) and no product ones. */
export function StorePageView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const storefront = useStorefrontUrl();
  const { orgId } = useSeller();
  const page = useQuery(sellerQueries.storePage(api, orgId));
  const biz = useQuery(sellerQueries.businessPage(api, orgId));
  const preview = useQuery(sellerQueries.storePreview(api, orgId));
  const audit = useQuery(sellerQueries.storePageAudit(api, orgId));
  const [draft, setDraft] = useState<StoreConfig | null>(null);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<'edit' | 'preview'>('edit');
  const [width, setWidth] = useState<'desktop' | 'mobile'>('desktop');
  const [find, setFind] = useState('');
  const [picked, setPicked] = useState<Map<string, ProductSummary>>(new Map());
  const results = useQuery({ ...sellerQueries.storePageProducts(api, orgId, find.trim()), enabled: find.trim().length > 0 });

  useEffect(() => {
    if (page.data && !draft) {
      setDraft(page.data.draft);
      setVersion(page.data.version);
    }
  }, [page.data, draft]);
  useEffect(() => {
    if (!preview.data) return;
    setPicked((m) => new Map([...m, ...[...preview.data.featured, ...preview.data.products.hits].map((p) => [p.atin, p] as const)]));
  }, [preview.data]);
  useEffect(() => {
    if (results.data) setPicked((m) => new Map([...m, ...results.data.items.map((p) => [p.atin, p] as const)]));
  }, [results.data]);

  const accept = (s: StorePageSettings) => {
    qc.setQueryData(sellerKeys.storePage(orgId), s);
    void qc.invalidateQueries({ queryKey: sellerKeys.storePageAudit(orgId) });
    setVersion(s.version);
  };
  const onError = (e: unknown) => {
    const fields = isApiError(e) ? e.problem.fieldErrors : undefined;
    if (isApiError(e) && e.status === 409) {
      toast.show('Someone else changed the store page. Reload to see their changes.');
      setErrors({ form: problemMessage(e) });
      return;
    }
    setErrors(fields ? { ...fields, form: problemMessage(e) } : { form: problemMessage(e) });
  };
  const save = useMutation({
    mutationFn: (cfg: StoreConfig) => api.saveStorePageDraft(version, cfg),
    onSuccess: (s) => {
      accept(s);
      setErrors({});
      toast.show('Your changes are saved. The live page is unchanged until you publish.');
    },
    onError
  });
  const publish = useMutation({
    mutationFn: async (cfg: StoreConfig) => {
      let v = version;
      let s = page.data!;
      if (!sameConfig(cfg, s.draft)) {
        s = await api.saveStorePageDraft(v, cfg);
        v = s.version;
      }
      return api.publishStorePage(v);
    },
    onSuccess: (s) => {
      accept(s);
      setErrors({});
      toast.show('Your store page is live.');
    },
    onError
  });
  const unpublish = useMutation({
    mutationFn: () => api.unpublishStorePage(),
    onSuccess: (s) => {
      accept(s);
      toast.show('Your store page is unpublished. It shows a "not available" page until you publish again.');
    },
    onError
  });

  const local = useMemo(() => (draft ? localProblems(draft) : {}), [draft]);
  if (!page.data || !draft) {
    return <SettingsPage section="store-page">{page.isError ? <ErrorState error={page.error} onRetry={() => void page.refetch()} /> : <Spinner />}</SettingsPage>;
  }
  const s = page.data;
  const isProvider = s.kind === 'PROVIDER';
  const editable = s.canEdit;
  const patch = (fn: (d: StoreConfig) => StoreConfig) => setDraft((d) => (d ? fn(d) : d));
  const dirty = !sameConfig(draft, s.draft);
  const invalid = Object.keys(local).length > 0;
  const err = (path: string) => local[path] ?? errors[path];
  const href = s.slug ? `${storefront.replace(/\/+$/, '')}/b/${s.slug}` : null;
  const look = PRESETS[draft.theme.preset as ThemePreset];
  const brandColour = biz.data?.brandColor ?? null;
  const resolved = resolveTheme({ preset: draft.theme.preset as ThemePreset, accent: draft.theme.accent, brandColour });
  const categories = preview.data?.products.categories ?? [];
  const featuredProducts = draft.featured.map((a) => picked.get(a));

  const status = s.isPublished ? (s.hasUnpublishedChanges || dirty ? 'Published, with changes not yet live' : 'Published') : 'Not published';
  const reload = () => {
    setDraft(null);
    void page.refetch();
  };

  const editor = (
    <div className="flex min-w-0 flex-col gap-8" data-testid="store-editor">
      <Group id="sp-look" title="Look" help="Pick a look. Each one sets the colours, corners, type and layout together.">
        <div role="radiogroup" aria-label="Look" className="grid gap-3 sm:grid-cols-2">
          {THEME_PRESET_IDS.map((id) => {
            const p = PRESETS[id];
            const on = draft.theme.preset === id;
            return (
              <button
                key={id}
                type="button"
                role="radio"
                aria-checked={on}
                disabled={!editable}
                data-testid={`preset-${id}`}
                onClick={() => patch((d) => ({ ...d, theme: { preset: id, accent: d.theme.accent && accentIsSafe(d.theme.accent, p.canvas, p.surface) ? d.theme.accent : null }, hero: { ...d.hero, style: p.heroStyle === 'split' && !d.hero.bannerUrl ? 'colour' : p.heroStyle }, grid: { columns: p.gridColumns } }))}
                className={`flex min-h-11 cursor-pointer flex-col items-start gap-2 rounded-md border-2 p-3 text-start ${on ? 'border-text' : 'border-border'}`}
              >
                <span className="flex w-full gap-1" aria-hidden>
                  {[p.canvas, p.surface, p.accent, p.text].map((c, i) => (
                    <span key={i} className="h-6 flex-1 rounded-xs border border-border" style={{ background: c }} />
                  ))}
                </span>
                <strong className="text-md">{p.name}</strong>
                <span className="text-xs text-muted">{p.description}</span>
              </button>
            );
          })}
        </div>
        <Field label="Accent colour" help={`Used for buttons, prices and links. We keep it readable: contrast on this look is ${contrastRatio(resolved.accent, look.canvas).toFixed(1)}:1 (needs 4.5:1) and the text on it is chosen for you.`} error={err('theme.accent')}>
          {(a11y) => (
            <div className="flex flex-wrap items-center gap-3">
              <input
                {...a11y}
                type="color"
                aria-label="Pick an accent colour"
                disabled={!editable}
                value={resolved.accent}
                onChange={(e) => {
                  const safe = nearestSafeAccent(e.target.value, look.canvas, look.surface);
                  patch((d) => ({ ...d, theme: { ...d.theme, accent: safe } }));
                }}
                className="size-11 cursor-pointer rounded-sm border-0 bg-transparent p-0 sm:size-9"
                data-testid="accent-input"
              />
              <code className="text-sm" data-testid="accent-value">
                {resolved.accent}
              </code>
              {draft.theme.accent ? (
                <button type="button" className="cursor-pointer border-0 bg-transparent text-sm text-muted underline" onClick={() => patch((d) => ({ ...d, theme: { ...d.theme, accent: null } }))}>
                  Use the look's own colour
                </button>
              ) : brandColour ? (
                <span className="text-xs text-muted">Starts from your brand colour {brandColour}, made readable.</span>
              ) : null}
            </div>
          )}
        </Field>
      </Group>

      <Group id="sp-hero" title="Top of the page" help="Your name and logo come from Business profile.">
        <Segmented
          label="Top of the page style"
          value={draft.hero.style}
          disabled={!editable}
          options={[
            { id: 'image', name: 'Picture' },
            { id: 'colour', name: 'Colour' },
            { id: 'split', name: 'Split' }
          ]}
          onChange={(style) => patch((d) => ({ ...d, hero: { ...d.hero, style } }))}
        />
        <TextField label="Tagline" help={`${draft.hero.tagline.length}/140. One line that says what you sell, with no contact details.`} value={draft.hero.tagline} error={err('hero.tagline')} maxLength={160} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, hero: { ...d.hero, tagline: e.target.value } }))} />
        <StoreImageField
          label="Banner picture"
          help="JPG, PNG or WebP up to 8 MB, at least 1600 x 700. Used by the Picture and Split styles; Colour ignores it."
          value={draft.hero.bannerUrl}
          minWidth={1600}
          minHeight={700}
          aspect="16 / 7"
          disabled={!editable}
          testId="banner-input"
          focal={{ x: draft.hero.focalX, y: draft.hero.focalY, onChange: (x, y) => patch((d) => ({ ...d, hero: { ...d.hero, focalX: x, focalY: y } })) }}
          onChange={(bannerUrl) => patch((d) => ({ ...d, hero: { ...d.hero, bannerUrl } }))}
        />
        {err('hero.bannerUrl') ? <p role="alert" className="m-0 text-sm text-danger">{err('hero.bannerUrl')}</p> : null}
        <p className="m-0 text-sm text-muted">
          Logo: <Link href="/settings/business" className="text-link underline">change it in Business profile</Link>.
        </p>
      </Group>

      <Group id="sp-sections" title="Sections" help={isProvider ? 'Switch sections on or off and move them. The header and the top of the page are always there.' : 'Switch sections on or off and move them. The header, top of the page and All products are always there.'}>
        <ul className="m-0 flex list-none flex-col p-0" data-testid="section-list">
          {draft.sections.map((sec, i) => (
            <li key={sec.id} className="flex flex-wrap items-center gap-3 border-b border-border py-2 first:border-t" data-testid={`section-${sec.id}`}>
              <div className="min-w-0 flex-1">
                <Switch label={SECTION_COPY[sec.id].title} checked={sec.enabled} disabled={!editable} onChange={(v) => patch((d) => ({ ...d, sections: toggleSection(d.sections, sec.id, v) }))} />
                <span className="ms-14 block text-xs text-muted">{SECTION_COPY[sec.id].hint}</span>
              </div>
              <div className="flex gap-1">
                <button type="button" aria-label={`Move ${SECTION_COPY[sec.id].title} up`} disabled={!editable || i === 0} className="min-h-11 min-w-11 cursor-pointer rounded-sm border border-border-strong bg-surface disabled:opacity-40 sm:min-h-9 sm:min-w-9" onClick={() => patch((d) => ({ ...d, sections: moveSection(d.sections, sec.id, -1) }))}>
                  ↑
                </button>
                <button type="button" aria-label={`Move ${SECTION_COPY[sec.id].title} down`} disabled={!editable || i === draft.sections.length - 1} className="min-h-11 min-w-11 cursor-pointer rounded-sm border border-border-strong bg-surface disabled:opacity-40 sm:min-h-9 sm:min-w-9" onClick={() => patch((d) => ({ ...d, sections: moveSection(d.sections, sec.id, 1) }))}>
                  ↓
                </button>
              </div>
            </li>
          ))}
        </ul>
        {isProvider ? null : (
        <div className="flex flex-col gap-3">
          <strong className="text-sm">Products grid</strong>
          <Segmented label="Products per row" value={draft.grid.columns} disabled={!editable} options={[{ id: 3 as const, name: '3 per row' }, { id: 4 as const, name: '4 per row' }]} onChange={(columns) => patch((d) => ({ ...d, grid: { columns } }))} />
        </div>
        )}
      </Group>

      {draft.sections.find((x) => x.id === 'announcement')?.enabled ? (
        <Group id="sp-announcement" title="Announcement bar">
          <TextField label="Message" help={`${draft.announcement.text.length}/140`} value={draft.announcement.text} error={err('announcement.text')} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, announcement: { ...d.announcement, text: e.target.value } }))} />
          <TextField label="Link (optional)" placeholder="https://… or /b/yourstore?category=…" value={draft.announcement.href ?? ''} error={err('announcement.href')} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, announcement: { ...d.announcement, href: e.target.value.trim() || null } }))} />
        </Group>
      ) : null}

      {isProvider ? null : (
      <Group id="sp-featured" title="Featured products" help={`Pick up to ${MAX_FEATURED} products. Only products shoppers can buy now are listed here, and a featured product that sells out or loses its photo drops off the page by itself.`}>
        <TextField label="Search your products" placeholder="Name of a product" value={find} onChange={(e) => setFind(e.target.value)} disabled={!editable} data-testid="featured-search" />
        {find.trim() ? (
          <ul className="m-0 flex list-none flex-col p-0" data-testid="featured-results">
            {results.isLoading ? <li className="py-2 text-sm text-muted">Searching…</li> : null}
            {results.data?.items.length === 0 ? <li className="py-2 text-sm text-muted">No sellable product matches.</li> : null}
            {results.data?.items.map((p) => (
              <li key={p.atin} className="flex items-center gap-3 border-b border-border py-2">
                {p.imageUrl ? <img src={p.imageUrl} alt="" width={40} height={40} className="size-10 rounded-xs bg-surface-muted object-cover" /> : <span className="size-10 rounded-xs bg-surface-muted" />}
                <span className="min-w-0 flex-1 truncate text-sm">{p.title}</span>
                <Button type="button" variant="neutral" size="sm" disabled={!editable || draft.featured.includes(p.atin) || draft.featured.length >= MAX_FEATURED} onClick={() => patch((d) => ({ ...d, featured: addFeatured(d.featured, p.atin) }))}>
                  {draft.featured.includes(p.atin) ? 'Added' : 'Add'}
                </Button>
              </li>
            ))}
          </ul>
        ) : null}
        <div className="flex flex-col gap-1">
          <strong className="text-sm">
            Chosen ({draft.featured.length}/{MAX_FEATURED})
          </strong>
          {draft.featured.length === 0 ? <p className="m-0 text-sm text-muted">Nothing chosen yet.</p> : null}
          <ul className="m-0 flex list-none flex-col p-0" data-testid="featured-chosen">
            {draft.featured.map((atin, i) => {
              const p = featuredProducts[i];
              return (
                <li key={atin} className="flex items-center gap-3 border-b border-border py-2">
                  {p?.imageUrl ? <img src={p.imageUrl} alt="" width={40} height={40} className="size-10 rounded-xs bg-surface-muted object-cover" /> : <span className="size-10 rounded-xs bg-surface-muted" />}
                  <span className="min-w-0 flex-1 truncate text-sm">{p?.title ?? atin}</span>
                  <button type="button" aria-label="Move up" disabled={!editable || i === 0} className="min-h-9 min-w-9 cursor-pointer rounded-sm border border-border-strong bg-surface disabled:opacity-40" onClick={() => patch((d) => ({ ...d, featured: moveFeatured(d.featured, atin, -1) }))}>↑</button>
                  <button type="button" aria-label="Move down" disabled={!editable || i === draft.featured.length - 1} className="min-h-9 min-w-9 cursor-pointer rounded-sm border border-border-strong bg-surface disabled:opacity-40" onClick={() => patch((d) => ({ ...d, featured: moveFeatured(d.featured, atin, 1) }))}>↓</button>
                  <button type="button" disabled={!editable} className="min-h-9 cursor-pointer border-0 bg-transparent text-sm text-muted underline" onClick={() => patch((d) => ({ ...d, featured: removeFeatured(d.featured, atin) }))}>
                    Remove
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      </Group>
      )}

      {isProvider ? null : (
      <Group id="sp-categories" title="Shop by category" help="Categories come from your live products.">
        <Segmented label="Categories shown" value={draft.categories.mode} disabled={!editable} options={[{ id: 'auto' as const, name: 'All of mine' }, { id: 'chosen' as const, name: 'I choose' }]} onChange={(mode) => patch((d) => ({ ...d, categories: { ...d.categories, mode } }))} />
        {draft.categories.mode === 'chosen' ? (
          <div className="flex flex-wrap gap-2">
            {categories.map((c) => {
              const on = draft.categories.chosen.includes(c.name);
              return (
                <button key={c.name} type="button" aria-pressed={on} disabled={!editable} onClick={() => patch((d) => ({ ...d, categories: { ...d.categories, chosen: on ? d.categories.chosen.filter((x) => x !== c.name) : [...d.categories.chosen, c.name].slice(0, 8) } }))} className={`min-h-11 cursor-pointer rounded-sm border px-3 text-sm sm:min-h-9 ${on ? 'border-text bg-text text-canvas' : 'border-border-strong bg-surface'}`}>
                  {c.name} ({c.count})
                </button>
              );
            })}
          </div>
        ) : null}
      </Group>
      )}

      {isProvider ? (
        <Group id="sp-provider" title="Services, hours and team" help="Your services and prices, weekly opening hours, service area and team come from Provider Hub, so they are always the ones customers can really book. Change them there.">
          <ul className="m-0 flex list-none flex-wrap gap-x-6 gap-y-2 p-0 text-sm">
            <li>
              <Link href="/services" className="text-link underline">
                Services &amp; prices
              </Link>
            </li>
            <li>
              <Link href="/hours" className="text-link underline">
                Working hours
              </Link>
            </li>
            <li>
              <Link href="/settings/team" className="text-link underline">
                Team
              </Link>
            </li>
          </ul>
        </Group>
      ) : null}

      <Group id="sp-about" title="Our story" help={`Plain text. A blank line starts a new paragraph. No HTML. ${DIVERSION_POLICY}`}>
        <TextField label="Heading" value={draft.about.heading} placeholder="Our story" error={err('about.heading')} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, about: { ...d.about, heading: e.target.value } }))} />
        <Field label="Story" help={`${draft.about.body.length}/2000`} error={err('about.body')}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={6} value={draft.about.body} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, about: { ...d.about, body: e.target.value } }))} />}
        </Field>
        <StoreImageField label="Story photo" help="JPG, PNG or WebP up to 8 MB, at least 900 x 675." value={draft.about.photoUrl} minWidth={900} minHeight={675} aspect="4 / 3" disabled={!editable} testId="about-photo-input" onChange={(photoUrl) => patch((d) => ({ ...d, about: { ...d.about, photoUrl } }))} />
      </Group>

      {isProvider ? null : (
      <Group id="sp-policies" title="Shipping and returns" help="Your own words. Shoppers see them on your page, so say what you do.">
        <Field label="Shipping" help={`${draft.policies.shipping.length}/600`} error={err('policies.shipping')}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} value={draft.policies.shipping} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, policies: { ...d.policies, shipping: e.target.value } }))} />}
        </Field>
        <Field label="Returns" help={`${draft.policies.returns.length}/600`} error={err('policies.returns')}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} value={draft.policies.returns} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, policies: { ...d.policies, returns: e.target.value } }))} />}
        </Field>
        <Switch label="Show that customers can collect orders from me" checked={draft.pickup.show} disabled={!editable} onChange={(v) => patch((d) => ({ ...d, pickup: { show: v } }))} />
        <p className="m-0 text-xs text-muted">
          Shown with your ZIP only when pickup is on in <Link href="/settings/pickup" className="text-link underline">Settings &gt; Pickup</Link>. Turning this on here never turns pickup on.
        </p>
      </Group>
      )}

      <Group id="sp-contact" title="Contact and hours" help={`Opening hours are shown on your page. ${DIVERSION_POLICY}`}>
        <p data-testid="contact-via-messages" className="m-0 text-sm text-muted">Customers write to you through AfroTransact messages. Your login email is never shown.</p>
        <TextField label="Phone (optional)" help="Kept for you. It is not shown on your page: a customer sees it on their order only, for Pick up and FBA coordination." inputMode="tel" value={draft.contact.phone} error={err('contact.phone')} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, contact: { ...d.contact, phone: e.target.value } }))} />
        {isProvider ? null : <TextField label="Hours (optional)" placeholder="Mon to Fri, 9 am to 5 pm" value={draft.contact.hours} error={err('contact.hours')} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, contact: { ...d.contact, hours: e.target.value } }))} />}
      </Group>

      <Group id="sp-social" title="Social links" help="Full https links to your own profiles. Website links are no longer available: one you already have stays hidden on your page until your store has earned it, and you can remove it.">
        {draft.social.map((link, i) => (
          <div key={i} data-testid="social-row" className="grid gap-2 sm:grid-cols-[10rem_minmax(0,1fr)_auto] sm:items-start sm:gap-x-3">
            <Field label="Network">
              {(a11y) => (
                <select {...a11y} className={fieldStyles.control} value={link.network} disabled={!editable || link.network === 'website'} onChange={(e) => patch((d) => ({ ...d, social: d.social.map((x, j) => (j === i ? { ...x, network: e.target.value as StoreSocialNetwork } : x)) }))}>
                  {(link.network === 'website' ? [LEGACY_WEBSITE] : NETWORKS).map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <TextField label="Link" placeholder={[...NETWORKS, LEGACY_WEBSITE].find((n) => n.id === link.network)?.example} value={link.url} error={err(`social.${i}.url`)} disabled={!editable} onChange={(e) => patch((d) => ({ ...d, social: d.social.map((x, j) => (j === i ? { ...x, url: e.target.value } : x)) }))} />
            <ControlRowAction>
              <Button type="button" variant="neutral" className="max-sm:w-full" aria-label={`Remove ${[...NETWORKS, LEGACY_WEBSITE].find((n) => n.id === link.network)?.name ?? link.network} link`} disabled={!editable} onClick={() => patch((d) => ({ ...d, social: d.social.filter((_, j) => j !== i) }))}>
                Remove
              </Button>
            </ControlRowAction>
          </div>
        ))}
        {draft.social.length < MAX_SOCIAL ? (
          <Button type="button" variant="neutral" size="sm" disabled={!editable} onClick={() => patch((d) => ({ ...d, social: [...d.social, { network: 'instagram', url: '' }] }))}>
            Add a link
          </Button>
        ) : null}
      </Group>
    </div>
  );

  const previewPanel = (
    <div className="flex min-w-0 flex-col gap-3" data-testid="store-preview">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 text-lg font-semibold">Preview</h2>
        <Segmented label="Preview size" value={width} options={[{ id: 'desktop' as const, name: 'Desktop' }, { id: 'mobile' as const, name: 'Phone' }]} onChange={setWidth} />
      </div>
      <p className="m-0 text-xs text-muted" data-testid="store-preview-note">
        <strong>Preview, not published.</strong> This is your page as it looks now, with your unsaved changes. Buttons are switched off here.
      </p>
      {preview.data ? <StorePagePreview preview={preview.data} config={draft} brandColour={brandColour} width={width} /> : preview.isError ? <PreviewError error={preview.error} onRetry={() => void preview.refetch()} /> : <Spinner />}
    </div>
  );

  return (
    <SettingsPage section="store-page">
      <div className="flex flex-col gap-4 border-b border-border pb-4" data-testid="store-status">
        <div className="flex flex-wrap items-center gap-3">
          <strong className="text-md" data-testid="store-state">
            {status}
          </strong>
          {s.publishedAt ? <span className="text-sm text-muted">Live since {new Date(s.publishedAt).toLocaleDateString()}</span> : null}
          {href && s.isPublished ? (
            <a href={href} target="_blank" rel="noreferrer" className={buttonClassName({ variant: 'neutral', size: 'sm' })} data-testid="view-store">
              View store page
            </a>
          ) : null}
        </div>
        {!s.approved ? <p className="m-0 text-sm text-muted">You can build your page now. It can go live once your business is approved.</p> : null}
        {!s.slug ? <p className="m-0 text-sm text-muted">Your page address is made when your payout account is set up.</p> : null}
        {errors.form ? (
          <p role="alert" className="m-0 flex flex-wrap items-center gap-3 text-sm text-danger">
            {errors.form}
            {isApiError(save.error) && save.error.status === 409 ? <button type="button" className="cursor-pointer border-0 bg-transparent underline" onClick={reload}>Reload</button> : null}
          </p>
        ) : null}
        {editable ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="neutral" loading={save.isPending} disabled={!dirty || invalid} onClick={() => save.mutate(draft)} data-testid="save-draft">
              Save changes
            </Button>
            <Button type="button" loading={publish.isPending} disabled={!s.canPublish || invalid || (s.isPublished && !dirty && !s.hasUnpublishedChanges)} onClick={() => publish.mutate(draft)} data-testid="publish">
              {s.isPublished ? 'Publish changes' : 'Publish'}
            </Button>
            {s.isPublished ? (
              <Button type="button" variant="neutral" loading={unpublish.isPending} onClick={() => unpublish.mutate()} data-testid="unpublish">
                Unpublish
              </Button>
            ) : null}
            {dirty ? (
              <Button type="button" variant="neutral" onClick={() => { setDraft(s.draft); setErrors({}); }}>
                Discard changes
              </Button>
            ) : null}
          </div>
        ) : (
          <p className="m-0 text-sm text-muted">Only the owner and admins can change the store page.</p>
        )}
        <div className="flex gap-2 xl:hidden" role="tablist" aria-label="Editor or preview">
          {(['edit', 'preview'] as const).map((t) => (
            <button key={t} type="button" role="tab" aria-selected={tab === t} onClick={() => setTab(t)} className={`min-h-11 cursor-pointer rounded-sm border px-4 text-sm font-semibold ${tab === t ? 'border-text bg-text text-canvas' : 'border-border-strong bg-surface'}`}>
              {t === 'edit' ? 'Edit' : 'Preview'}
            </button>
          ))}
        </div>
      </div>
      <div className="grid items-start gap-8 xl:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
        <div className={tab === 'preview' ? 'hidden xl:block' : ''}>{editor}</div>
        <div className={`${tab === 'edit' ? 'hidden xl:block' : ''} xl:sticky xl:top-4`}>{previewPanel}</div>
      </div>
      <section aria-labelledby="sp-history" className="flex flex-col gap-2 border-t border-border pt-6">
        <h2 id="sp-history" className="m-0 text-lg font-semibold">
          History
        </h2>
        {audit.data?.items.length ? (
          <ul className="m-0 flex list-none flex-col p-0 text-sm" data-testid="store-audit">
            {audit.data.items.map((a, i) => (
              <li key={i} className="flex flex-wrap gap-3 border-b border-border py-2">
                <span className="font-semibold">{a.action === 'PUBLISHED' ? 'Published' : a.action === 'UNPUBLISHED' ? 'Unpublished' : 'Saved a draft'}</span>
                <span className="text-muted">{a.actor}</span>
                <span className="text-muted">{new Date(a.at).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="m-0 text-sm text-muted">Nothing yet.</p>
        )}
      </section>
    </SettingsPage>
  );
}
