/* Notification settings (S168): server switches from identity (GET/PUT /v1/me/notification-preferences) plus
   per-category switches on this device (used to filter pushes). */
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { StyleSheet, Switch, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { identityKeys } from '@/api/keys';
import { useNotificationPreferences } from '@/api/queries';
import { useAuth } from '@/auth/AuthProvider';
import { type CategoryPrefs } from '@/domain/notifications';
import { usePush } from '@/push/PushProvider';
import { c, sp, t } from '@/theme';
import { AppText, Loading, SectionLabel } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { useToast } from '@/ui/Toast';

function Toggle({ title, detail, value, onChange }: { title: string; detail: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={styles.row}>
      <View style={{ flex: 1 }}>
        <AppText variant="bodyStrong">{title}</AppText>
        <AppText variant="small" color={c.textMuted}>{detail}</AppText>
      </View>
      <Switch accessibilityLabel={title} value={value} onValueChange={onChange} trackColor={{ true: c.success, false: t.ink['200'] }} thumbColor={c.surface} />
    </View>
  );
}

export default function NotificationSettings() {
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { user } = useAuth();
  const push = usePush();
  const prefs = useNotificationPreferences();
  const p = prefs.data;
  useEffect(() => { if (p) push.setServerPrefs({ push: p.push ?? true, marketingOptIn: p.marketingOptIn ?? false }); }, [p, push]);

  const putServer = async (patch: Partial<{ email: boolean; sms: boolean; push: boolean; marketingOptIn: boolean }>) => {
    const next = { email: p?.email ?? true, sms: p?.sms ?? true, push: p?.push ?? true, marketingOptIn: p?.marketingOptIn ?? false, ...patch };
    qc.setQueryData(identityKeys.preferences(), { ...p, ...next });
    try {
      const saved = await api.identity.putPreferences(next);
      qc.setQueryData(identityKeys.preferences(), saved);
      if (next.push) await push.enable();
    } catch {
      toast('Couldn’t save. Check your connection.');
      void qc.invalidateQueries({ queryKey: identityKeys.preferences() });
    }
  };
  const setCat = (k: keyof CategoryPrefs, v: boolean) => void push.setCategories({ ...push.categories, [k]: v });

  return (
    <Screen header={<SubHeader title="Notifications" />}>
      {!p ? <Loading /> : (
        <>
          <SectionLabel>ON THIS PHONE</SectionLabel>
          <Toggle title="Order & delivery updates" detail="Packed, out for delivery, delivered with photo" value={!!p.push && push.categories.orders} onChange={(v) => { setCat('orders', v); if (v && !p.push) void putServer({ push: true }); }} />
          <Toggle title="Saved-item alerts" detail="Back in stock and price drops" value={push.categories.restock} onChange={(v) => setCat('restock', v)} />
          <Toggle title="Deals & recipes" detail="Weekly, never more than twice a week" value={push.categories.deals && !!p.marketingOptIn} onChange={(v) => { setCat('deals', v); void putServer({ marketingOptIn: v }); }} />
          <Toggle title="Lola suggestions" detail="Reorder reminders based on your pantry" value={push.categories.lola} onChange={(v) => setCat('lola', v)} />
          <SectionLabel>EVERYWHERE</SectionLabel>
          <Toggle title="Push notifications" detail="All notifications to your devices" value={!!p.push} onChange={(v) => void putServer({ push: v })} />
          <Toggle title="Delivery texts" detail={`SMS to ${user?.phone || 'your mobile'} when the driver is near`} value={!!p.sms} onChange={(v) => void putServer({ sms: v })} />
          <Toggle title="Email" detail="Receipts and account updates" value={!!p.email} onChange={(v) => void putServer({ email: v })} />
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingVertical: sp['2'] }
});
