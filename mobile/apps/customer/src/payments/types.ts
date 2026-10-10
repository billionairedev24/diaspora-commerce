import { type OrderConfirmation } from '@/api/types';

export type PayResult =
  | { status: 'placed'; confirmation: OrderConfirmation }
  | { status: 'cancelled' }
  | { status: 'failed'; message: string };

export interface Payments {
  /** 'stripe' = native Stripe SDK (PaymentSheet); 'mock' = dev mock (EXPO_PUBLIC_MOCKS or web preview). */
  mode: 'stripe' | 'mock';
  /** Stripe PaymentSheet in setup mode (card with ZIP, US bank account via Financial Connections). */
  addPaymentMethod(setupIntentClientSecret: string): Promise<'added' | 'cancelled' | { error: string }>;
  /** Deferred-intent PaymentSheet built from the BFF checkout summary (wallets and new cards). The confirm handler
      places the order with the PaymentMethod the sheet produced and hands the PaymentIntent client secret back. */
  payWithSheet(opts: { amountMinor: number; currency: string; placeOrder: (paymentMethodId: string) => Promise<OrderConfirmation> }): Promise<PayResult>;
  /** 3-D Secure after placing an order with a saved card (OrderConfirmation.status = requires_action). */
  handleNextAction(paymentClientSecret: string): Promise<{ ok: true } | { ok: false; message: string }>;
  /** Booking deposit: PaymentSheet on the PaymentIntent bookings-service created. */
  payDeposit(paymentIntentClientSecret: string): Promise<'paid' | 'cancelled' | { error: string }>;
}
