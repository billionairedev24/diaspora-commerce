import { type SiteCommunity, type SiteDepartment } from '@afrotransact/api-client';

import { EN_MESSAGES, type MessageKey, type Translator } from '@/i18n/t';

/** A department's display name in the shopper's language: the gateway's label for that locale, else the catalog's `dept.<key>` translation, else the catalog name. */
export function departmentLabel(d: Pick<SiteDepartment, 'key' | 'name' | 'label' | 'labels' | 'labelKey'>, locale: string, t: Translator<MessageKey>): string {
  const own = d.labels[locale as keyof SiteDepartment['labels']];
  if (own) return own;
  const key = d.labelKey ?? `dept.${d.key}`;
  if (locale !== 'en' && Object.prototype.hasOwnProperty.call(EN_MESSAGES, key)) return t(key as MessageKey);
  return d.labels.en || d.label || d.name;
}

export function communityLabel(c: Pick<SiteCommunity, 'label' | 'labels'>, locale: string): string {
  return c.labels[locale as keyof SiteCommunity['labels']] || c.label;
}
