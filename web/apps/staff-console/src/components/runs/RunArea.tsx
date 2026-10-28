'use client';

import { type StaffRunLocation } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';

import { useT } from '@/i18n/t';

/** Where a location delivers, in words: a radius around the town, or the zone drawn for it in geo-service. */
export function useAreaText() {
  const t = useT();
  const fmt = useFormatters();
  return (location: Pick<StaffRunLocation, 'name' | 'area'> | null | undefined): string => {
    const area = location?.area;
    if (!location || !area) return t('st.runs.lc.area.unknown');
    return area.kind === 'RADIUS'
      ? t('st.runs.lc.area.RADIUS', { miles: fmt.number(area.radiusMiles ?? 0), town: location.name })
      : t('st.runs.lc.area.POLYGON', { town: location.name });
  };
}
