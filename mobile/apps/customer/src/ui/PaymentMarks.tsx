/* Card network and wallet marks for saved payment methods (Visa, Mastercard, Amex, Discover, JCB, Diners, UnionPay;
   bank, Cash App Pay, Link, Amazon Pay), drawn like web/packages/ui PaymentBrandMarks.tsx. Brand owners require their
   own colours: this file is the documented exception to "tokens only" (eslint.mobile.mjs). */
import { type PaymentMethod } from '@afrotransact/api-client';
import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Path, Rect, Text as SvgText } from 'react-native-svg';

import { t } from '@/theme';

import { AppText } from './primitives';

type Network = 'visa' | 'mastercard' | 'amex' | 'discover' | 'jcb' | 'diners' | 'unionpay';
type Kind = Network | 'bank' | 'cashapp' | 'link' | 'amazon_pay' | 'card';

const NETWORK_LABEL: Record<Network, string> = {
  visa: 'Visa', mastercard: 'Mastercard', amex: 'American Express', discover: 'Discover', jcb: 'JCB', diners: 'Diners Club', unionpay: 'UnionPay'
};
const KIND_LABEL: Record<Exclude<Kind, Network>, string> = { bank: 'Bank account', cashapp: 'Cash App Pay', link: 'Link', amazon_pay: 'Amazon Pay', card: 'Card' };
const ALIASES: Record<string, Network> = {
  visa: 'visa', mastercard: 'mastercard', amex: 'amex', american_express: 'amex', discover: 'discover', jcb: 'jcb', diners: 'diners', diners_club: 'diners', unionpay: 'unionpay'
};

export function networkOf(brand: string | null | undefined): Network | null {
  return ALIASES[(brand ?? '').trim().toLowerCase().replace(/[\s-]+/g, '_')] ?? null;
}

function kindOf(p: Pick<PaymentMethod, 'type' | 'brand'>): Kind {
  if (p.type === 'us_bank_account') return 'bank';
  if (p.type === 'cashapp' || p.type === 'link' || p.type === 'amazon_pay') return p.type;
  return networkOf(p.brand) ?? 'card';
}

export function methodName(p: Pick<PaymentMethod, 'type' | 'brand'>): string {
  const k = kindOf(p);
  return k in NETWORK_LABEL ? NETWORK_LABEL[k as Network] : KIND_LABEL[k as Exclude<Kind, Network>];
}

const WHITE_TILE = <Rect x={0.5} y={0.5} width={37} height={23} rx={3.5} fill="#FFFFFF" stroke="#D9D9D9" />;

