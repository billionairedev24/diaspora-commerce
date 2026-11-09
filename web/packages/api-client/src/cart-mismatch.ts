import { isApiError } from './errors';

/** Problem codes that mean the cart no longer matches what the shopper asked to check out (a line removed or reduced,
    nothing left to buy, an item that cannot be checked out). Their detail can name an internal ATIN, which is never shown. */
const CART_MISMATCH_CODES = new Set([
  'orders.line-not-in-cart',
  'orders.cart-changed',
  'orders.cart-empty',
  'orders.cart-not-checkoutable'
]);

/** True for a checkout problem that is fixed by reviewing the cart. Show a plain "your cart changed" message with a
    link back to the cart instead of the problem's detail. */
export function isCartMismatch(error: unknown): boolean {
  return isApiError(error) && !!error.code && CART_MISMATCH_CODES.has(error.code);
}
