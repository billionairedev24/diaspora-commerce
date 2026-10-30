'use client';

import { CONTENT_LOCALES, type ContentKind, type ContentLocale } from '@afrotransact/api-client';
import { countLegalMarkers, fillPlaceholders, HelpPageContent, LegalDocumentContent, LegalDraftBanner, LegalNote, outlineOf, parseSections } from '@afrotransact/content';
import { cx } from '@afrotransact/ui';
import { Desktop,DeviceMobile, DeviceTablet } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';

import { type DeviceKey, DEVICES, formatLegalDate, PREVIEW_CHROME } from './chrome';

/** Language names in their own language, as the storefront's language menu shows them. */
export const LOCALE_NAME: Record<ContentLocale, string> = { en: 'English', fr: 'Français', es: 'Español', ar: 'العربية', yo: 'Yorùbá' };

/** The text of a page as the preview needs it: published, draft, unsaved edit or a past version. */
export interface PreviewDoc {
  version: string;
  effectiveDate: string | null;
  draftBanner: boolean | null;
  title: string;
  bodies: Readonly<Record<string, string>>;
  needsReview?: readonly string[];
}

const DEVICE_ICON = { desktop: Desktop, tablet: DeviceTablet, phone: DeviceMobile } as const;

/** Segmented button group (language, device). */
function Segmented<V extends string>({ label, value, onChange, items }: { label: string; value: V; onChange: (v: V) => void; items: { value: V; label: ReactNode; title?: string }[] }) {
  return (
    <div role="group" aria-label={label} className="inline-flex flex-wrap gap-1">
      {items.map((it) => (
        <button
          key={it.value}
          type="button"
          aria-pressed={it.value === value}
          title={it.title}
          onClick={() => onChange(it.value)}
          className={cx(
            'inline-flex min-h-9 items-center gap-1.5 rounded-pill border px-3 py-1 text-sm font-semibold pointer-coarse:min-h-11',
            it.value === value ? 'border-ink-1000 bg-ink-1000 text-inverse' : 'border-border bg-surface text-text hover:bg-surface-hover'
          )}
        >
          {it.label}
        </button>
      ))}
    </div>
  );
}

export interface PreviewControlsProps {
  locales: readonly ContentLocale[];
  locale: ContentLocale;
  onLocale: (l: ContentLocale) => void;
  device: DeviceKey;
  onDevice: (d: DeviceKey) => void;
  /** Languages the previewed text has a translation for. */
  translated: readonly string[];
  needsReview: readonly string[];
}

/** Language switcher and desktop / tablet / phone width toggle. */
export function PreviewControls({ locales, locale, onLocale, device, onDevice, translated, needsReview }: PreviewControlsProps) {
  const t = useT();
  const shown = CONTENT_LOCALES.filter((l) => locales.includes(l));
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      {shown.length > 1 ? (
        <Segmented<ContentLocale>
          label={t('st.pages.preview.language')}
          value={locale}
          onChange={onLocale}
          items={shown.map((l) => ({
            value: l,
            title: l !== 'en' && !translated.includes(l) ? t('st.pages.preview.noTranslationShort') : needsReview.includes(l) ? t('st.pages.locale.needsReview') : undefined,
            label: (
              <>
                {LOCALE_NAME[l]}
                {l !== 'en' && !translated.includes(l) ? <span aria-hidden className="text-2xs font-normal opacity-70">·EN</span> : null}
                {needsReview.includes(l) ? <span aria-hidden className="size-2 rounded-full bg-warning" /> : null}
              </>
            )
          }))}
        />
      ) : (
        <span className="text-sm text-muted">{t('st.pages.preview.englishOnly')}</span>
      )}
      <Segmented<DeviceKey>
        label={t('st.pages.preview.width')}
        value={device}
        onChange={onDevice}
        items={DEVICES.map((d) => {
          const Icon = DEVICE_ICON[d.key];
          return { value: d.key, title: `${d.width}px`, label: (<><Icon size={16} aria-hidden />{t(`st.pages.preview.${d.key}`)}</>) };
        })}
      />
    </div>
  );
}

/**
 * The page as shoppers will read it, rendered by the storefront's own components (@afrotransact/content) at the chosen
 * width and language: a missing translation shows the English text with a notice, the draft banner shows when the
 * document is a draft for counsel, and `[[LEGAL: …]]` markers are highlighted and counted so none is published by accident.
 */
export function ContentPreview({ kind, doc, placeholders, locale, device, stageLabel }: { kind: ContentKind; doc: PreviewDoc; placeholders: Readonly<Record<string, string>>; locale: ContentLocale; device: DeviceKey; stageLabel?: string }) {
  const t = useT();
  const width = DEVICES.find((d) => d.key === device)?.width ?? 1100;
  const missing = locale !== 'en' && !doc.bodies[locale];
  const served = missing ? 'en' : locale;
  const raw = doc.bodies[served] ?? doc.bodies.en ?? '';
  const markers = countLegalMarkers(raw);
  const chrome = PREVIEW_CHROME[locale];
  const dir = locale === 'ar' ? 'rtl' : 'ltr';
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {missing ? (
        <Notice tone="warning" title={t('st.pages.preview.noTranslation', { language: LOCALE_NAME[locale] })}>
          {t('st.pages.preview.fallbackBody')}
        </Notice>
      ) : null}
      {kind === 'legal' && markers > 0 ? (
        <Notice tone="warning" role="alert" title={t('st.pages.preview.markers', { n: markers })}>
          {t('st.pages.preview.markersBody')}
        </Notice>
      ) : null}
      <div className="overflow-x-auto rounded-md border border-border bg-surface-muted p-2 sm:p-4">
        <div
          data-testid="preview-stage"
          data-device={device}
          role="region"
          aria-label={stageLabel ?? t('st.pages.preview.stage')}
          lang={locale}
          dir={dir}
          style={{ maxWidth: width }}
          className="mx-auto overflow-hidden rounded-sm border border-border bg-surface px-4 pb-8 [--at-page-gutter:16px]"
        >
          {kind === 'legal' ? (
            <>
              {doc.draftBanner ? <LegalDraftBanner>{chrome.draft}</LegalDraftBanner> : null}
              <div className="pt-8">
                <LegalDocumentContent
                  title={doc.title}
                  body={fillPlaceholders(raw, placeholders)}
                  outline={outlineOf(fillPlaceholders(raw, placeholders))}
                  bodyLang={served}
                  bodyDir={served === 'ar' ? 'rtl' : 'ltr'}
                  versionLabel={chrome.version.replace('{version}', doc.version)}
                  effectiveLabel={chrome.effective}
                  effectiveDate={doc.effectiveDate ?? ''}
                  effectiveText={doc.effectiveDate ? formatLegalDate(doc.effectiveDate, locale) : ''}
                  tocLabel={chrome.onPage}
                  titleId={`preview-title-${device}`}
                  highlightMarkers
                  notes={locale !== 'en' ? <LegalNote>{chrome.translation}</LegalNote> : null}
                />
              </div>
            </>
          ) : (
            <HelpPageContent
              title={doc.title}
              sections={parseSections(raw)}
              titleId={`preview-title-${device}`}
              highlightMarkers
              crumb={
                <nav aria-label={chrome.helpCrumbAria} className="flex flex-wrap items-center gap-2 text-sm text-muted">
                  <span className="text-link">{chrome.helpCrumb}</span>
                  <span aria-hidden>›</span>
                  <span aria-current="page">{doc.title}</span>
                </nav>
              }
            />
          )}
        </div>
      </div>
    </div>
  );
}
