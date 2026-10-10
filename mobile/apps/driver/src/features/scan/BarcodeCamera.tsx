/* Code 128 label scanner (expo-camera). Repeated reads of the same label while it stays in frame are ignored. */
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useRef } from 'react';
import { StyleSheet, View } from 'react-native';

import { isRepeat, normalizeLabel } from '@/domain/scan';
import { c, sp, t } from '@/theme';
import { AppText, Button } from '@/ui/primitives';

export function BarcodeCamera({ hint, onScan, paused }: { hint: string; onScan: (label: string) => void; paused: boolean }) {
  const [perm, requestPerm] = useCameraPermissions();
  const last = useRef<{ label: string; at: number } | null>(null);
  return (
    <View style={styles.box} accessibilityLabel="Barcode scanner">
      {perm?.granted ? (
        <CameraView
          style={StyleSheet.absoluteFill}
          facing="back"
          barcodeScannerSettings={{ barcodeTypes: ['code128'] }}
          onBarcodeScanned={paused ? undefined : ({ data }) => {
            const label = normalizeLabel(data);
            const now = Date.now();
            if (isRepeat(last.current, label, now)) return;
            last.current = { label, at: now };
            onScan(label);
          }}
        />
      ) : (
        <View style={styles.center}>
          <AppText color={c.textInverse} style={{ textAlign: 'center' }}>Allow the camera to scan labels, or type the label number below.</AppText>
          <Button size="md" label="Allow camera" onPress={() => void requestPerm()} />
        </View>
      )}
      <View pointerEvents="none" style={styles.frame} />
      <AppText variant="small" color={c.textInverse} style={styles.hint}>{hint}</AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { height: 220, borderRadius: t.radius.lg, overflow: 'hidden', backgroundColor: t.ink['900'] },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: sp['3'], padding: sp['4'] },
  frame: { position: 'absolute', left: '12%', right: '12%', top: '30%', bottom: '30%', borderWidth: 2, borderColor: t.gold['500'], borderRadius: t.radius.md },
  hint: { position: 'absolute', left: 0, right: 0, bottom: sp['3'], textAlign: 'center' }
});
