import { expect, type Page, test } from '@playwright/test';

/* Checkout (S282 + pickup), against the mock BFF:
   - one page: address, every seller group with Delivery or Pickup and all its items, payment, sticky order summary;
   - without a Stripe key: the labelled dev-only stand-in form, always visible;
   - with the mock BFF's test hook (cookie at_mock_stripe_pk) and a fake window.Stripe injected before the page loads:
     the storefront's real Elements code (deferred intent, inline Payment Element, confirmPayment). No Express Checkout.
   CHECKOUT_SHOTS_DIR=… saves full-page screenshots at 1280, 1024, 768 and 390 px. */

const SHOTS = process.env.CHECKOUT_SHOTS_DIR ?? process.env.STRIPE_SHOTS_DIR;

async function signInWithCart(page: Page, products: string[] = ['AT000000P2']) {
  await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
  for (const p of products) {
    await page.goto(`/p/${p}`);
    await page.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }).click();
    await expect(page.getByRole('complementary', { name: 'Buy box' }).getByText(/in cart$/)).toBeVisible();
  }
  await page.goto('/checkout');
  await expect(page.getByTestId('checkout-package').first()).toBeVisible();
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
                box.style.padding = '0';
                const row = (label: string, open: boolean) =>
                  `<div style="border-bottom:1px solid #e0e0e0;padding:12px 14px"><label style="display:flex;gap:10px;align-items:center;font-weight:600"><input type="radio" name="fake-pm" ${open ? 'checked' : ''}> ${label}</label>` +
                  (open ? '<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:12px"><input aria-label="Fake Stripe card number" placeholder="1234 1234 1234 1234" style="grid-column:1/-1;min-width:0;box-sizing:border-box;width:100%;height:40px;border:1px solid #c9c9c9;border-radius:6px;padding:0 10px"><input aria-label="Fake Stripe expiry" placeholder="MM / YY" style="min-width:0;box-sizing:border-box;width:100%;height:40px;border:1px solid #c9c9c9;border-radius:6px;padding:0 10px"><input aria-label="Fake Stripe CVC" placeholder="CVC" style="min-width:0;box-sizing:border-box;width:100%;height:40px;border:1px solid #c9c9c9;border-radius:6px;padding:0 10px"></div>' : '') +
                  '</div>';
                box.innerHTML = ['Card', 'Apple Pay', 'Klarna', 'Amazon Pay', 'Cash App Pay'].map((l, i) => row(l, i === 0)).join('');
                el.appendChild(box);
                box.addEventListener('input', () => on.change?.({ complete: true, empty: false, value: { type: 'card' } }));
                setTimeout(() => {
                  on.ready?.({});
                  on.change?.({ complete: false, empty: true, value: { type: 'card' } });
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

test('without a Stripe key the always-visible payment form is the labelled dev-only stand-in', async ({ page }) => {
  await signInWithCart(page);
  await expect(page.getByRole('radio', { name: /Use a new payment method/ })).toHaveCount(0);
  const notice = page.getByTestId('stripe-dev-notice');
  await expect(notice).toContainText('Stripe test mode is not configured');
  await expect(notice).toContainText('STRIPE_PUBLISHABLE_KEY and STRIPE_SECRET_KEY');
  await page.getByLabel('Card number').fill('4242 4242 4242 4242');
  await page.getByLabel('Expiration').fill('12 / 34');
  await page.getByLabel('Security code').fill('123');
  await page.getByLabel('ZIP').fill('77002');
  await page.getByRole('checkbox', { name: /Save for future purchases/ }).check();
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('the stand-in 3-D Secure card returns through /checkout/complete, which confirms with the BFF', async ({ page }) => {
  await signInWithCart(page);
  await page.getByLabel('Card number').fill('4000 0027 6000 3184');
  await page.getByLabel('Expiration').fill('12 / 34');
  await page.getByLabel('Security code').fill('123');
  await page.getByLabel('ZIP').fill('77002');
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('with a Stripe key: Payment Element only (deferred intent), submit → order → confirmPayment', async ({ page }) => {
  await useFakeStripe(page);
  await signInWithCart(page);
  await expect(page.locator('[data-fake-stripe="expressCheckout"]')).toHaveCount(0);
  await expect(page.locator('[data-fake-stripe="payment"]')).toBeVisible();
  await expect(page.getByTestId('stripe-dev-notice')).toHaveCount(0);
  await expect(page.getByRole('img', { name: 'Powered by Stripe' }).first()).toBeVisible();
  const elements = (await calls(page)).filter(([k]) => k === 'elements').map(([, o]) => o as Record<string, unknown>);
  expect(elements.at(-1)).toMatchObject({ mode: 'payment', currency: 'usd', captureMethod: 'automatic_async' });
  await page.getByLabel('Fake Stripe card number').fill('4242 4242 4242 4242');
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('with a Stripe key: a redirect (3-D Secure page) comes back to /checkout/complete and the order is placed', async ({ page }) => {
  await useFakeStripe(page, true);
  await signInWithCart(page);
  await expect(page.locator('[data-fake-stripe="payment"]')).toBeVisible();
  const elements = (await calls(page)).filter(([k]) => k === 'elements').map(([, o]) => o as Record<string, unknown>);
  expect(elements.at(-1)?.appearance).toMatchObject({ variables: { colorPrimary: expect.stringMatching(/\S/) } });
  await page.getByLabel('Fake Stripe card number').fill('4242 4242 4242 4242');
  const back = page.waitForURL(/\/checkout\/complete\?order=AT-2026-.*redirect_status=succeeded/);
  await page.getByRole('button', { name: 'Place your order' }).click();
  await back;
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
});

test('pickup: choose an AfroTransact pickup point, no shipping charge, the confirmation names the point', async ({ page }) => {
  await useFakeStripe(page);
  await signInWithCart(page);
  const pkg = page.getByTestId('checkout-package').first();
  await pkg.getByTestId('fulfil-pickup').click();
  await expect(pkg.getByTestId('pickup-point').first()).toBeVisible();
  await expect(page.getByTestId('order-summary')).toContainText('no shipping charge');
  await page.getByRole('button', { name: 'Place your order' }).click();
  await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
  await expect(page.getByTestId('order-pickup').first()).toBeVisible();
});

for (const width of [1280, 1024, 768, 390]) {
  test(`looks right at ${width}px: summary rows on one line, compact payment form`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await useFakeStripe(page);
    await signInWithCart(page, ['AT000000P2', 'AT00000P23']);
    await expect(page.locator('[data-fake-stripe="payment"]')).toBeVisible();
    const summary = page.getByTestId('order-summary');
    for (const label of ['Shipping', 'Tax (estimated)', 'Order total']) {
      const dt = summary.locator('dt', { hasText: label });
      const box = await dt.boundingBox();
      const lineHeight = await dt.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight) || 24);
      expect(box?.height ?? 0, `${label} wraps at ${width}px`).toBeLessThan(lineHeight * 1.6);
    }
    if (width >= 1000) {
      const aside = await page.getByRole('complementary', { name: 'Order summary' }).boundingBox();
      expect(aside?.width ?? 0).toBeGreaterThanOrEqual(360);
    }
    const form = await page.getByTestId('stripe-payment-element').boundingBox();
    expect(form?.width ?? 0).toBeLessThanOrEqual(Math.min(width, 600));
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/checkout-${width}.png`, fullPage: true });
    if (SHOTS && width === 1280) {
      await page.getByTestId('checkout-package').first().getByTestId('fulfil-pickup').click();
      await page.screenshot({ path: `${SHOTS}/checkout-${width}-pickup.png`, fullPage: true });
    }
  });
}
