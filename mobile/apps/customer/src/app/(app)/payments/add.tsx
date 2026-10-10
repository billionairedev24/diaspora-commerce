/* Add payment method (design: ADD CARD, S165): card with ZIP and US bank account (ACH).
   Stripe mode: a SetupIntent from the BFF opens Stripe's native PaymentSheet in setup mode (card + Financial
   Connections for US banks; ZIP collected automatically for US cards). Dev mocks: the design's form, validated with
   the same rules, attaches a mock payment method. */
import { isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { BankIcon as Bank, CreditCardIcon as CreditCard, LockSimpleIcon as LockSimple, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { accountKeys, checkoutKeys } from '@/api/keys';
import { mockAttachPaymentMethod } from '@/api/mock/db';
import { useAuth } from '@/auth/AuthProvider';
import { type BankInput, cardBrand, type CardInput, postalLabel, postalNeeded, validateBank, validateCard } from '@/domain/paymentForm';
import { usePayments } from '@/payments/usePayments';
import { c, sp, t } from '@/theme';
import { AppText, Banner, Button, Card, Chip, Field } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

const COUNTRIES = [['US', 'United States'], ['CA', 'Canada'], ['GB', 'United Kingdom'], ['NG', 'Nigeria'], ['GH', 'Ghana']] as const;

export default function AddPaymentMethod() {
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const payments = usePayments();
  const { confirmWithDevice } = useAuth();
  const [tab, setTab] = useState<'card' | 'bank'>('card');
  const [card, setCard] = useState<CardInput>({ number: '', expiry: '', cvc: '', country: 'US', postal: '' });
  const [bank, setBank] = useState<BankInput>({ holder: '', routing: '', account: '', confirm: '' });
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const done = (label: string) => {
    void qc.invalidateQueries({ queryKey: accountKeys.paymentMethods() });
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
    toast(label);
    if (router.canGoBack()) router.back();
  };

  const openStripe = async () => {
    setBusy(true);
    setErr('');
    try {
      if (!(await confirmWithDevice('Add a payment method'))) return;
      const si = await api.account.createSetupIntent();
      const r = await payments.addPaymentMethod(si.clientSecret);
      if (r === 'added') done('Payment method saved');
      else if (r !== 'cancelled') setErr(r.error);
    } catch (e) {
      setErr(isApiError(e) ? e.message : 'Couldn’t start Stripe. Try again.');
    } finally {
      setBusy(false);
    }
  };

  const saveMock = () => {
    const invalid = tab === 'card' ? validateCard(card) : validateBank(bank);
    if (invalid) return setErr(invalid);
    const digits = (tab === 'card' ? card.number : bank.account).replace(/\D/g, '');
    mockAttachPaymentMethod(tab === 'card' ? 'card' : 'us_bank_account', digits.slice(-4), tab === 'card' ? cardBrand(card.number).toLowerCase() || null : null);
    done(tab === 'card' ? 'Card saved' : 'Bank account added · pending verification');
  };

  if (payments.mode === 'stripe') {
    return (
      <Screen header={<SubHeader title="Add payment method" />} footer={<Button icon={LockSimple} label="Continue with Stripe" loading={busy} onPress={() => void openStripe()} />}>
        <Card><View style={styles.row}><CreditCard size={24} color={c.text} /><View style={{ flex: 1 }}><AppText variant="bodyStrong">Card</AppText><AppText variant="small" color={c.textMuted}>Visa, Mastercard, Amex, Discover · ZIP for US cards</AppText></View></View></Card>
        <Card><View style={styles.row}><Bank size={24} color={c.text} /><View style={{ flex: 1 }}><AppText variant="bodyStrong">US bank account</AppText><AppText variant="small" color={c.textMuted}>ACH · log in to your bank through Stripe, verified in seconds</AppText></View></View></Card>
        {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
        <AppText variant="caption" color={c.textMuted}>Card details are entered in Stripe’s secure sheet and sent straight to Stripe. AfroTransact never sees the card number. Bank login happens in Stripe Financial Connections.</AppText>
      </Screen>
    );
  }

  const footer = <Button label={tab === 'card' ? 'Save card' : 'Save and send test deposits'} onPress={saveMock} />;
  return (
    <Screen header={<SubHeader title="Add payment method" />} footer={footer}>
      <View style={styles.tabs}>
        {([['card', CreditCard, 'Card', 'Visa, Mastercard, Amex, Discover'], ['bank', Bank, 'US bank account', 'ACH · lower fees, no card needed']] as const).map(([k, I, title, sub]) => (
          <Card key={k} selected={tab === k} onPress={() => { setTab(k); setErr(''); }} style={{ flex: 1 }} accessibilityLabel={title}>
            <I size={22} color={c.text} />
            <AppText variant="bodyStrong">{title}</AppText>
            <AppText variant="caption" color={c.textMuted}>{sub}</AppText>
          </Card>
        ))}
      </View>
      {tab === 'card' ? (
        <View style={{ gap: sp['3'] }}>
          <Field label={`Card number${cardBrand(card.number) ? ` · ${cardBrand(card.number)}` : ''}`} value={card.number} onChangeText={(v) => setCard({ ...card, number: v })} keyboardType="number-pad" autoComplete="cc-number" placeholder="1234 1234 1234 1234" />
          <View style={styles.row}>
            <View style={{ flex: 1 }}><Field label="Expiry" value={card.expiry} onChangeText={(v) => setCard({ ...card, expiry: v })} keyboardType="number-pad" autoComplete="cc-exp" placeholder="MM / YY" /></View>
            <View style={{ flex: 1 }}><Field label="CVC" value={card.cvc} onChangeText={(v) => setCard({ ...card, cvc: v.replace(/\D/g, '').slice(0, 4) })} keyboardType="number-pad" autoComplete="cc-csc" placeholder="123" /></View>
          </View>
          <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>Country</AppText>
          <View style={styles.chips}>{COUNTRIES.map(([code, name]) => <Chip key={code} label={name} selected={card.country === code} onPress={() => setCard({ ...card, country: code, postal: '' })} />)}</View>
          {postalNeeded(card.country) ? <Field label={postalLabel(card.country)} value={card.postal} onChangeText={(v) => setCard({ ...card, postal: v })} autoComplete="postal-code" placeholder={card.country === 'US' ? '77032' : card.country === 'CA' ? 'M5V 2T6' : 'SW1A 1AA'} /> : <AppText variant="small" color={c.textMuted}>No postal code needed</AppText>}
          <AppText variant="caption" color={c.textMuted}>By saving, you allow AfroTransact to charge this card for future orders in line with our terms.</AppText>
        </View>
      ) : (
        <View style={{ gap: sp['3'] }}>
          <Field label="Account holder name" value={bank.holder} onChangeText={(v) => setBank({ ...bank, holder: v })} autoComplete="name" />
          <Field label="Routing number" value={bank.routing} onChangeText={(v) => setBank({ ...bank, routing: v.replace(/\D/g, '').slice(0, 9) })} keyboardType="number-pad" placeholder="110000000" />
          <Field label="Account number" value={bank.account} onChangeText={(v) => setBank({ ...bank, account: v.replace(/\D/g, '').slice(0, 17) })} keyboardType="number-pad" placeholder="000123456789" />
          <Field label="Confirm account number" value={bank.confirm} onChangeText={(v) => setBank({ ...bank, confirm: v.replace(/\D/g, '').slice(0, 17) })} keyboardType="number-pad" />
          <AppText variant="caption" color={c.textMuted}>We’ll send two small deposits in 1–2 business days. Confirm the amounts in the app to finish. By saving, you authorise AfroTransact to debit this account by ACH for orders you place, and to credit it for refunds.</AppText>
        </View>
      )}
      {err ? <Banner tone="danger" icon={WarningCircle}>{err}</Banner> : null}
      <View style={styles.row}><LockSimple size={14} color={c.textMuted} /><AppText variant="caption" color={c.textMuted}>Powered by Stripe · dev mock (test card 4242 4242 4242 4242)</AppText></View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  tabs: { flexDirection: 'row', gap: sp['3'] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] }
});
