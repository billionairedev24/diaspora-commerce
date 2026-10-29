/*
 * Third-party payment brand marks: Stripe's "Powered by Stripe" badge and wordmark, and the card network marks
 * (Visa, Mastercard, American Express, Discover). Brand owners require their own colours, so this file is the one
 * documented exception to the "design tokens only" rule (eslint at/no-raw-hex is off for this file only, see
 * eslint.config.mjs). Use these components; never re-draw the marks elsewhere or recolour them.
 *
 * Stripe: wordmark from Stripe's brand assets (stripe.com/newsroom/brand-assets), blurple #635BFF; the badge is the
 * "Powered by Stripe" lockup (blurple pill, white type and wordmark).
 */
import { type CSSProperties } from 'react';

const STRIPE_BLURPLE = '#635BFF';

/** Stripe's wordmark path (viewBox 0 0 60 25). */
const STRIPE_WORDMARK =
  'M59.64 14.28h-8.06c.19 1.93 1.6 2.55 3.2 2.55 1.64 0 2.96-.37 4.05-.95v3.32a8.33 8.33 0 0 1-4.56 1.1c-4.01 0-6.83-2.5-6.83-7.48 0-4.19 2.39-7.52 6.3-7.52 3.92 0 5.96 3.28 5.96 7.5 0 .4-.04 1.26-.06 1.48zm-5.92-5.62c-1.03 0-2.17.73-2.17 2.58h4.25c0-1.85-1.07-2.58-2.08-2.58zM40.95 20.3c-1.44 0-2.32-.6-2.9-1.04l-.02 4.63-4.12.87V5.57h3.76l.08 1.02a4.7 4.7 0 0 1 3.23-1.29c2.9 0 5.62 2.6 5.62 7.4 0 5.23-2.7 7.6-5.65 7.6zM40 8.95c-.95 0-1.54.34-1.97.81l.02 6.12c.4.44.98.78 1.95.78 1.52 0 2.54-1.65 2.54-3.87 0-2.15-1.04-3.84-2.54-3.84zM28.24 5.57h4.13v14.44h-4.13V5.57zm0-4.7L32.37 0v3.36l-4.13.88V.88zm-4.32 9.35v9.79H19.8V5.57h3.7l.12 1.22c1-1.77 3.07-1.41 3.62-1.22v3.79c-.52-.17-2.29-.43-3.32.86zm-8.55 4.72c0 2.43 2.6 1.68 3.12 1.46v3.36c-.55.3-1.54.54-2.89.54a4.15 4.15 0 0 1-4.27-4.24l.01-13.17 4.02-.86v3.54h3.14V9.1h-3.13v5.85zm-4.91.7c0 2.97-2.31 4.66-5.73 4.66a11.2 11.2 0 0 1-4.46-.93v-3.93c1.38.75 3.1 1.31 4.46 1.31.92 0 1.53-.24 1.53-1C6.26 13.77 0 14.51 0 9.95 0 7.04 2.28 5.3 5.62 5.3c1.36 0 2.72.2 4.09.75v3.88a9.23 9.23 0 0 0-4.1-1.06c-.86 0-1.44.25-1.44.9 0 1.85 6.29.97 6.29 5.88z';

export interface BrandMarkProps {
  /** Rendered height in px (width follows the mark's proportions). */
  height?: number;
  className?: string;
  style?: CSSProperties;
}

/** Stripe's wordmark in blurple (or white on a dark surface). */
export function StripeWordmark({ height = 16, className, style, inverse = false }: BrandMarkProps & { inverse?: boolean }) {
  return (
    <svg viewBox="0 0 60 25" height={height} width={(height * 60) / 25} className={className} style={style} role="img" aria-label="Stripe" focusable="false">
      <path fill={inverse ? '#FFFFFF' : STRIPE_BLURPLE} d={STRIPE_WORDMARK} />
    </svg>
  );
}

/** The "Powered by Stripe" badge (blurple lockup, 150 × 34 at its reference size). */
export function PoweredByStripe({ height = 26, className, style }: BrandMarkProps) {
  return (
    <svg viewBox="0 0 150 34" height={height} width={(height * 150) / 34} className={className} style={style} role="img" aria-label="Powered by Stripe" focusable="false">
      <rect width="150" height="34" rx="6" fill={STRIPE_BLURPLE} />
      <text x="13" y="21.5" fill="#FFFFFF" fontFamily="-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif" fontSize="12" fontWeight="500">
        Powered by
      </text>
      <svg x="86" y="7" width="50" height="20.8" viewBox="0 0 60 25" aria-hidden="true">
        <path fill="#FFFFFF" d={STRIPE_WORDMARK} />
      </svg>
    </svg>
  );
}

