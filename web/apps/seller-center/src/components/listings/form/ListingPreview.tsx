import { useT } from '@/i18n/t';

interface Props {
  title: string;
  imageUrl: string | null;
  price: string;
  options: number;
  condition: string | null;
  attributes: { label: string; value: string }[];
}

/** How the listing will look on the storefront: main image, title, price and the key attributes a shopper sees. */
export function ListingPreview({ title, imageUrl, price, options, condition, attributes }: Props) {
  const t = useT();
  return (
    <div role="group" aria-label={t('sc.pf.preview')} data-testid="listing-preview" className="flex flex-col gap-2">
      <span className="text-xs font-semibold text-muted">{t('sc.pf.preview')}</span>
      <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-2.5">
        {imageUrl ? <img src={imageUrl} alt="" className="aspect-square w-full rounded-sm object-cover" />
          : <div className="grid aspect-square w-full place-items-center rounded-sm bg-surface-muted text-xs text-subtle">{t('sc.pf.noImage')}</div>}
        <span className="line-clamp-2 text-sm leading-tight font-medium" data-testid="preview-title">{title || t('sc.pf.previewTitle')}</span>
        <strong className="text-base" data-testid="preview-price">{price}</strong>
        {options > 1 ? <span className="text-xs text-muted">{t('sc.pf.previewOptions', { count: options })}</span> : null}
        {condition ? <span className="text-xs text-muted">{condition}</span> : null}
        {attributes.length ? (
          <dl className="m-0 flex flex-col gap-0.5 border-t border-border pt-2 text-xs" data-testid="preview-attrs">
            {attributes.map((a) => (
              <div key={a.label} className="flex gap-1.5">
                <dt className="flex-none text-muted">{a.label}</dt>
                <dd className="m-0 min-w-0 truncate font-medium">{a.value}</dd>
              </div>
            ))}
          </dl>
        ) : null}
      </div>
    </div>
  );
}
