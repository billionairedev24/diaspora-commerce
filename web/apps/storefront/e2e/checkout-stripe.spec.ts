import { expect, type Page, test } from '@playwright/test';

/* Checkout payment step with Stripe Elements (S282), against the mock BFF:
   - without a Stripe key: the labelled dev-only stand-in form;
   - with the mock BFF's test hook (cookie at_mock_stripe_pk) and a fake window.Stripe injected before the page loads:
     the storefront's real Elements code (deferred intent, inline Payment Element, Express Checkout, confirmPayment). */

const SHOTS = process.env.STRIPE_SHOTS_DIR;

async function signInWithCart(page: Page) {
  await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
  await page.goto('/p/AT000000P2');
  await page.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }).click();
  await expect(page.getByRole('complementary', { name: 'Buy box' }).getByText(/in cart$/)).toBeVisible();
  await page.goto('/checkout');
  await page.getByRole('button', { name: 'Deliver to this address' }).click();
}

/** A stand-in for js.stripe.com: renders a visible "element", records calls on window.__stripeCalls. */
function installFakeStripe() {
  type Handler = (e: Record<string, unknown>) => void;
  const w = window as unknown as Record<string, unknown> & { __stripeCalls: unknown[] };
  w.__stripeCalls = [];
  const calls = w.__stripeCalls;
  w.Stripe = (key: string) => {
    calls.push(['Stripe', key]);
    return {
      elements: (options: Record<string, unknown>) => {
        calls.push(['elements', options]);
        return {
          create: (type: string) => {
            const on: Record<string, Handler> = {};
            return {
              mount: (el: HTMLElement) => {
                calls.push(['mount', type]);
                const box = document.createElement('div');
                box.setAttribute('data-fake-stripe', type);
                box.style.cssText = 'border:1px solid #c9c9c9;border-radius:6px;padding:12px;font:14px Figtree,sans-serif;background:#fff';
                if (type === 'payment') {
                  box.innerHTML = '<div style="display:flex;gap:8px;margin-bottom:10px"><span style="flex:1;border:2px solid #1E7B3C;border-radius:6px;padding:8px;color:#17602F;font-weight:600">Card</span><span style="flex:1;border:1px solid #c9c9c9;border-radius:6px;padding:8px">Cash App Pay</span><span style="flex:1;border:1px solid #c9c9c9;border-radius:6px;padding:8px">Affirm</span><span style="flex:1;border:1px solid #c9c9c9;border-radius:6px;padding:8px">US bank</span></div><label>Card number <input aria-label="Fake Stripe card number" value="4242 4242 4242 4242" style="width:100%"></label><p style="margin:8px 0 0;color:#666">Fake Stripe Payment Element (test)</p>';
                } else {
                  box.textContent = 'Fake Express Checkout: Apple Pay · Google Pay · Link';
                }
                el.appendChild(box);
                setTimeout(() => {
                  on.ready?.({});
                  on.change?.({ complete: true, value: { type: 'card' } });
                }, 0);
              },
              unmount: () => undefined,
              on: (event: string, fn: Handler) => {
                on[event] = fn;
              }
            };
          },
          submit: async () => {
            calls.push(['submit']);
            return {};
          },
          update: (u: Record<string, unknown>) => calls.push(['update', u])
        };
      },
      confirmPayment: async (o: { clientSecret: string; confirmParams?: { return_url?: string } }) => {
        calls.push(['confirmPayment', { clientSecret: o.clientSecret, returnUrl: o.confirmParams?.return_url }]);
        if (localStorage.getItem('fake-stripe-3ds') === '1') {
          window.location.assign(`${o.confirmParams?.return_url}&payment_intent=pi_fake&redirect_status=succeeded`);
          return new Promise(() => undefined);
        }
        return { paymentIntent: { id: 'pi_fake', status: 'requires_capture' } };
      },
      confirmSetup: async () => ({})
    };
  };
}

async function useFakeStripe(page: Page, threeDs = false) {
  await page.context().addCookies([{ name: 'at_mock_stripe_pk', value: 'pk_test_fake_e2e', domain: 'localhost', path: '/' }]);
  await page.addInitScript(installFakeStripe);
  if (threeDs) await page.addInitScript(() => localStorage.setItem('fake-stripe-3ds', '1'));
}

const calls = (page: Page) => page.evaluate(() => (window as unknown as { __stripeCalls: [string, unknown?][] }).__stripeCalls);

test('without a Stripe key the payment step shows the labelled dev-only stand-in form', async ({ page }) => {
  await signInWithCart(page);
  await page.getByRole('radio', { name: /Use a new payment method/ }).check();
  const notice = page.getByTestId('stripe-dev-notice');
  await expect(notice).toContainText('Stripe test mode is not configured');
  await expect(notice).toContainText('STRIPE_PUBLISHABLE_KEY and STRIPE_SECRET_KEY');
  await page.getByLabel('Card number').fill('4242 4242 4242 4242');
  await page.getByLabel('Expiration').fill('12 / 34');
  await page.getByLabel('Security code').fill('123');
  await page.getByLabel('ZIP').fill('77002');
  await page.getByRole('checkbox', { name: /Save for future purchases/ }).check();
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/checkout-payment-mock-fallback.png`, fullPage: true });
  await page.getByRole('button', { name: 'Use this payment method' }).click();
  await expect(page.getByTestId('checkout-new-method-summary')).toContainText('Card');
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('the stand-in 3-D Secure card returns through /checkout/complete, which confirms with the BFF', async ({ page }) => {
  await signInWithCart(page);
  await page.getByRole('radio', { name: /Use a new payment method/ }).check();
  await page.getByLabel('Card number').fill('4000 0027 6000 3184');
  await page.getByLabel('Expiration').fill('12 / 34');
  await page.getByLabel('Security code').fill('123');
  await page.getByLabel('ZIP').fill('77002');
  await page.getByRole('button', { name: 'Use this payment method' }).click();
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('with a Stripe key: inline Payment Element (deferred intent), Express Checkout on top, submit → order → confirmPayment', async ({ page }) => {
  await useFakeStripe(page);
  await signInWithCart(page);
  await expect(page.locator('[data-fake-stripe="expressCheckout"]')).toBeVisible();
  await page.getByRole('radio', { name: /Use a new payment method/ }).check();
  await expect(page.locator('[data-fake-stripe="payment"]')).toBeVisible();
  await expect(page.getByTestId('stripe-dev-notice')).toHaveCount(0);
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/checkout-payment-fake-stripe.png`, fullPage: true });
  await page.getByRole('button', { name: 'Use this payment method' }).click();
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('with a Stripe key: a redirect (3-D Secure page) comes back to /checkout/complete and the order is placed', async ({ page }) => {
  await useFakeStripe(page, true);
  await signInWithCart(page);
  await page.getByRole('radio', { name: /Use a new payment method/ }).check();
  await expect(page.locator('[data-fake-stripe="payment"]')).toBeVisible();
  const elements = (await calls(page)).filter(([k]) => k === 'elements').map(([, o]) => o as Record<string, unknown>);
  expect(elements.at(-1)).toMatchObject({ mode: 'payment', currency: 'usd', captureMethod: 'manual' });
  expect(elements.at(-1)?.appearance).toMatchObject({ variables: { colorPrimary: expect.stringMatching(/\S/) } });
  await page.getByRole('button', { name: 'Use this payment method' }).click();
  const back = page.waitForURL(/\/checkout\/complete\?order=AT-2026-.*redirect_status=succeeded/);
  await page.getByRole('button', { name: 'Place your order' }).click();
  await back;
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});
