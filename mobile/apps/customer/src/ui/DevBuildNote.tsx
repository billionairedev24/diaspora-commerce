/* Development only: in Expo Go a small, dismissible note says what is missing there (remote push since SDK 53) and
   how to get it (a development build: docs/engineering/mobile-on-your-phone.md). Release builds never show it. */
import { BellSlashIcon as BellSlash, XIcon as X } from 'phosphor-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { isExpoGo } from '@/platform/runtime';
import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

/** Whether the note applies: a development bundle running inside Expo Go. */
export function shouldShowDevBuildNote(dev: boolean, expoGo: boolean): boolean {
  return dev && expoGo;
}

export function DevBuildNote() {
  const insets = useSafeAreaInsets();
  const [open, setOpen] = useState(() => shouldShowDevBuildNote(__DEV__, isExpoGo()));
  if (!open) return null;
  return (
    <View pointerEvents="box-none" style={[styles.wrap, { bottom: insets.bottom + sp['16'] }]}>
      <View style={styles.note} accessibilityRole="alert">
        <BellSlash size={18} color={t.gold['500']} />
        <View style={{ flex: 1 }}>
          <AppText variant="small" color={c.textInverse} style={{ fontWeight: t.fontWeight.semibold }}>Push notifications need the development build</AppText>
          <AppText variant="caption" color={c.headerMuted}>Expo Go can’t receive push. Everything else works here.</AppText>
        </View>
        <Pressable accessibilityRole="button" accessibilityLabel="Dismiss" hitSlop={10} onPress={() => setOpen(false)} style={styles.close}>
          <X size={16} color={c.textInverse} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: sp['3'], right: sp['3'] },
  note: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['3'], borderRadius: t.radius.lg, backgroundColor: t.ink['900'], ...t.shadow.md },
  close: { width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: t.ink['800'] }
});
