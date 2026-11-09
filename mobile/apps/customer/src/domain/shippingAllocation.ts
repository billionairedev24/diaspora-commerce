/* The delivery charge on each line of a package, for the tax estimate (rules TX-05 to TX-07): the delivery follows the
   sale, so a delivery tied only to exempt lines is untaxed and a mixed package is split. Same arithmetic as orders (Java)
   and gateway-bff (Go); shared vectors in tools/checkout/shipping-allocation-vectors.json.
   OPEN LEGAL DECISION 5: the mixed-basket method must be approved by the tax adviser before production. */
export type AllocationMethod = 'PROPORTIONAL' | 'FIRST_LINE';

/** floor(total * w / sum) per line; spare minor units go to the largest remainder (ties: the earliest line). */
export function allocateShipping(total: number, weights: number[], method: AllocationMethod = 'PROPORTIONAL'): number[] {
  const out = weights.map(() => 0);
  if (total <= 0 || weights.length === 0) return out;
  const w = weights.map((x) => Math.max(0, x));
  const sum = w.reduce((a, b) => a + b, 0);
  if (method === 'FIRST_LINE' || sum === 0) {
    out[0] = total;
    return out;
  }
  const rem = w.map((x, i) => {
    const product = BigInt(total) * BigInt(x);
    out[i] = Number(product / BigInt(sum));
    return Number(product % BigInt(sum));
  });
  for (let left = total - out.reduce((a, b) => a + b, 0); left > 0; left -= 1) {
    let best = 0;
    for (let i = 1; i < rem.length; i += 1) if ((rem[i] ?? 0) > (rem[best] ?? 0)) best = i;
    out[best] = (out[best] ?? 0) + 1;
    rem[best] = -1;
  }
  return out;
}
