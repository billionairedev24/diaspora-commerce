'use client';

import { type PreorderItemState, preorderItemState, type PreorderPlace, preorderQueries, type PreorderResolution, type PreorderWindow } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import { type PreorderDateLabels, preorderDates, type StageCopy,stageCopy } from './copy';

/** Resolution for the shopper's delivery location (or an explicit place for a window page). */
export function usePreorderResolution(place?: PreorderPlace) {
  return useQuery(preorderQueries.resolution(browserApi(), place));
}

export function useOpenPreorders() {
  return useQuery(preorderQueries.open(browserApi()));
}

/** Badge / add-to-cart state of a product for the shopper's place. */
export function usePreorderItem(eligible: boolean | undefined): PreorderItemState {
  const { data } = usePreorderResolution();
  return preorderItemState(eligible, data);
}

export interface LivePreorder {
  r: PreorderResolution;
  w: PreorderWindow;
  stage: NonNullable<PreorderResolution['stage']>;
  dates: PreorderDateLabels;
  copy: StageCopy;
  city: string;
  doorFee: string;
}

/** The live (on) pre-order with its formatted dates and stage copy, or null where the pre-order is off. */
export function useLivePreorder(r: PreorderResolution | undefined): LivePreorder | null {
  const t = useT();
  const fmt = useFormatters();
  return useMemo(() => {
    if (!r?.on || !r.window || !r.stage) return null;
    const w = r.window;
    const dates = preorderDates(w, fmt.tag);
    const doorFee = fmt.money(w.doorFeeMinor, w.currency);
    const city = r.place.city;
    return { r, w, stage: r.stage, dates, city, doorFee, copy: stageCopy(t, { stage: r.stage, city, ribbon: w.ribbon, headline: w.headline, phone: r.supportPhone, dates, doorFee }) };
  }, [r, t, fmt]);
}
