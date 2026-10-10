/* Provider (design: PROVIDER): services with price/deposit, cancellation policy. */
import { formatMoney } from '@afrotransact/api-client';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { SealCheckIcon as SealCheck, ShieldCheckIcon as ShieldCheck } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useProvider } from '@/api/queries';
import { c, sp, t } from '@/theme';
import { AppText, Card, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

const hrs = (m: number) => (m < 60 ? `${m} min` : `${m / 60} hr`);

export default function Provider() {
  const { orgId } = useLocalSearchParams<{ orgId: string }>();
  const router = useRouter();
  const p = useProvider(orgId ?? '');
  const d = p.data;
  return (
    <Screen header={<SubHeader title={d?.displayName ?? 'Provider'} />}>
      {!d ? <Loading /> : (
        <>
          <View style={styles.cover}>
            <AppText variant="small" color={t.ink['200']}>{d.location}</AppText>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <SealCheck size={18} weight="fill" color={t.gold['500']} />
              <AppText variant="small" color={c.textInverse}>Verified pro · {d.instantBooking ? 'instant book' : 'confirms within 4 h'}</AppText>
            </View>
          </View>
          <AppText variant="heading">Services</AppText>
          {(d.services ?? []).map((s) => (
            <Card key={s.id} onPress={() => router.push({ pathname: '/book/[serviceId]', params: { serviceId: s.id ?? '', orgId: d.orgId ?? '' } })} accessibilityLabel={`Book ${s.name}`}>
              <View style={styles.row}>
                <View style={{ flex: 1, gap: 2 }}>
                  <AppText variant="bodyStrong">{s.name}</AppText>
                  <AppText variant="small" color={c.textMuted}>{hrs(s.durationMinutes ?? 60)} · {s.depositMinor ? `${formatMoney(s.depositMinor, s.currency ?? 'USD')} deposit` : 'no deposit'}{s.description ? ` · ${s.description}` : ''}</AppText>
                </View>
                <View style={{ alignItems: 'flex-end' }}>
                  <AppText variant="bodyStrong">{formatMoney(s.priceMinor ?? 0, s.currency ?? 'USD')}</AppText>
                  <AppText variant="small" style={{ textDecorationLine: 'underline' }}>Book</AppText>
                </View>
              </View>
            </Card>
          ))}
          <View style={styles.policy}>
            <ShieldCheck size={22} color={c.text} />
            <AppText variant="small" style={{ flex: 1 }}><AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>{d.policy?.label} cancellation.</AppText> {d.policy?.text}</AppText>
          </View>
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  cover: { borderRadius: t.radius.lg, padding: sp['5'], minHeight: 120, justifyContent: 'flex-end', gap: sp['1'], backgroundColor: t.ink['900'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] },
  policy: { flexDirection: 'row', gap: sp['3'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted }
});
