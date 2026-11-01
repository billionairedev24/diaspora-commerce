/* Shared frame of the lock and setup screens: black canvas, the "afrotransact delivery" logo on top, content centred,
   actions at the bottom. Drawn as a layer over the app (absolute fill): the navigator below stays mounted. */
import { StatusBar } from 'expo-status-bar';
import { type ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { sp, t } from '@/theme';
import { Logo } from '@/ui/Logo';

export function LockFrame({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[StyleSheet.absoluteFill, styles.root]} accessibilityViewIsModal>
      <StatusBar style="light" />
      <View style={{ paddingTop: insets.top + sp['4'], paddingHorizontal: sp['5'] }}>
        <Logo size={32} wordSize={t.fontSize.lg} tone="light" suffix="delivery" />
      </View>
      <ScrollView style={{ flex: 1 }} contentContainerStyle={styles.body} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled" bounces={false}>
        {children}
      </ScrollView>
      {footer ? <View style={[styles.footer, { paddingBottom: insets.bottom + sp['4'] }]}>{footer}</View> : <View style={{ height: insets.bottom + sp['4'] }} />}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { backgroundColor: t.ink['1000'], zIndex: 100, elevation: 100 },
  body: { flexGrow: 1, alignItems: 'center', justifyContent: 'center', gap: sp['5'], paddingHorizontal: sp['6'], paddingVertical: sp['4'] },
  footer: { paddingHorizontal: sp['5'], gap: sp['2'] }
});
