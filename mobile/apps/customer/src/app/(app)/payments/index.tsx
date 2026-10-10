/* Payment methods (design: PAYMENTS). Cards and bank accounts are stored by Stripe. */
import { isApiError } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { BankIcon as Bank, CreditCardIcon as CreditCard, CurrencyDollarIcon as CurrencyDollar, LockSimpleIcon as LockSimple, PlusIcon as Plus, TrashIcon as Trash } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { accountKeys } from '@/api/keys';
import { usePaymentMethods } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { c, sp, t } from '@/theme';
import { AppText, Button, Card, IconButton, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

export default function Payments() {
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { confirmWithDevice } = useAuth();
  const pms = usePaymentMethods();
  const remove = async (id: string, label: string) => {
    if ((pms.data?.length ?? 0) <= 1) return toast('Keep at least one payment method');
    if (!(await confirmWithDevice(`Remove ${label}`))) return;
    try {
      await api.account.deletePaymentMethod(id);
      toast(`${label} removed`);
      void qc.invalidateQueries({ queryKey: accountKeys.paymentMethods() });
    } catch (e) {
      toast(isApiError(e) ? e.message : 'Couldn’t remove it');
    }
  };
  return (
    <Screen header={<SubHeader title="Payment methods" />} footer={<Button icon={Plus} label="Add payment method" onPress={() => router.push('/payments/add')} />}>
      {pms.isLoading ? <Loading /> : null}
      {(pms.data ?? []).map((p) => {
        const I = p.type === 'us_bank_account' ? Bank : p.type === 'cashapp' ? CurrencyDollar : CreditCard;
        return (
          <Card key={p.id} selected={p.isDefault}>
            <View style={styles.row}>
              <I size={24} color={c.text} />
              <View style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{p.label}</AppText>
                {p.expires ? <AppText variant="small" color={c.textMuted}>Expires {p.expires}</AppText> : null}
              </View>
              {p.isDefault ? <AppText variant="caption" style={styles.badge}>Default</AppText> : null}
              <IconButton icon={Trash} label={`Remove ${p.label}`} tone="clear" onPress={() => void remove(p.id, p.label)} />
            </View>
          </Card>
        );
      })}
      <View style={styles.row}>
        <LockSimple size={16} color={c.textMuted} />
        <AppText variant="caption" color={c.textMuted} style={{ flex: 1 }}>Cards and bank accounts are stored by Stripe. AfroTransact never sees full numbers.</AppText>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  badge: { paddingHorizontal: sp['2'], paddingVertical: 2, borderRadius: t.radius.pill, backgroundColor: c.successBg, color: c.success, fontWeight: t.fontWeight.bold }
});
