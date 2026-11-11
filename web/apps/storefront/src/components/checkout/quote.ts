import { type CheckoutPackage } from '@afrotransact/api-client';

/**
 * "Ask us for a quote" (listing rules D20): the button of a package outside every delivery zone calls this.
 *
 * STUB. The delivery quote flow (a case in the staff queue, an amount agreed by customer care, a pay link valid for 48
 * hours) is built behind the `quote_required` key by its own change; it replaces this body. Until then it only records that
 * the shopper asked, so the button is wired and testable.
 */
export function onRequestQuote(pkg: CheckoutPackage): void {
  void pkg;
}
