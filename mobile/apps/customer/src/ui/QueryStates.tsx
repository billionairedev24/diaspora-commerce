/* How a screen explains that its data did not load, and how a person refreshes it. Screens used to check only
   isLoading, so a failed request looked like an empty list ("No orders yet"): every data screen now separates
   loading, failed (with Try again) and genuinely empty. */
import { WarningCircleIcon as WarningCircle, WifiSlashIcon as WifiSlash } from 'phosphor-react-native';
import { type ReactNode, useCallback, useRef, useState } from 'react';
import { RefreshControl, View } from 'react-native';

import { describeError, NetworkError } from '@/api/network';
import { env } from '@/config/env';
import { c, sp } from '@/theme';

import { AppText, Button, Loading, styles } from './primitives';

export function ErrorView({ error, onRetry, retrying }: { error: unknown; onRetry?: () => void; retrying?: boolean }) {
  const d = describeError(error);
  const Icon = error instanceof NetworkError ? WifiSlash : WarningCircle;
  return (
    <View style={[styles.center, { gap: sp['3'], paddingVertical: sp['12'], paddingHorizontal: sp['4'] }]} accessibilityRole="alert">
      <Icon size={48} color={c.textSubtle} />
      <AppText variant="heading" style={{ textAlign: 'center' }}>{d.title}</AppText>
      <AppText variant="small" color={c.textMuted} style={{ textAlign: 'center' }}>{d.body}</AppText>
      {onRetry && d.retryable ? <Button label="Try again" onPress={onRetry} loading={retrying} size="md" style={{ paddingHorizontal: sp['6'] }} /> : null}
      {d.detail && env.appEnv !== 'prod' ? <AppText variant="caption" color={c.textSubtle} style={{ textAlign: 'center' }}>{d.detail}</AppText> : null}
    </View>
  );
}

interface QueryLike {
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isFetching: boolean;
  refetch: () => Promise<unknown>;
}

/**
 * Loading, failed or ready. `children` renders only when there is data to show; a failed refresh of data already on
 * screen keeps showing it (stale beats blank) with a small "couldn't refresh" line.
 */
export function QueryBoundary({ query, hasData, children }: { query: QueryLike; hasData: boolean; children: ReactNode }) {
  if (query.isPending && !hasData) return <Loading />;
  if (query.isError && !hasData) return <ErrorView error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />;
  return (
    <>
      {query.isError ? <AppText variant="caption" color={c.textMuted} accessibilityRole="alert">Couldn’t refresh. Showing what we had.</AppText> : null}
      {children}
    </>
  );
}

/** Pull-to-refresh for one or more queries; the spinner shows only for the person's own pull, not background refetches. */
export function useRefresh(...queries: Array<Pick<QueryLike, 'refetch'>>) {
  const [refreshing, setRefreshing] = useState(false);
  const latest = useRef(queries);
  latest.current = queries;
  const onRefresh = useCallback(() => {
    setRefreshing(true);
    void Promise.allSettled(latest.current.map((q) => q.refetch())).finally(() => setRefreshing(false));
  }, []);
  return { refreshing, onRefresh };
}

export function refreshControl(r: { refreshing: boolean; onRefresh: () => void }) {
  return <RefreshControl refreshing={r.refreshing} onRefresh={r.onRefresh} tintColor={c.text} colors={[c.text]} />;
}