export type CardBrand = 'visa' | 'mastercard' | 'amex' | 'discover' | 'jcb' | 'diners' | 'unionpay';

export const CARD_BRAND_LABEL: Record<CardBrand, string> = {
  visa: 'Visa', mastercard: 'Mastercard', amex: 'American Express', discover: 'Discover', jcb: 'JCB', diners: 'Diners Club', unionpay: 'UnionPay'
};
const CARD_LABEL = CARD_BRAND_LABEL;

const BRAND_ALIASES: Record<string, CardBrand> = {
  visa: 'visa', mastercard: 'mastercard', master_card: 'mastercard', mc: 'mastercard', amex: 'amex', american_express: 'amex',
  americanexpress: 'amex', discover: 'discover', jcb: 'jcb', diners: 'diners', diners_club: 'diners', dinersclub: 'diners',
  unionpay: 'unionpay', union_pay: 'unionpay', cartes_bancaires: 'visa'
};

/** A card network from Stripe's brand string ("visa", "American Express", "diners_club" …), or null when unknown. */
export function cardBrandOf(raw: string | null | undefined): CardBrand | null {
  const key = (raw ?? '').trim().toLowerCase().replace(/[\s-]+/g, '_');
  return BRAND_ALIASES[key] ?? BRAND_ALIASES[key.replace(/_/g, '')] ?? null;
}

/** One card network mark on a card-shaped tile (38 × 24 reference). */
export function CardBrandMark({ brand, height = 24, className, style }: BrandMarkProps & { brand: CardBrand }) {
  const common = { viewBox: '0 0 38 24', height, width: (height * 38) / 24, className, style, role: 'img', 'aria-label': CARD_LABEL[brand], focusable: 'false' } as const;
  switch (brand) {
    case 'visa':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <text x="19" y="16.2" textAnchor="middle" fill="#1A1F71" fontFamily="Arial Black, Arial, Helvetica, sans-serif" fontSize="11" fontStyle="italic" fontWeight="900" letterSpacing="-.3">
            VISA
          </text>
        </svg>
      );
    case 'mastercard':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <circle cx="15" cy="12" r="7" fill="#EB001B" />
          <circle cx="23" cy="12" r="7" fill="#F79E1B" />
          <path d="M19 6.27a7 7 0 0 1 0 11.46 7 7 0 0 1 0-11.46z" fill="#FF5F00" />
        </svg>
      );
    case 'amex':
      return (
        <svg {...common}>
          <rect width="38" height="24" rx="4" fill="#016FD0" />
          <text x="19" y="15.6" textAnchor="middle" fill="#FFFFFF" fontFamily="Arial, Helvetica, sans-serif" fontSize="8.4" fontWeight="700" letterSpacing=".4">
            AMEX
          </text>
        </svg>
      );
    case 'discover':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <path d="M14 23.5h20a3.5 3.5 0 0 0 3.5-3.5v-6.5C30 18.5 22 21.8 14 23.5z" fill="#FF6000" />
          <text x="5" y="13.6" fill="#231F20" fontFamily="Arial, Helvetica, sans-serif" fontSize="6.2" fontWeight="700">
            DISC
          </text>
          <circle cx="23.3" cy="11.5" r="2.9" fill="#FF6000" />
          <text x="26.6" y="13.6" fill="#231F20" fontFamily="Arial, Helvetica, sans-serif" fontSize="6.2" fontWeight="700">
            VER
          </text>
        </svg>
      );
    case 'jcb':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <rect x="7" y="5" width="8" height="14" rx="2.5" fill="#0E4C96" />
          <rect x="15" y="5" width="8" height="14" rx="2.5" fill="#E21836" />
          <rect x="23" y="5" width="8" height="14" rx="2.5" fill="#007B40" />
          <text x="19" y="14.6" textAnchor="middle" fill="#FFFFFF" fontFamily="Arial, Helvetica, sans-serif" fontSize="6.4" fontWeight="700" letterSpacing="1.6">
            JCB
          </text>
        </svg>
      );
    case 'diners':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <circle cx="19" cy="12" r="7.5" fill="#0079BE" />
          <circle cx="19" cy="12" r="5.2" fill="#FFFFFF" />
          <path d="M17.4 8.2v7.6a4 4 0 0 1 0-7.6zM20.6 8.2a4 4 0 0 1 0 7.6z" fill="#0079BE" />
        </svg>
      );
    case 'unionpay':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <path d="M8 5h8l-3 14H5z" fill="#E21836" />
          <path d="M15 5h8l-3 14h-8z" fill="#00447C" />
          <path d="M22 5h9l-3 14h-9z" fill="#007B84" />
        </svg>
      );
  }
}

