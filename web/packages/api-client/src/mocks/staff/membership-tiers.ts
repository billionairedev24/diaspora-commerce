/* What a membership tier gives, in words, for the staff mock's tier sheets (the Go BFF's benefitText). */
import { type Scalar } from './db';

const LABELS: [string, string][] = [
  ['freeDelivery', 'Free AfroTransact delivery'],
  ['freeShipping', 'Free carrier shipping'],
  ['cashback', 'Cashback as credit'],
  ['memberPrices', 'Member prices and deals'],
  ['earlyAccess', 'Early access to pre-orders and runs'],
  ['prioritySupport', 'Priority support'],
  ['freeReturns', 'Free returns'],
  ['boostedRewards', 'Bigger referral and gift rewards'],
  ['monthlyCredit', 'Monthly credit']
];

const usd = (dollars: number) => `$${dollars.toFixed(2)}`;
const n = (f: Record<string, Scalar>, k: string): number => {
  const v = f[k];
  return typeof v === 'number' ? v : 0;
};

function detail(f: Record<string, Scalar>, key: string): string {
  switch (key) {
    case 'freeDelivery':
    case 'freeShipping':
    case 'cashback': {
      const min = n(f, `${key}Min`);
      const cap = n(f, `${key}Cap`);
      const where = min > 0 ? `on orders from ${usd(min)}` : 'on every order';
      const capText = cap > 0 ? `, up to ${usd(cap)} off an order` : '';
      if (key === 'cashback') return `${n(f, 'cashbackPercent')}% of the goods back as credit ${where}${capText}`;
      return `${where.charAt(0).toUpperCase()}${where.slice(1)}${capText}`;
    }
    case 'earlyAccess':
      return `${n(f, 'earlyAccessHours')} hours before everyone else`;
    case 'boostedRewards':
      return `${n(f, 'boostedPercent')}% more on referral and gift rewards`;
    case 'monthlyCredit':
      return `${usd(n(f, 'monthlyCreditAmount'))} of credit every month`;
    default:
      return 'Included';
  }
}

/** "Free AfroTransact delivery: On orders from $25.00; Cashback as credit: 2% of the goods back as credit on every order". */
export function tierBenefitDetails(f: Record<string, Scalar>): string {
  const parts = LABELS.filter(([k]) => f[k] === true).map(([k, label]) => `${label}: ${detail(f, k)}`);
  return parts.length ? parts.join('; ') : 'None';
}
