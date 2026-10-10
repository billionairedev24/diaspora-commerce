/* Signature capture (someone-else handover, inspection sign-off): finger drawing → PNG via react-native-view-shot. */
import { useRef, useState } from 'react';
import { PanResponder, StyleSheet, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { captureRef } from 'react-native-view-shot';

import { c, sp, t } from '@/theme';
import { AppText } from '@/ui/primitives';

export function SignaturePad({ label = 'Signature', onChange }: { label?: string; onChange: (uri: string | null) => void }) {
  const [paths, setPaths] = useState<string[]>([]);
  const current = useRef('');
  const view = useRef<View>(null);
  const [, force] = useState(0);

  const save = async () => {
    try {
      onChange(await captureRef(view, { format: 'png', quality: 1, result: 'tmpfile' }));
    } catch {
      onChange(`signature:${Date.now()}`); // web preview: keep the fact that it was signed
    }
  };

  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => {
        current.current = `M${e.nativeEvent.locationX.toFixed(1)},${e.nativeEvent.locationY.toFixed(1)}`;
        force((n) => n + 1);
      },
      onPanResponderMove: (e) => {
        current.current += ` L${e.nativeEvent.locationX.toFixed(1)},${e.nativeEvent.locationY.toFixed(1)}`;
        force((n) => n + 1);
      },
      onPanResponderRelease: () => {
        const p = current.current;
        current.current = '';
        setPaths((ps) => [...ps, p]);
        setTimeout(() => void save(), 0);
      }
    })
  ).current;

  return (
    <View style={{ gap: sp['1'] }}>
      <View style={styles.row}>
        <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>{label}</AppText>
        <AppText variant="small" accessibilityRole="button" style={{ textDecorationLine: 'underline' }} onPress={() => { setPaths([]); onChange(null); }}>Clear</AppText>
      </View>
      <View ref={view} collapsable={false} style={[styles.pad, paths.length ? { borderColor: c.success } : null]} {...responder.panHandlers} accessibilityLabel="Signature pad, draw with your finger">
        <Svg style={StyleSheet.absoluteFill}>
          {[...paths, current.current].filter(Boolean).map((d, i) => <Path key={i} d={d} stroke={t.ink['1000']} strokeWidth={2.4} fill="none" strokeLinecap="round" strokeLinejoin="round" />)}
        </Svg>
        {!paths.length && !current.current ? <AppText variant="caption" color={c.textSubtle} style={styles.ph}>Sign here</AppText> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  pad: { height: 130, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, backgroundColor: c.surface },
  ph: { position: 'absolute', left: sp['3'], bottom: sp['2'] }
});
