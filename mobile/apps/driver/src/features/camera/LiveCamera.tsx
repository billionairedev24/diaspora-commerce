/* Live in-app camera for proof and inspection photos (S082/S085). There is no gallery picker anywhere in the app:
   photos come only from this camera, are stamped with time, GPS and the route id (burned into the image with
   react-native-view-shot) and carry capture metadata {source: LIVE_CAMERA, capturedAt, lat, lon, accuracyM,
   stampRouteId, sha256}. */
import { CameraView, useCameraPermissions } from 'expo-camera';
import { CameraRotateIcon as CameraRotate, CameraSlashIcon as CameraSlash, XIcon as X } from 'phosphor-react-native';
import { useRef, useState } from 'react';
import { Image, Linking, Modal, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { captureRef } from 'react-native-view-shot';

import { env } from '@/config/env';
import { stampText } from '@/domain/pod';
import { type PhotoCapture } from '@/domain/types';
import { sha256File } from '@/lib/hash';
import { currentFix } from '@/lib/location';
import { c, sp, t } from '@/theme';
import { AppText, Button } from '@/ui/primitives';

export interface LiveCameraProps {
  visible: boolean;
  title: string;
  routeId: string;
  onClose: () => void;
  onCapture: (photo: PhotoCapture) => void;
}

interface Shot {
  uri: string;
  width: number;
  height: number;
  at: Date;
  lat: number | null;
  lon: number | null;
  accuracyM: number | null;
}

export function LiveCamera({ visible, title, routeId, onClose, onCapture }: LiveCameraProps) {
  const insets = useSafeAreaInsets();
  const [perm, requestPerm] = useCameraPermissions();
  const cam = useRef<CameraView>(null);
  const stampView = useRef<View>(null);
  const [facing, setFacing] = useState<'back' | 'front'>('back');
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [shot, setShot] = useState<Shot | null>(null);
  const [err, setErr] = useState('');

  const close = () => {
    setShot(null);
    setBusy(false);
    setErr('');
    onClose();
  };

  const finish = async (s: Shot) => {
    let uri = s.uri;
    try {
      uri = await captureRef(stampView, { format: 'jpg', quality: 0.85, result: 'tmpfile' });
    } catch {
      /* stamping unavailable (web preview): the metadata still carries time, GPS and route id */
    }
    try {
      onCapture({ localUri: uri, source: 'LIVE_CAMERA', capturedAt: s.at.toISOString(), lat: s.lat, lon: s.lon, accuracyM: s.accuracyM, stampRouteId: routeId, sha256: await sha256File(uri) });
      close();
    } catch {
      setErr('Couldn’t save the photo. Try again.');
      setShot(null);
      setBusy(false);
    }
  };

  const shoot = async () => {
    if (!cam.current || busy) return;
    setBusy(true);
    setErr('');
    try {
      const at = new Date();
      const [pic, fix] = await Promise.all([cam.current.takePictureAsync({ quality: 0.7 }), currentFix(true)]);
      setShot({ uri: pic.uri, width: pic.width, height: pic.height, at, lat: fix?.lat ?? null, lon: fix?.lon ?? null, accuracyM: fix?.accuracyM ?? null });
    } catch {
      setErr('The camera didn’t take the photo. Try again.');
      setBusy(false);
    }
  };

  /** Dev mocks only (no camera in simulators / web preview) — never shipped behaviour. */
  const demo = () => {
    const at = new Date();
    void finish({ uri: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=', width: 1, height: 1, at, lat: 29.7376, lon: -95.5134, accuracyM: 8 });
  };

  const denied = perm && !perm.granted && !perm.canAskAgain;
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={close} presentationStyle="fullScreen">
      <View style={[styles.root, { paddingTop: insets.top, paddingBottom: insets.bottom }]}>
        <View style={styles.top}>
          <Pressable accessibilityRole="button" accessibilityLabel="Close camera" onPress={close} style={styles.round}><X size={20} color={c.textInverse} /></Pressable>
          <AppText variant="bodyStrong" color={c.textInverse} style={{ flex: 1 }}>{title}</AppText>
          <View style={styles.live}><AppText variant="caption" color={c.textInverse} style={{ fontWeight: t.fontWeight.black }}>LIVE</AppText></View>
        </View>
        <View style={styles.viewport}>
          {perm?.granted ? (
            <CameraView ref={cam} style={StyleSheet.absoluteFill} facing={facing} onCameraReady={() => setReady(true)} />
          ) : (
            <View style={styles.msg}>
              <CameraSlash size={40} color={c.textInverse} />
              <AppText color={c.textInverse} style={{ textAlign: 'center' }}>
                {denied ? 'Camera access was blocked. Turn it on in Settings → AfroTransact Delivery → Camera. Proof photos must be taken live — gallery uploads aren’t accepted.' : 'Allow camera access to take proof photos. Photos are taken live in the app — gallery uploads aren’t accepted.'}
              </AppText>
              {denied ? <Button kind="outline" label="Open Settings" onPress={() => void Linking.openSettings()} /> : <Button label="Allow camera" onPress={() => void requestPerm()} />}
              {env.mocks ? <Button kind="ghost" label="Continue with a demo photo (dev mocks only)" onPress={demo} /> : null}
            </View>
          )}
          {shot ? (
            /* Off-screen composition that gets captured: the photo with its stamp burned in. */
            <View ref={stampView} collapsable={false} style={[styles.stampBox, { aspectRatio: shot.width && shot.height ? shot.width / shot.height : 0.75 }]}>
              <Image source={{ uri: shot.uri }} style={StyleSheet.absoluteFill} onLoad={() => void finish(shot)} onError={() => void finish(shot)} />
              <View style={styles.stampBar}>
                <AppText color={c.textInverse} style={styles.stampText}>{stampText(shot.at, shot.lat, shot.lon, routeId)}</AppText>
              </View>
            </View>
          ) : null}
          <AppText variant="caption" color={c.textInverse} style={styles.hint}>{new Date().toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })} · GPS-stamped · {routeId}</AppText>
        </View>
        {err ? <AppText color={c.dangerBg} style={{ textAlign: 'center' }}>{err}</AppText> : null}
        <View style={styles.bottom}>
          <AppText variant="caption" color={t.ink['300']} style={{ width: 90 }}>No gallery uploads</AppText>
          <Pressable accessibilityRole="button" accessibilityLabel="Take photo" disabled={!perm?.granted || !ready || busy} onPress={() => void shoot()} style={[styles.shutter, { opacity: perm?.granted && ready && !busy ? 1 : 0.4 }]} />
          <Pressable accessibilityRole="button" accessibilityLabel="Switch camera" onPress={() => setFacing((f) => (f === 'back' ? 'front' : 'back'))} style={[styles.round, { width: 90, alignItems: 'flex-end', backgroundColor: 'transparent' }]}>
            <CameraRotate size={28} color={c.textInverse} />
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: t.ink['1000'] },
  top: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], padding: sp['4'] },
  round: { width: 40, height: 40, borderRadius: 20, backgroundColor: t.ink['800'], alignItems: 'center', justifyContent: 'center' },
  live: { paddingHorizontal: sp['2'], paddingVertical: 2, borderRadius: t.radius.sm, backgroundColor: c.danger },
  viewport: { flex: 1, overflow: 'hidden', backgroundColor: t.ink['900'] },
  msg: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: sp['4'], padding: sp['6'] },
  stampBox: { position: 'absolute', left: -10000, top: 0, width: 1080 },
  stampBar: { position: 'absolute', left: 0, right: 0, bottom: 0, paddingHorizontal: 24, paddingVertical: 14, backgroundColor: c.overlay },
  stampText: { fontSize: 30, lineHeight: 38, fontWeight: t.fontWeight.semibold },
  hint: { position: 'absolute', left: sp['4'], bottom: sp['4'], paddingHorizontal: sp['2'], paddingVertical: 2, backgroundColor: c.overlay, borderRadius: t.radius.sm },
  bottom: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', padding: sp['5'] },
  shutter: { width: 72, height: 72, borderRadius: 36, borderWidth: 5, borderColor: t.ink['300'], backgroundColor: t.ink['0'] }
});
