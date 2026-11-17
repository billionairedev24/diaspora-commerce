'use client';

import { BRAND_PALETTE } from '@afrotransact/tokens';
import { type BrandColourLabels, BrandColourPicker } from '@afrotransact/ui';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';

/** The shared brand-colour control (packages/ui) in the active language: onboarding, Business page and the Provider Hub all use this one. */
export function BrandColourField({ id, value, onChange, error, disabled }: { id?: string; value: string; onChange: (value: string) => void; error?: string | null; disabled?: boolean }) {
  const t = useT();
  const labels = useMemo<BrandColourLabels>(
    () => ({
      label: t('sc.brandColour.label'),
      optional: t('sc.brandColour.optional'),
      help: t('sc.brandColour.help'),
      defaultName: t('sc.brandColour.defaultName'),
      defaultHelp: t('sc.brandColour.defaultHelp'),
      custom: t('sc.brandColour.custom'),
      picker: t('sc.brandColour.picker'),
      hex: t('sc.brandColour.hex'),
      hexHelp: t('sc.brandColour.hexHelp'),
      invalid: t('sc.brandColour.invalid'),
      preview: t('sc.brandColour.preview'),
      previewButton: t('sc.brandColour.previewButton'),
      selected: (name, hex) => (hex ? t('sc.brandColour.selected', { name, hex }) : t('sc.brandColour.selectedNoHex', { name })),
      names: Object.fromEntries(BRAND_PALETTE.map((s) => [s.id, t(`sc.brandColour.name.${s.id}` as 'sc.brandColour.name.green')]))
    }),
    [t]
  );
  return <BrandColourPicker id={id} value={value} onChange={onChange} labels={labels} error={error} disabled={disabled} />;
}
