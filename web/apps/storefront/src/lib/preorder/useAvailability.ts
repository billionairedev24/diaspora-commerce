'use client';

import { type PreorderItemState, preorderItemState } from '@afrotransact/api-client';
import { type ProductCardAvailability } from '@afrotransact/ui';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';

import { type LivePreorder, useLivePreorder, usePreorderResolution } from './usePreorder';

export interface Availability {
  state: PreorderItemState;
  live: LivePreorder | null;
  /** Card badge / tag / lock; undefined in metros (the usual delivery promise shows). */
  card: ProductCardAvailability | undefined;
  /** Adding is blocked (window not open, or waitlist-only city). */
  locked: boolean;
}

/** Badge logic for a product (spec §5 Badge): pre-order tag by stage, "Ships now" for the rest, waitlist lock. */
export function useAvailability(eligible: boolean | undefined): Availability {
  const t = useT();
  const { data: r } = usePreorderResolution();
  const live = useLivePreorder(r);
  return useMemo(() => {
    const state = preorderItemState(eligible, r);
    if (state.kind === 'preorder' && live) {
      return {
        state,
        live,
        locked: state.locked,
        card: { badge: state.showBadge ? t('preorder.badge') : undefined, tag: live.copy.tag, tone: state.stage === 'OPEN' ? 'gold' : 'muted', lockedLabel: live.copy.addLabel }
      };
    }
    if (state.kind === 'ships-now') return { state, live, locked: false, card: { tag: t('preorder.tag.shipsNow'), tone: 'muted' } };
    if (state.kind === 'waitlist') return { state, live, locked: true, card: { tag: t('preorder.tag.waitlist'), tone: 'muted', lockedLabel: t('preorder.add.unavailable') } };
    return { state, live, locked: false, card: undefined };
  }, [eligible, r, live, t]);
}
