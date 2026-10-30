import { type ContentLocale } from '@afrotransact/api-client';

/**
 * The words around a legal document and a help page that the storefront prints in the shopper's language, copied from
 * the storefront catalogues (legal.version, legal.effective, legal.onPage, legal.translation, legal.draft,
 * help.hubCrumb, help.crumbAria) so the staff preview shows exactly what that shopper reads. A test keeps them equal
 * to the storefront's.
 */
export interface PreviewChrome {
  version: string;
  effective: string;
  onPage: string;
  translation: string;
  draft: string;
  helpCrumb: string;
  helpCrumbAria: string;
}

export const PREVIEW_CHROME: Record<ContentLocale, PreviewChrome> = {
  en: {
    version: 'Version {version}',
    effective: 'Effective',
    onPage: 'On this page',
    translation: 'This document is in English, which is the binding version.',
    draft: 'Draft for legal counsel review — not yet in force.',
    helpCrumb: 'Help center',
    helpCrumbAria: 'Breadcrumb'
  },
  fr: {
    version: 'Version {version}',
    effective: 'En vigueur le',
    onPage: 'Sur cette page',
    translation: 'La traduction française de ce document est en cours de relecture juridique. La version anglaise ci-dessous fait foi.',
    draft: 'Projet soumis à relecture juridique — pas encore en vigueur.',
    helpCrumb: 'Centre d’aide',
    helpCrumbAria: 'Fil d’Ariane'
  },
  es: {
    version: 'Versión {version}',
    effective: 'Vigente desde',
    onPage: 'En esta página',
    translation: 'La traducción al español de este documento está en revisión legal. La versión en inglés que aparece abajo es la que rige.',
    draft: 'Borrador para revisión legal — aún no está en vigor.',
    helpCrumb: 'Centro de ayuda',
    helpCrumbAria: 'Ruta de navegación'
  },
  ar: {
    version: 'الإصدار {version}',
    effective: 'ساري من',
    onPage: 'في هذه الصفحة',
    translation: 'الترجمة العربية لهذا المستند قيد المراجعة القانونية. النسخة الإنجليزية أدناه هي المعتمدة.',
    draft: 'مسودة قيد المراجعة القانونية — غير سارية بعد.',
    helpCrumb: 'مركز المساعدة',
    helpCrumbAria: 'مسار التنقل'
  },
  yo: {
    version: 'Ẹ̀dà {version}',
    effective: 'Ó bẹ̀rẹ̀ láti',
    onPage: 'Lójú ìwé yìí',
    translation: 'Ìtúmọ̀ Yorùbá ìwé yìí ṣì wà lábẹ́ àyẹ̀wò òfin. Ẹ̀dà Gẹ̀ẹ́sì tó wà nísàlẹ̀ ni ó ní àṣẹ.',
    draft: 'Àkọsílẹ̀ fún àyẹ̀wò agbẹjọ́rò — kò tíì ní ipa.',
    helpCrumb: 'Ibùdó ìrànlọ́wọ́',
    helpCrumbAria: 'Ìtọ́sọ́nà ojú-ọ̀nà'
  }
};

/** "1 September 2026" in the reader's language (calendar dates: noon UTC avoids a day shift), as the storefront prints it. */
export function formatLegalDate(iso: string, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(`${iso}T12:00:00Z`));
  } catch {
    return iso;
  }
}

/** The preview widths: a desktop column, a tablet and a phone. */
export const DEVICES = [
  { key: 'desktop', width: 1100 },
  { key: 'tablet', width: 768 },
  { key: 'phone', width: 390 }
] as const;

export type DeviceKey = (typeof DEVICES)[number]['key'];
