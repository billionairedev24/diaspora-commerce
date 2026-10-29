'use client';

import { type PreviewField, type PreviewSection, sellerFormPreview, type TaxonomyProductType } from '@afrotransact/api-client';
import { useMemo } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

const SECTION_TITLE: Record<PreviewSection['section'], MessageKey> = {
  IDENTIFIER: 'st.catalog.preview.identifier',
  DETAILS: 'st.catalog.preview.details',
  FOOD: 'st.catalog.preview.food',
  REGULATED: 'st.catalog.preview.regulated'
};

const BOX = 'flex min-h-9 items-center rounded-sm border border-border-strong bg-ink-0 px-2 text-sm text-subtle';

function Control({ f }: { f: PreviewField }) {
  const t = useT();
  if (f.control === 'textarea') return <div className={`${BOX} min-h-16 items-start py-1`}>{t('st.catalog.preview.longText')}</div>;
  if (f.control === 'select') {
    return (
      <div className={`${BOX} justify-between`}>
        <span>{t('st.catalog.preview.choose')}</span>
        <span aria-hidden>▾</span>
      </div>
    );
  }
  if (f.control === 'number') {
    return (
      <div className="flex gap-2">
        <div className={`${BOX} flex-1`}>0</div>
        {f.units.length ? (
          <div className={`${BOX} justify-between gap-2`}>
            {f.units[0]}
            <span aria-hidden>▾</span>
          </div>
        ) : null}
      </div>
    );
  }
  return <div className={BOX}>{f.key === 'identifier' ? '' : t('st.catalog.preview.text')}</div>;
}

/**
 * The fields a seller's product form shows for the product type being edited, as the seller sees them: the identifier,
 * the details that apply (required ones starred) and the food and regulated sections only for those traits. It updates
 * with every change in the editor, before anything is saved.
 */
export function SellerFormPreview({ type }: { type: Pick<TaxonomyProductType, 'identifierRule' | 'traits' | 'attributes'> }) {
  const t = useT();
  const sections = useMemo(() => sellerFormPreview(type), [type]);
  return (
    <div data-testid="seller-form-preview" className="flex flex-col gap-3 rounded-md border border-border bg-ink-25 p-3" aria-label={t('st.catalog.preview.title')} role="group">
      <p className="m-0 text-xs font-bold tracking-wide text-muted uppercase">{t('st.catalog.preview.title')}</p>
      {sections.map((s) => (
        <section key={s.section} className="flex flex-col gap-2">
          <h4 className="m-0 border-b border-border pb-1 text-sm font-semibold">{t(SECTION_TITLE[s.section])}</h4>
          {s.fields.map((f) => (
            <div key={f.key} className="flex flex-col gap-1" data-testid={`preview-${f.key}`}>
              <span className="text-sm font-semibold">
                {f.label}
                {f.required ? <span aria-hidden> *</span> : <span className="font-normal text-muted"> {t('st.catalog.preview.optional')}</span>}
              </span>
              <Control f={f} />
              {f.help ? <span className="text-xs text-muted">{f.help}</span> : null}
              {f.control === 'select' && f.options.length ? (
                <span className="text-xs text-muted">
                  {f.options.slice(0, 6).join(' · ')}
                  {f.options.length > 6 ? ' …' : ''}
                </span>
              ) : null}
            </div>
          ))}
        </section>
      ))}
      {sections.length === 1 ? <p className="m-0 text-sm text-muted">{t('st.catalog.preview.none')}</p> : null}
    </div>
  );
}