/** The mark of a saved method on a 38 × 24 card-shaped tile. */
export function PaymentMark({ method, height = 24 }: { method: Pick<PaymentMethod, 'type' | 'brand'>; height?: number }) {
  const k = kindOf(method);
  return (
    <Svg width={(height * 38) / 24} height={height} viewBox="0 0 38 24" accessibilityLabel={methodName(method)}>
      {k === 'visa' ? (<>{WHITE_TILE}<SvgText x={19} y={16.2} textAnchor="middle" fill="#1A1F71" fontSize={11} fontWeight="900" fontStyle="italic">VISA</SvgText></>) : null}
      {k === 'mastercard' ? (<>{WHITE_TILE}<Circle cx={15} cy={12} r={7} fill="#EB001B" /><Circle cx={23} cy={12} r={7} fill="#F79E1B" /><Path d="M19 6.27a7 7 0 0 1 0 11.46 7 7 0 0 1 0-11.46z" fill="#FF5F00" /></>) : null}
      {k === 'amex' ? (<><Rect width={38} height={24} rx={4} fill="#016FD0" /><SvgText x={19} y={15.6} textAnchor="middle" fill="#FFFFFF" fontSize={8.4} fontWeight="700">AMEX</SvgText></>) : null}
      {k === 'discover' ? (<>{WHITE_TILE}<Path d="M14 23.5h20a3.5 3.5 0 0 0 3.5-3.5v-6.5C30 18.5 22 21.8 14 23.5z" fill="#FF6000" /><SvgText x={5} y={13.6} fill="#231F20" fontSize={6.2} fontWeight="700">DISC</SvgText><Circle cx={23.3} cy={11.5} r={2.9} fill="#FF6000" /><SvgText x={26.6} y={13.6} fill="#231F20" fontSize={6.2} fontWeight="700">VER</SvgText></>) : null}
      {k === 'jcb' ? (<>{WHITE_TILE}<Rect x={7} y={5} width={8} height={14} rx={2.5} fill="#0E4C96" /><Rect x={15} y={5} width={8} height={14} rx={2.5} fill="#E21836" /><Rect x={23} y={5} width={8} height={14} rx={2.5} fill="#007B40" /><SvgText x={19} y={14.6} textAnchor="middle" fill="#FFFFFF" fontSize={6.4} fontWeight="700" letterSpacing={1.6}>JCB</SvgText></>) : null}
      {k === 'diners' ? (<>{WHITE_TILE}<Circle cx={19} cy={12} r={7.5} fill="#0079BE" /><Circle cx={19} cy={12} r={5.2} fill="#FFFFFF" /><Path d="M17.4 8.2v7.6a4 4 0 0 1 0-7.6zM20.6 8.2a4 4 0 0 1 0 7.6z" fill="#0079BE" /></>) : null}
      {k === 'unionpay' ? (<>{WHITE_TILE}<Path d="M8 5h8l-3 14H5z" fill="#E21836" /><Path d="M15 5h8l-3 14h-8z" fill="#00447C" /><Path d="M22 5h9l-3 14h-9z" fill="#007B84" /></>) : null}
      {k === 'cashapp' ? (<><Rect width={38} height={24} rx={4} fill="#00D64F" /><SvgText x={19} y={17} textAnchor="middle" fill="#FFFFFF" fontSize={14} fontWeight="700">$</SvgText></>) : null}
      {k === 'link' ? (<><Rect width={38} height={24} rx={4} fill="#00D66F" /><SvgText x={19} y={15.6} textAnchor="middle" fill="#011E0F" fontSize={9} fontWeight="700">link</SvgText></>) : null}
      {k === 'amazon_pay' ? (<><Rect width={38} height={24} rx={4} fill="#232F3E" /><SvgText x={19} y={13.4} textAnchor="middle" fill="#FFFFFF" fontSize={6.6} fontWeight="700">amazon</SvgText><Path d="M11.5 15.6c4.6 2.6 10.4 2.6 15 0" stroke="#FF9900" strokeWidth={1.3} fill="none" /></>) : null}
      {k === 'bank' ? (<>{WHITE_TILE}<Path d="M19 4.5l8 4H11z" fill="#3D4451" /><Rect x={12} y={9.5} width={2} height={7} fill="#3D4451" /><Rect x={18} y={9.5} width={2} height={7} fill="#3D4451" /><Rect x={24} y={9.5} width={2} height={7} fill="#3D4451" /><Rect x={10.5} y={17.5} width={17} height={2} fill="#3D4451" /></>) : null}
      {k === 'card' ? (<>{WHITE_TILE}<Rect x={1} y={6} width={36} height={4} fill="#3D4451" /><Rect x={5} y={14} width={12} height={2.5} rx={1} fill="#B8BDC7" /></>) : null}
    </Svg>
  );
}

/** A saved method drawn as a wallet card (network mark, masked number, expiry), like the web SavedPaymentCard. */
export function SavedCardTile({ method, selected }: { method: PaymentMethod; selected?: boolean }) {
  const isCard = method.type === 'card';
  const name = method.type === 'us_bank_account' ? method.label.replace(/ ending in \d+.*$/, '') : methodName(method);
  const dark = isCard;
  const fg = dark ? t.ink['0'] : t.color.text;
  return (
    <View style={[styles.card, dark ? styles.dark : styles.light, selected ? styles.selected : null]} accessibilityLabel={method.label}>
      <View style={styles.top}>
        {isCard ? <View style={styles.chip} /> : <AppText variant="bodyStrong" color={fg}>{name}</AppText>}
        <PaymentMark method={method} height={28} />
      </View>
      <AppText variant="heading" color={fg} style={styles.number}>{method.last4 ? `•••• ${isCard ? '•••• •••• ' : ''}${method.last4}` : ' '}</AppText>
      <View style={styles.bottom}>
        <AppText variant="small" color={fg} style={{ flex: 1 }} numberOfLines={1}>{isCard ? name : ''}</AppText>
        {method.expires ? <AppText variant="caption" color={fg}>Expires {method.expires}</AppText> : null}
      </View>
      {method.isDefault ? <AppText variant="label" style={styles.defaultTag}>DEFAULT</AppText> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { aspectRatio: 1.586, borderRadius: t.radius.md, padding: t.space['4'], justifyContent: 'space-between', borderWidth: 1, overflow: 'hidden' },
  dark: { backgroundColor: t.ink['1000'], borderColor: t.ink['900'] },
  light: { backgroundColor: t.color.surface, borderColor: t.color.border },
  selected: { borderColor: t.gold['500'], borderWidth: 2 },
  top: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  chip: { width: 36, height: 26, borderRadius: t.radius.xs, backgroundColor: t.gold['400'] },
  number: { letterSpacing: 2 },
  bottom: { flexDirection: 'row', alignItems: 'flex-end', gap: t.space['3'] },
  defaultTag: { position: 'absolute', top: t.space['2'], alignSelf: 'center', paddingHorizontal: t.space['2'], borderRadius: t.radius.pill, backgroundColor: t.gold['500'], color: t.ink['1000'] }
});