/** A saved method that is not a card: bank account, Cash App Pay, Link, Amazon Pay, or an unknown card network. */
export type WalletKind = 'bank' | 'cashapp' | 'link' | 'amazon_pay' | 'card';

const WALLET_LABEL: Record<WalletKind, string> = { bank: 'Bank account', cashapp: 'Cash App Pay', link: 'Link', amazon_pay: 'Amazon Pay', card: 'Card' };

/** The mark of a non-card method on the same card-shaped tile as the networks. */
export function WalletMark({ kind, height = 24, className, style }: BrandMarkProps & { kind: WalletKind }) {
  const common = { viewBox: '0 0 38 24', height, width: (height * 38) / 24, className, style, role: 'img', 'aria-label': WALLET_LABEL[kind], focusable: 'false' } as const;
  switch (kind) {
    case 'cashapp':
      return (
        <svg {...common}>
          <rect width="38" height="24" rx="4" fill="#00D64F" />
          <text x="19" y="17" textAnchor="middle" fill="#FFFFFF" fontFamily="Arial, Helvetica, sans-serif" fontSize="14" fontWeight="700">$</text>
        </svg>
      );
    case 'link':
      return (
        <svg {...common}>
          <rect width="38" height="24" rx="4" fill="#00D66F" />
          <text x="19" y="15.6" textAnchor="middle" fill="#011E0F" fontFamily="Arial, Helvetica, sans-serif" fontSize="9" fontWeight="700">link</text>
        </svg>
      );
    case 'amazon_pay':
      return (
        <svg {...common}>
          <rect width="38" height="24" rx="4" fill="#232F3E" />
          <text x="19" y="13.4" textAnchor="middle" fill="#FFFFFF" fontFamily="Arial, Helvetica, sans-serif" fontSize="6.6" fontWeight="700">amazon</text>
          <path d="M11.5 15.6c4.6 2.6 10.4 2.6 15 0" stroke="#FF9900" strokeWidth="1.3" fill="none" strokeLinecap="round" />
        </svg>
      );
    case 'bank':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <path d="M19 4.5l8 4H11z" fill="#3D4451" />
          <rect x="12" y="9.5" width="2" height="7" fill="#3D4451" />
          <rect x="18" y="9.5" width="2" height="7" fill="#3D4451" />
          <rect x="24" y="9.5" width="2" height="7" fill="#3D4451" />
          <rect x="10.5" y="17.5" width="17" height="2" fill="#3D4451" />
        </svg>
      );
    case 'card':
      return (
        <svg {...common}>
          <rect x=".5" y=".5" width="37" height="23" rx="3.5" fill="#FFFFFF" stroke="#D9D9D9" />
          <rect x="1" y="6" width="36" height="4" fill="#3D4451" />
          <rect x="5" y="14" width="12" height="2.5" rx="1" fill="#B8BDC7" />
        </svg>
      );
  }
}

/** The right mark for any saved method: the card network's own mark, else the wallet or bank mark. */
export function PaymentMethodMark({ type, brand, height = 24, className, style }: BrandMarkProps & { type: string; brand?: string | null }) {
  const t = type.toLowerCase();
  if (t === 'us_bank_account' || t === 'bank' || t === 'bank_account') return <WalletMark kind="bank" height={height} className={className} style={style} />;
  if (t === 'cashapp' || t === 'link' || t === 'amazon_pay') return <WalletMark kind={t} height={height} className={className} style={style} />;
  const network = cardBrandOf(brand);
  return network ? <CardBrandMark brand={network} height={height} className={className} style={style} /> : <WalletMark kind="card" height={height} className={className} style={style} />;
}

/** The accepted card networks in a row (e.g. next to a card form). */
export function CardBrandRow({ brands = ['visa', 'mastercard', 'amex', 'discover'], height = 20, className }: { brands?: CardBrand[]; height?: number; className?: string }) {
  return (
    <span className={className} style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }} aria-label={`Accepted cards: ${brands.map((b) => CARD_LABEL[b]).join(', ')}`} role="img">
      {brands.map((b) => (
        <CardBrandMark key={b} brand={b} height={height} />
      ))}
    </span>
  );
}
