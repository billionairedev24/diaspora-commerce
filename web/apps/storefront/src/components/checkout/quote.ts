import { type CheckoutPackage } from '@afrotransact/api-client';

/**
 * "Ask us for a quote" (listing rules D20): the button of a package outside every delivery zone calls this.
 *
 * The request itself is the delivery quote flow's own box (AskForQuote, above the packages: a case in the staff queue,
 * an amount agreed by customer care, a pay link valid for 48 hours). This handler takes the shopper to it, so the button
 * is never a dead end. It is the one seam the flow can replace.
 */
export function onRequestQuote(pkg: CheckoutPackage): void {
  void pkg;
  const box = document.querySelector<HTMLElement>('[data-testid="ask-for-quote"]');
  if (!box) return;
  box.scrollIntoView({ behavior: 'smooth', block: 'start' });
  box.querySelector<HTMLElement>('button, select, textarea, input')?.focus({ preventScroll: true });
}
