/* A fake Stripe.js (window.Stripe) for component tests: records every call in order and lets a test decide the
   elements.submit() and stripe.confirmPayment() results. */
import { act } from '@testing-library/react';
import { vi } from 'vitest';

import { type ElementsOptions, type PaymentIntentResult, type StripeElementChange, type StripeInstance } from '@/lib/payments/stripe';

export type Handler = (e: StripeElementChange & Record<string, unknown>) => void;

export interface FakeElements {
  options: ElementsOptions;
  created: string[];
  handlers: Record<string, Record<string, Handler>>;
  updates: Record<string, unknown>[];
}

/** A fake Stripe.js: records every call in order and lets a test decide submit / confirm results. */
export function fakeStripe() {
  const log: string[] = [];
  const elements: FakeElements[] = [];
  const confirms: Parameters<StripeInstance['confirmPayment']>[0][] = [];
  const results: { submit: { error?: { type?: string; message?: string } }; confirm: PaymentIntentResult } = {
    submit: {},
    confirm: { paymentIntent: { id: 'pi_1', status: 'requires_capture' } }
  };
  const factory = vi.fn((key: string): StripeInstance => {
    log.push(`Stripe(${key})`);
    return {
      elements: (options) => {
        const rec: FakeElements = { options, created: [], handlers: {}, updates: [] };
        elements.push(rec);
        return {
          create: (type) => {
            rec.created.push(type);
            const on: Record<string, Handler> = {};
            rec.handlers[type] = on;
            return {
              mount: (el: HTMLElement) => {
                el.setAttribute('data-fake-stripe', type);
                setTimeout(() => act(() => on.ready?.({})), 0);
              },
              unmount: () => undefined,
              on: (event: string, fn: Handler) => {
                on[event] = fn;
              }
            };
          },
          submit: async () => {
            log.push(`submit:${rec.created.join('+')}`);
            return results.submit;
          },
          update: (u) => {
            rec.updates.push(u);
          }
        };
      },
      confirmPayment: async (o) => {
        log.push('confirmPayment');
        confirms.push(o);
        return results.confirm;
      },
      confirmSetup: async () => ({})
    };
  });
  const payment = () => elements.find((e) => e.created.includes('payment'));
  const express = () => elements.find((e) => e.created.includes('expressCheckout'));
  return { factory, log, elements, confirms, results, payment, express };
}

