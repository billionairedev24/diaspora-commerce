/* S164 layout contract: the status area has its own solid strip, headers and the tab bar are fixed, and only the
   screen's inner content scrolls (vertically — never sideways). Pages never scroll as a whole. */
import { useRouter } from 'expo-router';
import { CaretLeftIcon as CaretLeft } from 'phosphor-react-native';
import { type ReactNode } from 'react';
import { RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useT } from '@/i18n';
import { c, sp, t } from '@/theme';

import { AppText, IconButton } from './primitives';

export interface ScreenProps {
  /** Fixed header (pinned title + back button for inner pages). */
  header?: ReactNode;
  /** Fixed bottom bar (action bar / composer), sits above the tab bar or the home indicator. */
  footer?: ReactNode;
  /** 'scroll' wraps children in the inner vertical ScrollView; 'fixed' when children are their own list. */
  body?: 'scroll' | 'fixed';
  /** True when the screen sits above the tab bar (the tab bar owns the bottom inset). */
  inTabs?: boolean;
  /** Content padding for the scroll body (default 16). */
  padded?: boolean;
  /** Pull-to-refresh for the scroll body (lists pass their own `refreshControl`). */
  refresh?: { refreshing: boolean; onRefresh: () => void };
  children: ReactNode;
}

export function Screen({ header, footer, body = 'scroll', inTabs = false, padded = true, refresh, children }: ScreenProps) {
  const insets = useSafeAreaInsets();
  const bottom = inTabs ? 0 : insets.bottom;
  return (
    <View style={styles.root}>
      <View style={[styles.statusStrip, { height: insets.top }]} />
      {header}
      {body === 'scroll' ? (
        <ScrollView
          style={styles.body}
          contentContainerStyle={[padded ? styles.padded : null, { paddingBottom: (footer ? sp['4'] : bottom + sp['6']) }]}
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          alwaysBounceHorizontal={false}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          refreshControl={refresh ? <RefreshControl refreshing={refresh.refreshing} onRefresh={refresh.onRefresh} tintColor={c.text} colors={[c.text]} /> : undefined}
        >
          {children}
        </ScrollView>
      ) : (
        <View style={styles.body}>{children}</View>
      )}
      {footer ? <View style={[styles.footer, { paddingBottom: bottom + sp['2'] }]}>{footer}</View> : null}
    </View>
  );
}

/** Pinned inner-page header: back button + title (+ optional text action). */
export function SubHeader({ title, onBack, action }: { title: string; onBack?: () => void; action?: { label: string; onPress: () => void } }) {
  const tr = useT();
  const router = useRouter();
  const back = onBack ?? (() => (router.canGoBack() ? router.back() : router.replace('/')));
  return (
    <View style={styles.subHeader}>
      <IconButton icon={CaretLeft} label={tr('cu.screen.back')} onPress={back} />
      <AppText variant="title" style={{ flex: 1, fontSize: 22 }} numberOfLines={1} accessibilityRole="header">
        {title}
      </AppText>
      {action ? (
        <AppText variant="bodyStrong" onPress={action.onPress} accessibilityRole="button" style={{ textDecorationLine: 'underline' }}>
          {action.label}
        </AppText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: c.canvas, overflow: 'hidden' },
  statusStrip: { backgroundColor: c.canvas },
  body: { flex: 1 },
  padded: { paddingHorizontal: sp['4'], paddingTop: sp['2'], gap: sp['4'] },
  footer: { paddingHorizontal: sp['4'], paddingTop: sp['3'], borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border, backgroundColor: c.canvas, gap: sp['2'] },
  subHeader: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingHorizontal: sp['4'], paddingTop: sp['2'], paddingBottom: sp['3'], backgroundColor: c.canvas, minHeight: t.size.touch + sp['5'] }
});
