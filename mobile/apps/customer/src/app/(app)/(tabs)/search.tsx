/* Browse & search (design: SEARCH): pinned search field; results list scrolls. */
import { useLocalSearchParams, useRouter } from 'expo-router';
import { BarcodeIcon as Barcode, ClockCounterClockwiseIcon as ClockCounterClockwise, MagnifyingGlassIcon as MagnifyingGlass } from 'phosphor-react-native';
import { useEffect, useState } from 'react';
import { FlatList, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { useSearch } from '@/api/queries';
import { ProductRow } from '@/features/shop/components';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Chip, Loading } from '@/ui/primitives';
import { ErrorView, refreshControl, useRefresh } from '@/ui/QueryStates';
import { Screen } from '@/ui/Screen';

const RECENT = ['shito', 'gari', 'jollof', 'black soap'];

function useDebounced(v: string, ms = 250) {
  const [d, setD] = useState(v);
  useEffect(() => {
    const id = setTimeout(() => setD(v), ms);
    return () => clearTimeout(id);
  }, [v, ms]);
  return d;
}

export default function Search() {
  const tr = useT();
  const router = useRouter();
  const params = useLocalSearchParams<{ q?: string }>();
  const [q, setQ] = useState(params.q ?? '');
  useEffect(() => { if (params.q !== undefined) setQ(params.q); }, [params.q]);
  const dq = useDebounced(q.trim());
  const results = useSearch(dq);
  const refresh = useRefresh(results);
  const hits = results.data?.hits ?? [];

  const header = (
    <View style={styles.header}>
      <View style={styles.field}>
        <MagnifyingGlass size={20} color={c.textMuted} />
        <TextInput value={q} onChangeText={setQ} placeholder={tr('cu.search.searchAfrotransact')} placeholderTextColor={c.textSubtle} accessibilityLabel={tr('cu.search.searchAfrotransact')} returnKeyType="search" autoCorrect={false} style={styles.input} />
        <Barcode size={20} color={c.textMuted} />
      </View>
      <AppText variant="bodyStrong" accessibilityRole="button" onPress={() => { setQ(''); router.navigate('/'); }}>{tr('cu.search.cancel')}</AppText>
    </View>
  );

  return (
    <Screen inTabs header={header} body="fixed">
      <FlatList
        data={hits}
        keyExtractor={(p) => p.atin}
        contentContainerStyle={styles.list}
        showsVerticalScrollIndicator={false}
        keyboardDismissMode="on-drag"
        refreshControl={refreshControl(refresh)}
        ListHeaderComponent={
          dq ? (
            <AppText variant="small" color={c.textMuted}>{results.isFetching ? tr('cu.search.searching') : tr('cu.search.resultsFor', { count: results.data?.total ?? 0, q: dq })}</AppText>
          ) : (
            <View style={{ gap: sp['2'] }}>
              <AppText variant="bodyStrong">{tr('cu.search.recent')}</AppText>
              <View style={styles.chips}>
                {RECENT.map((r) => (
                  <Pressable key={r} onPress={() => setQ(r)} accessibilityRole="button" style={styles.recent}>
                    <ClockCounterClockwise size={14} color={c.textMuted} />
                    <AppText variant="small">{r}</AppText>
                  </Pressable>
                ))}
              </View>
              <AppText variant="bodyStrong">{tr('cu.search.allProducts')}</AppText>
            </View>
          )
        }
        ListEmptyComponent={results.isPending ? <Loading /> : results.isError ? <ErrorView error={results.error} onRetry={() => void results.refetch()} retrying={results.isFetching} /> : dq ? <View style={{ paddingTop: sp['6'], gap: sp['2'] }}><AppText>{tr('cu.search.noMatchesYet')}</AppText><Chip label={tr('cu.search.askLolaInstead')} onPress={() => router.push('/lola')} /></View> : null}
        renderItem={({ item }) => <ProductRow p={item} onPress={() => router.push(`/product/${item.atin}`)} />}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], paddingHorizontal: sp['4'], paddingVertical: sp['2'], backgroundColor: c.canvas },
  field: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: sp['2'], minHeight: t.size.controlLg, paddingHorizontal: sp['3'], borderRadius: t.radius.pill, backgroundColor: c.surfaceMuted },
  input: { flex: 1, fontSize: t.fontSize.base, color: c.text },
  list: { paddingHorizontal: sp['4'], paddingBottom: sp['6'], gap: sp['1'] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  recent: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: sp['3'], paddingVertical: sp['2'], borderRadius: t.radius.pill, borderWidth: 1, borderColor: c.border }
});
