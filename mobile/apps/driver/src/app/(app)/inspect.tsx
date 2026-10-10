/* Vehicle inspection (S082, design: INSPECTION): 10 checks (brakes, tyres, lights critical), odometer, fuel,
   cold box when perishables are on board, 4 live photos, defect note, certification and signature. */
import { useRouter } from 'expo-router';
import { CameraIcon as Camera, CheckCircleIcon as CheckCircle, CheckIcon as Check, CheckSquareIcon as CheckSquare, SquareIcon as Square, XIcon as X } from 'phosphor-react-native';
import { useState } from 'react';
import { Image, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { CHECKS, emptyInspection, type InspectionDraft, inspectionOutcome, PHOTO_SIDES, readingHelp, toPayload, validateInspection } from '@/domain/inspection';
import { type PhotoSide } from '@/domain/types';
import { LiveCamera } from '@/features/camera/LiveCamera';
import { SignaturePad } from '@/features/signature/SignaturePad';
import { useDriver } from '@/session/DriverProvider';
import { c, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button, Field, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

const SIDE_LABEL: Record<PhotoSide, string> = { FRONT: 'Front', BACK: 'Back', LEFT: 'Left', RIGHT: 'Right' };

export default function Inspect() {
  const router = useRouter();
  const toast = useToast();
  const { sync, engine } = useDriver();
  const r = sync.route;
  const [d, setD] = useState<InspectionDraft>(emptyInspection);
  const [cam, setCam] = useState<PhotoSide | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  if (!r) return <DriverScreen title="Vehicle inspection" back><Loading /></DriverScreen>;
  const ctx = { lastOdometer: r.lastOdometer, perishable: r.perishable };
  const outcome = inspectionOutcome(d);
  const patch = (p: Partial<InspectionDraft>) => { setD((x) => ({ ...x, ...p })); setErr(''); };

  const submit = async () => {
    const invalid = validateInspection(d, ctx);
    if (invalid) return setErr(invalid);
    setBusy(true);
    try {
      await engine.record({ type: 'inspection.submitted', inspection: toPayload(d, r.vehicleId, ctx) });
      if (outcome === 'blocked') toast('Defect sent to dispatch — a replacement vehicle will be assigned. You can’t start this route.');
      else toast(outcome === 'passed-with-defect' ? 'Inspection passed · defect reported to dispatch' : 'Inspection passed');
      router.back();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Couldn’t save the inspection.');
    } finally {
      setBusy(false);
    }
  };

  const reading = (field: 'odometer' | 'fuel' | 'coldBox', label: string, ph: string) => {
    const help = readingHelp(field, d, ctx);
    return (
      <View style={{ flex: 1 }}>
        <Field label={label} value={d[field]} onChangeText={(v) => patch({ [field]: v.replace(/[^\d.]/g, '') })} keyboardType="decimal-pad" placeholder={ph} error={help?.tone === 'danger' ? help.text : undefined} hint={help?.tone === 'warning' ? help.text : undefined} />
      </View>
    );
  };

  return (
    <DriverScreen title="Vehicle inspection" back footer={<Button kind={outcome === 'blocked' ? 'danger' : 'primary'} label={outcome === 'blocked' ? 'Report defect to dispatch' : 'Submit inspection'} loading={busy} onPress={() => void submit()} />}>
      <AppText variant="small" color={c.textMuted}>Pre-trip inspection (DVIR). Critical items must pass before you can start.</AppText>
      <View style={{ gap: sp['2'] }}>
        {CHECKS.map((k) => {
          const v = d.checks[k.key];
          return (
            <View key={k.key} style={[styles.check, v === 'FAIL' ? { borderColor: c.danger } : v === 'PASS' ? { borderColor: t.green['200'] } : null]}>
              <AppText style={{ flex: 1 }}>
                <AppText variant="bodyStrong">{k.label}</AppText>
                {k.critical ? <AppText variant="caption" color={c.danger}>  CRITICAL</AppText> : null}
              </AppText>
              <Pressable accessibilityRole="button" accessibilityLabel={`${k.label}: pass`} accessibilityState={{ selected: v === 'PASS' }} onPress={() => patch({ checks: { ...d.checks, [k.key]: 'PASS' } })} style={[styles.pf, v === 'PASS' ? { backgroundColor: t.green['600'] } : null]}>
                <Check size={18} weight="bold" color={v === 'PASS' ? c.textInverse : c.textMuted} />
              </Pressable>
              <Pressable accessibilityRole="button" accessibilityLabel={`${k.label}: fail`} accessibilityState={{ selected: v === 'FAIL' }} onPress={() => patch({ checks: { ...d.checks, [k.key]: 'FAIL' } })} style={[styles.pf, v === 'FAIL' ? { backgroundColor: c.danger } : null]}>
                <X size={18} weight="bold" color={v === 'FAIL' ? c.textInverse : c.textMuted} />
              </Pressable>
            </View>
          );
        })}
      </View>
      <View style={styles.readings}>
        {reading('odometer', 'Odometer (mi)', String(r.lastOdometer + 12))}
        {reading('fuel', 'Fuel / charge %', '75')}
        {reading('coldBox', 'Cold box °F', '37')}
      </View>
      {r.perishable ? <AppText variant="caption" color={c.textMuted}>Perishables on board: the cold box must read 33–40°F.</AppText> : null}
      <View style={styles.photos}>
        {PHOTO_SIDES.map((s) => {
          const p = d.photos[s];
          return (
            <Pressable key={s} accessibilityRole="button" accessibilityLabel={`${SIDE_LABEL[s]} photo${p ? ', taken' : ''}`} onPress={() => setCam(s)} style={[styles.photo, p ? { borderColor: c.success } : null]}>
              {p?.localUri ? <Image source={{ uri: p.localUri }} style={StyleSheet.absoluteFill} /> : null}
              {p ? <CheckCircle size={22} weight="fill" color={c.success} /> : <Camera size={22} color={c.textMuted} />}
              <AppText variant="caption">{SIDE_LABEL[s]}</AppText>
            </Pressable>
          );
        })}
      </View>
      {outcome !== 'passed' ? (
        <View style={{ gap: sp['1'] }}>
          <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>Describe the defect(s)</AppText>
          <TextInput value={d.defectNote} onChangeText={(v) => patch({ defectNote: v })} multiline accessibilityLabel="Describe the defect(s)" style={styles.textarea} />
        </View>
      ) : null}
      <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: d.certified }} onPress={() => patch({ certified: !d.certified })} style={styles.certify}>
        {d.certified ? <CheckSquare size={22} weight="fill" color={c.text} /> : <Square size={22} color={c.textMuted} />}
        <AppText variant="small" style={{ flex: 1 }}>I certify this vehicle was inspected and the information above is accurate.</AppText>
      </Pressable>
      <SignaturePad label="Driver signature" onChange={(uri) => patch({ signatureLocalUri: uri })} />
      {err ? <Banner tone="danger">{err}</Banner> : null}
      <LiveCamera visible={cam !== null} title={`Vehicle ${cam ? SIDE_LABEL[cam].toLowerCase() : ''} photo`} routeId={r.id} onClose={() => setCam(null)} onCapture={(photo) => { if (cam) patch({ photos: { ...d.photos, [cam]: photo } }); }} />
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  check: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], padding: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  pf: { width: 40, height: 40, borderRadius: 20, borderWidth: 1, borderColor: c.border, alignItems: 'center', justifyContent: 'center', backgroundColor: c.surface },
  readings: { flexDirection: 'row', gap: sp['2'] },
  photos: { flexDirection: 'row', gap: sp['2'] },
  photo: { flex: 1, aspectRatio: 1, borderRadius: t.radius.md, borderWidth: 1, borderStyle: 'dashed', borderColor: c.borderStrong, alignItems: 'center', justifyContent: 'center', gap: 2, overflow: 'hidden' },
  textarea: { minHeight: 72, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, padding: sp['3'], fontSize: t.fontSize.base, color: c.text, textAlignVertical: 'top' },
  certify: { flexDirection: 'row', alignItems: 'center', gap: sp['3'] }
});
