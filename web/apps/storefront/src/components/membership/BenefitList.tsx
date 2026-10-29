import { type MembershipBenefit } from '@afrotransact/api-client';
import { type Formatters } from '@afrotransact/i18n';
import { CheckCircle, Coins, Gift, Headset, Lightning, Package, Star, Tag, Truck } from '@afrotransact/ui';
import { type ReactNode } from 'react';

import { type MessageKey, type Translator } from '@/i18n/t';

import styles from './Membership.module.css';
import { benefitDetail, benefitTitle } from './memberText';

const ICONS: Record<string, ReactNode> = {
  FREE_DELIVERY: <Truck weight="duotone" />,
  FREE_SHIPPING: <Package weight="duotone" />,
  CASHBACK: <Coins weight="duotone" />,
  MEMBER_PRICES: <Tag weight="duotone" />,
  EARLY_ACCESS: <Lightning weight="duotone" />,
  PRIORITY_SUPPORT: <Headset weight="duotone" />,
  BOOSTED_REWARDS: <Star weight="duotone" />,
  MONTHLY_CREDIT: <Gift weight="duotone" />
};

export interface BenefitListProps {
  benefits: readonly MembershipBenefit[];
  currency: string;
  t: Translator<MessageKey>;
  fmt: Formatters;
}

/** The benefits of a tier, one per line with a small icon (from the tier staff configured), a title and the order limits. */
export function BenefitList({ benefits, currency, t, fmt }: BenefitListProps) {
  return (
    <ul className={styles.benefits}>
      {benefits.map((b) => {
        const detail = benefitDetail(t, fmt, b, currency);
        return (
          <li key={b.code} className={styles.benefit}>
            <span className={styles.benefitIcon} aria-hidden>
              {ICONS[b.code] ?? <CheckCircle weight="duotone" />}
            </span>
            <span className={styles.benefitText}>
              <span className={styles.benefitTitle}>{benefitTitle(t, fmt, b)}</span>
              {detail && <span className={styles.benefitDetail}>{detail}</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
