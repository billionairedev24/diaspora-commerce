import { type Cart, isApiError } from '@afrotransact/api-client';

import { useCart, useCartActions } from '@/api/queries';
import { useToast } from '@/ui/Toast';

export function lineFor(cart: Cart | undefined, atin: string) {
  return cart?.packages.flatMap((p) => p.lines).find((l) => l.atin === atin) ?? null;
}

/** Quantity + setter for one product, backed by the BFF cart (stock limits come back as 409 STOCK_LIMIT). */
export function useQuantity(atin: string, offerId: string) {
  const cart = useCart();
  const { add, update, remove } = useCartActions();
  const toast = useToast();
  const line = lineFor(cart.data, atin);
  const onError = (e: unknown) => toast(isApiError(e) ? e.message : 'Couldn’t update your cart');
  const setQty = (n: number) => {
    if (!line) {
      if (n > 0) add.mutate({ offerId, quantity: n }, { onError });
      return;
    }
    if (n <= 0) remove.mutate(line.lineId, { onError });
    else update.mutate({ lineId: line.lineId, quantity: Math.min(n, line.maxQuantity || 99) }, { onError });
  };
  return { qty: line?.quantity ?? 0, setQty, busy: add.isPending || update.isPending || remove.isPending };
}
