/* "Ask us for a quote" (listing rules D20): a package outside every delivery zone offers quote_required instead of a
   delivery price, with a button that calls this.

   STUB. The delivery quote flow (a case in the staff queue, an amount agreed by customer care, a pay link valid for 48 hours)
   is built behind the quote_required key by its own change; it replaces this body. */
import { type CheckoutSummary } from './checkout';

export function onRequestQuote(pkg: CheckoutSummary['packages'][number]): void {
  void pkg;
}
