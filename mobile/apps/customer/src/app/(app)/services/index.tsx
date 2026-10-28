/* Book a pro (design: SERVICES): upcoming booking banner and providers with their next opening. */
import { formatMoney } from '@afrotransact/api-client';
import { useRouter } from 'expo-router';
import { CalendarCheckIcon as CalendarCheck, CaretRightIcon as CaretRight, CookingPotIcon as CookingPot, MagnifyingGlassIcon as MagnifyingGlass, ScissorsIcon as Scissors, StorefrontIcon as Storefront, TShirtIcon as TShirt } from 'phosphor-react-native';
import { useState } from 'react';
import { FlatList, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { useMyBookings, useServices } from '@/api/queries';
import { formatWhen, isActive } from '@/domain/bookings';
import { c, sp, t } from '@/theme';
import { AppText, Loading } from '@/ui/primitives';
import { ErrorView, refreshControl, useRefresh } from '@/ui/QueryStates';
import { Screen, SubHeader } from '@/ui/Screen';

const ICON: Record<string, typeof Scissors> = { 'Hair & beauty': Scissors, Catering: CookingPot, Tailoring: TShirt };

export default function Services() {
  const router = useRouter();
  const [q, setQ] = useState('');
  const services = useServices(q.trim());
  const mine = useMyBookings();
  const refresh = useRefresh(services, mine);
  const next = (mine.data ?? []).filter((b) => isActive(b.status)).sort((a, b) => (a.startAt ?? '').localeCompare(b.startAt ?? ''))[0];

  return (
    <Screen header={<SubHeader title="Book a pro" />} body="fixed">
      <FlatList
        data={services.data?.services ?? []}
        keyExtractor={(s) => s.serviceId ?? ''}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        keyboardDismissMode="on-drag"
        ListHeaderComponent={
          <View style={{ gap: sp['3'] }}>
            <View style={styles.search}>
              <MagnifyingGlass size={18} color={c.textMuted} />
              <TextInput value={q} onChangeText={setQ} placeholder="Braids, catering, tailoring…" placeholderTextColor={c.textSubtle} accessibilityLabel="Search services" style={styles.input} />
            </View>
            {next ? (
              <Pressable accessibilityRole="button" onPress={() => router.push(`/bookings/${next.id}`)} style={styles.upcoming}>
                <CalendarCheck size={24} color={t.gold['500']} />
                <View style={{ flex: 1 }}>
                  <AppText variant="bodyStrong" color={c.textInverse}>{next.service?.name} · {next.statusLabel}</AppText>
                  <AppText variant="caption" color={t.ink['200']}>{next.service?.providerName} · {formatWhen(next.startAt ?? '')}</AppText>
                </View>
                <CaretRight size={18} color={c.textInverse} />
              </Pressable>
            ) : null}
          </View>
        }
        refreshControl={refreshControl(refresh)}
        ListEmptyComponent={services.isPending ? <Loading /> : services.isError ? <ErrorView error={services.error} onRetry={() => void services.refetch()} retrying={services.isFetching} /> : <AppText color={c.textMuted}>No pros match yet.</AppText>}
        renderItem={({ item: s }) => {
          const I = ICON[s.category ?? ''] ?? Storefront;
          const nextLabel = s.nextAvailable ? `Next opening ${new Date(`${s.nextAvailable}T12:00:00`).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })}` : 'Fully booked for 3 weeks';
          return (
            <Pressable accessibilityRole="button" accessibilityLabel={`${s.providerName}, ${s.name}`} onPress={() => router.push(`/services/${s.orgId}`)} style={styles.pro}>
              <View style={styles.icon}><I size={26} color={c.text} /></View>
              <View style={{ flex: 1, gap: 2 }}>
                <AppText variant="caption" color={c.textMuted}>{s.category}</AppText>
                <AppText variant="bodyStrong">{s.providerName}</AppText>
                <AppText variant="small">{s.name}</AppText>
                <AppText variant="caption" color={c.textMuted}>★ {s.rating} · {s.location}</AppText>
                <AppText variant="caption" color={c.success}>{nextLabel}</AppText>
              </View>
              <View style={{ alignItems: 'flex-end' }}>
                <AppText variant="caption" color={c.textMuted}>from</AppText>
                <AppText variant="bodyStrong">{formatMoney(s.priceFromMinor ?? 0, s.currency ?? 'USD')}</AppText>
              </View>
            </Pressable>
          );
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { padding: sp['4'], gap: sp['3'] },
  search: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.controlLg, paddingHorizontal: sp['3'], borderRadius: t.radius.pill, backgroundColor: c.surfaceMuted },
  input: { flex: 1, fontSize: t.fontSize.base, color: c.text },
  upcoming: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: 14, borderRadius: t.radius.md, backgroundColor: t.ink['1000'] },
  pro: { flexDirection: 'row', gap: sp['3'], padding: sp['3'], borderRadius: t.radius.lg, borderWidth: 1, borderColor: c.border },
  icon: { width: 56, height: 56, borderRadius: t.radius.md, backgroundColor: c.surfaceMuted, alignItems: 'center', justifyContent: 'center' }
});
