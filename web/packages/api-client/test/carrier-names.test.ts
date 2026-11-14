import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { arrivalWindow, deliveryOptions, deliveryPromise } from '../src/mocks/delivery';
import { resetShippingSettings, setShippingSettings } from '../src/mocks/shipping-settings';

/* Listing rules section 3: AfroTransact sets all shipping at launch, so NO shopper-facing text may name a carrier or a
   carrier service (USPS, UPS, FedEx, DHL, "Ground Advantage", "Next Day Air") while the staff switch
   sellerCarrierShippingEnabled is off. This guard scans the places a shopper reads (the storefront, the shared shopper UI,
   the customer app, the mock BFF's shopper content and every option the mock estimator can produce) and fails on a hit.
   The Go side has the same guard for the BFF (carrier_names_guard_test.go) and the generated options. Seller, staff and
   carrier-gateway code is not shopper-facing and is not scanned. */

const ROOT = resolve(__dirname, '../../../..');
// (?<![\w-]) keeps "sign-ups" and "groups" out: only the carrier name itself matches.
const CARRIER = /(?<![\w-])(usps|ups|fedex|dhl)(?![\w-])|ground advantage|next day air|priority mail|express air/i;

const SCAN: { dir: string; exts: string[]; skip?: RegExp }[] = [
  { dir: 'web/apps/storefront/src', exts: ['.ts', '.tsx', '.json'] },
  { dir: 'web/packages/ui/src/commerce', exts: ['.ts', '.tsx'] },
  { dir: 'mobile/apps/customer/src', exts: ['.ts', '.tsx', '.json'], skip: /[\\/]api[\\/]generated[\\/]|PaymentMarks/ },
  // The mock BFF's shopper-facing content; its seller and staff mocks are not shopper-facing.
  { dir: 'web/packages/api-client/src/mocks', exts: ['.ts'], skip: /[\\/](seller|staff)[\\/]|inventory-hub|delivery-run-handlers|fixtures[\\/]legal/ }
];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.next' || name === 'generated') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

/** Source text without comments (a carrier named in a comment is not shown to anyone). */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
}

describe('no shopper-facing text names a carrier while the seller carrier switch is off', () => {
  for (const { dir, exts, skip } of SCAN) {
    it(`${dir}`, () => {
      const hits: string[] = [];
      for (const file of walk(resolve(ROOT, dir))) {
        if (!exts.some((e) => file.endsWith(e)) || skip?.test(file)) continue;
        const text = file.endsWith('.json') ? readFileSync(file, 'utf8') : code(readFileSync(file, 'utf8'));
        text.split('\n').forEach((line, i) => {
          if (CARRIER.test(line)) hits.push(`${relative(ROOT, file)}:${i + 1}: ${line.trim().slice(0, 140)}`);
        });
      }
      expect(hits, 'carrier names in shopper-facing places').toEqual([]);
    });
  }

  it('every option and promise the estimator can show, for every fulfilment, fee setting and cart value', () => {
    const now = new Date('2026-11-11T12:00:00Z');
    const texts: string[] = [];
    for (const settings of [{ flatFeeMinor: 599, freeAboveMinor: null }, { flatFeeMinor: 0, freeAboveMinor: 5000 }, { flatFeeMinor: 799, freeAboveMinor: 3500 }]) {
      resetShippingSettings();
      setShippingSettings(settings);
      for (const fulfilment of ['AFROTRANSACT', 'AFROTRANSACT_DELIVERY', 'SELLER'] as const) {
        for (const subtotalMinor of [0, 1000, 4999, 5000, 99_999]) {
          const from = { fulfilment, city: 'Houston, TX' };
          for (const o of deliveryOptions({ from, sellerName: 'Seller', arrival: arrivalWindow({ ...from, handling: 2 }, 'Houston, TX', now), subtotalMinor }, 'Houston, TX', now)) texts.push(o.label, o.eta);
          const p = deliveryPromise({ ...from, handling: 2 }, 'Houston, TX', now, true);
          texts.push(p.prefix, p.label);
        }
      }
    }
    resetShippingSettings();
    expect(texts.filter((x) => CARRIER.test(x))).toEqual([]);
    expect(texts.length).toBeGreaterThan(50);
  });

  it('the pattern catches carriers and spares our own words', () => {
    for (const bad of ['UPS Ground', 'USPS Ground Advantage', 'FedEx 2Day', 'DHL Express', 'UPS Next Day Air', 'Ground Advantage']) expect(CARRIER.test(bad), bad).toBe(true);
    for (const good of ['Delivery by AfroTransact', 'Pickup at an AfroTransact point', 'Collect from the store', 'Sign-ups by source', 'Groups']) expect(CARRIER.test(good), good).toBe(false);
  });
});
