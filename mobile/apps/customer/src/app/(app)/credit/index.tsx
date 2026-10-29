/* AfroTransact credit (S320, ADR 0006): balance and history. Credit only pays for products and services at
   checkout; it can't be withdrawn, paid out, refunded to a card or sent to someone else, so there is nothing here that
   moves it out. */
import { type CreditEntry, formatMoney } from '@afrotransact/api-client';
import { GiftIcon as Gift } from 'phosphor-react-native';
import { StyleSheet, View } from 'react-native';

import { useCreditAccount } from '@/api/queries';
import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';
import { AppText, Button, Empty, Loading } from '@/ui/primitives';
import { ErrorView, useRefresh } from '@/ui/QueryStates';
import { Screen, SubHeader } from '@/ui/Screen';

const dayOf = (iso: string): string => new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });

function Entry({ entry }: { entry: CreditEntry }) {
  const tr = useT();
  const added = entry.amountMinor > 0;
  return (
    <View style={styles.entry} accessible accessibilityLabel={`${tr(`cu.credit.src.${entry.source}`)}, ${formatMoney(entry.amountMinor, entry.currency)}`}>
      <View style={{ flex: 1 }}>
        <AppText variant="bodyStrong">{tr(`cu.credit.src.${entry.source}`)}</AppText>
        <AppText variant="caption" color={c.textMuted}>{dayOf(entry.createdAt)} · {tr('cu.credit.after', { amount: formatMoney(entry.balanceAfterMinor, entry.currency) })}</AppText>
      </View>
      <AppText variant="bodyStrong" color={added ? c.success : c.text}>{added ? '+' : ''}{formatMoney(entry.amountMinor, entry.currency)}</AppText>
    </View>
  );
}

export default function Credit() {
  const tr = useT();
  const credit = useCreditAccount();
  const refresh = useRefresh(credit);
  const pages = credit.data?.pages ?? [];
  const first = pages[0];
  const entries = pages.flatMap((p) => p.entries);
  return (
    <Screen header={<SubHeader title={tr('cu.credit.title')} />} refresh={refresh}>
      {credit.isPending ? <Loading /> : null}
      {credit.isError && !credit.data ? <ErrorView error={credit.error} onRetry={() => void credit.refetch()} retrying={credit.isFetching} /> : null}
      {first ? (
        <>
          <View style={styles.balance}>
            <AppText variant="caption" color={c.textMuted}>{tr('cu.credit.balance')}</AppText>
            <AppText variant="display" accessibilityRole="header">{formatMoney(first.balanceMinor, first.currency)}</AppText>
            <AppText variant="small" color={c.textMuted}>{tr('cu.credit.intro')}</AppText>
          </View>
          <AppText variant="heading">{tr('cu.credit.history')}</AppText>
          {entries.length === 0 ? <Empty icon={Gift} title={tr('cu.credit.none')} /> : entries.map((e) => <Entry key={e.id} entry={e} />)}
          {credit.hasNextPage ? <Button kind="outline" label={tr('cu.credit.loadMore')} loading={credit.isFetchingNextPage} onPress={() => void credit.fetchNextPage()} /> : null}
        </>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  balance: { gap: sp['1'], padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: c.surfaceMuted },
  entry: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], minHeight: t.size.touch + 4, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: c.border }
});
