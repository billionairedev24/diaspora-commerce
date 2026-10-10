'use client';

import { type Cart, cartQueries, isApiError } from '@afrotransact/api-client';
import { useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useT } from '@/i18n/t';

import { browserApi } from '../api/browser';
import { useAnnounce } from './announcer';

export function useCart() {
  return useQuery(cartQueries.current(browserApi()));
}

/** Quantity of an offer currently in the cart. */
export function quantityInCart(cart: Cart | undefined, offerId: string): number {
  return cart?.packages.flatMap((p) => p.lines).find((l) => l.offerId === offerId)?.quantity ?? 0;
}

export function lineForOffer(cart: Cart | undefined, offerId: string) {
  return cart?.packages.flatMap((p) => p.lines).find((l) => l.offerId === offerId);
}

type CartMutation =
  | { kind: 'add'; offerId: string; quantity: number; title: string }
  | { kind: 'quantity'; lineId: string; quantity: number; title: string }
  | { kind: 'select'; lineId: string; selected: boolean }
  | { kind: 'selectAll'; selected: boolean }
  | { kind: 'remove'; lineId: string; title: string }
  | { kind: 'save'; lineId: string; title: string }
  | { kind: 'moveToCart'; savedId: string; title: string }
  | { kind: 'removeSaved'; savedId: string };

/** All cart mutations: server is the source of truth (stock caps); errors become toasts + announcements. */
export function useCartMutation() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const announce = useAnnounce();
  const api = browserApi();
  const key = cartQueries.current(api).queryKey;

  return useMutation({
    mutationFn: (m: CartMutation): Promise<Cart> => {
      switch (m.kind) {
        case 'add':
          return api.addToCart({ offerId: m.offerId, quantity: m.quantity });
        case 'quantity':
          return api.updateCartLine(m.lineId, { quantity: m.quantity });
        case 'select':
          return api.updateCartLine(m.lineId, { selected: m.selected });
        case 'selectAll':
          return api.selectAllCartLines(m.selected);
        case 'remove':
          return api.removeCartLine(m.lineId);
        case 'save':
          return api.saveForLater(m.lineId);
        case 'moveToCart':
          return api.moveToCart(m.savedId);
        case 'removeSaved':
          return api.removeSaved(m.savedId);
      }
    },
    onSuccess: (cart, m) => {
      qc.setQueryData(key, cart);
      let msg = '';
      if (m.kind === 'add') msg = t('cart.added', { title: m.title });
      else if (m.kind === 'quantity') msg = t('cart.addedCount', { title: m.title, n: m.quantity });
      else if (m.kind === 'remove') msg = t('cart.removed', { title: m.title });
      else if (m.kind === 'save') msg = t('cart.movedToSaved', { title: m.title });
      else if (m.kind === 'moveToCart') msg = t('cart.movedToCart', { title: m.title });
      if (msg) {
        if (m.kind === 'add' || m.kind === 'moveToCart') toast.show(msg);
        announce(`${msg}. ${t('cart.itemCount', { count: cart.itemCount })}`);
      }
    },
    onError: (err) => {
      const text = isApiError(err) ? err.message : t('auth.genericError');
      toast.show(text, 'error');
      announce(text);
      void qc.invalidateQueries({ queryKey: key });
    }
  });
}
