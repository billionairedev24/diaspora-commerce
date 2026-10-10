/* Your bookings (design: BOOKINGS). */
import { useRouter } from 'expo-router';
import { CalendarBlankIcon as CalendarBlank } from 'phosphor-react-native';
import { FlatList, StyleSheet, View } from 'react-native';

import { useMyBookings } from '@/api/queries';
import { formatWhen, isActive } from '@/domain/bookings';
import { statusTone } from '@/features/bookings/statusTone';
import { c, sp, t } from '@/theme';
import { AppText, Card, Empty, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

export default function Bookings() {
  const router = useRouter();
  const mine = useMyBookings();
  return (
    <Screen header={<SubHeader title="Your bookings" />} body="fixed">
      <FlatList
        data={mine.data ?? []}
        keyExtractor={(b) => b.id ?? ''}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        ListEmptyComponent={mine.isLoading ? <Loading /> : <Empty icon={CalendarBlank} title="No bookings yet" action={{ label: 'Find a pro', onPress: () => router.push('/services') }} />}
        renderItem={({ item: b }) => {
          const tone = statusTone(b.status);
          return (
            <Card onPress={() => router.push(`/bookings/${b.id}`)} accessibilityLabel={`${b.service?.name}, ${b.statusLabel}`} style={!isActive(b.status) ? { opacity: 0.8 } : undefined}>
              <View style={[styles.pill, { backgroundColor: tone.bg }]}><AppText variant="caption" color={tone.fg} style={{ fontWeight: t.fontWeight.bold }}>{b.statusLabel}</AppText></View>
              <AppText variant="bodyStrong">{b.service?.name}</AppText>
              <AppText variant="small" color={c.textMuted}>{b.service?.providerName} · {formatWhen(b.startAt ?? '')}</AppText>
              {b.status === 'RESCHEDULE_PROPOSED' ? <AppText variant="small" color={c.warning} style={{ fontWeight: t.fontWeight.bold }}>Action needed · provider proposed a new time</AppText> : null}
            </Card>
          );
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { padding: sp['4'], gap: sp['3'] },
  pill: { alignSelf: 'flex-start', paddingHorizontal: sp['2'], paddingVertical: 2, borderRadius: t.radius.pill }
});
