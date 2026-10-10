/* Scanner (S083/S084, design: SCANNER): load-out, seller pickup, drop-off and return scans. Camera scanning of Code 128
   labels plus manual entry, which is accepted and flagged (method MANUAL). Unknown / already-scanned labels are
   rejected on the spot and again by the server. */
import { useLocalSearchParams, useRouter } from 'expo-router';
import { BarcodeIcon as Barcode, CheckCircleIcon as CheckCircle, KeyboardIcon as Keyboard } from 'phosphor-react-native';
import { useState } from 'react';
import { StyleSheet, TextInput, View } from 'react-native';

import { checkScan } from '@/domain/scan';
import { fcPackages, returnsExpected, stopPackages } from '@/domain/shift';
import { type RoutePackage, type ScanMethod } from '@/domain/types';
import { BarcodeCamera } from '@/features/scan/BarcodeCamera';
import { useDriver } from '@/session/DriverProvider';
import { c, mono, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Button, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

type Ctx = { kind: 'loadout' } | { kind: 'return' } | { kind: 'pickup' | 'drop'; stopId: string };

function parseCtx(raw: string): Ctx {
  if (raw === 'loadout' || raw === 'return') return { kind: raw };
  const [kind, ...rest] = raw.split('-');
  return { kind: kind === 'pickup' ? 'pickup' : 'drop', stopId: rest.join('-') };
}

const TITLES = { loadout: 'Load-out scan', pickup: 'Pickup scan', drop: 'Delivery scan', return: 'Return scan' } as const;
const LIST_TITLES = { loadout: 'FC manifest', pickup: 'Seller packages', drop: 'For this customer', return: 'Return to FC' } as const;

export default function Scan() {
  const { ctx: raw } = useLocalSearchParams<{ ctx: string }>();
  const ctx = parseCtx(raw ?? 'loadout');
  const router = useRouter();
  const toast = useToast();
  const { sync, engine } = useDriver();
  const [code, setCode] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const r = sync.route;
  if (!r) return <DriverScreen title={TITLES[ctx.kind]} back><Loading /></DriverScreen>;
  const stop = 'stopId' in ctx ? r.stops.find((s) => s.id === ctx.stopId) : undefined;
  const expected: RoutePackage[] = ctx.kind === 'loadout' ? fcPackages(r) : ctx.kind === 'return' ? returnsExpected(r) : stop ? stopPackages(r, stop) : [];
  const isDone = (p: RoutePackage) => (ctx.kind === 'loadout' ? p.loadScanned : ctx.kind === 'return' ? p.returnScanned : ctx.kind === 'pickup' ? p.pickupScanned : p.dropScanned);
  const scanned = expected.filter(isDone);
  const complete = scanned.length === expected.length;

  const scan = async (rawLabel: string, method: ScanMethod) => {
    const res = checkScan(rawLabel, expected.map((p) => p.label), scanned.map((p) => p.label));
    if (!res.ok) return setMsg({ ok: false, text: res.message });
    const s = { label: res.label, method };
    if (ctx.kind === 'loadout') await engine.record({ type: 'loadout.scanned', scan: s });
    else if (ctx.kind === 'return') await engine.record({ type: 'return.scanned', scan: s });
    else await engine.record({ type: 'stop.scanned', stopId: ctx.stopId, scan: s });
    setMsg({ ok: true, text: `✓ ${res.label} scanned${method === 'MANUAL' ? ' · typed by hand (flagged)' : ''}` });
    setCode('');
  };

  const finish = async () => {
    if (!complete) return setMsg({ ok: false, text: `${expected.length - scanned.length} label(s) still to scan.` });
    if (ctx.kind === 'loadout') {
      await engine.record({ type: 'loadout.completed' });
      toast('Load-out complete — start your route');
      router.navigate('/');
      return;
    }
    router.back();
  };

  return (
    <DriverScreen title={TITLES[ctx.kind]} back footer={<Button label={ctx.kind === 'loadout' ? 'Confirm load-out' : 'Done scanning'} kind={complete ? 'primary' : 'muted'} onPress={() => void finish()} />}>
      <BarcodeCamera hint={complete ? 'All labels scanned' : 'Point at the shipment label barcode'} paused={complete} onScan={(l) => void scan(l, 'CAMERA')} />
      <View style={styles.manual}>
        <Keyboard size={20} color={c.textMuted} />
        <TextInput value={code} onChangeText={(v) => setCode(v.toUpperCase())} onSubmitEditing={() => void scan(code, 'MANUAL')} placeholder="Or type the label number" placeholderTextColor={c.textSubtle} autoCapitalize="characters" autoCorrect={false} accessibilityLabel="Label number" style={styles.input} />
        <Button size="md" kind="dark" label="Enter" onPress={() => void scan(code, 'MANUAL')} />
      </View>
      {msg ? <AppText variant="small" color={msg.ok ? c.success : c.danger} accessibilityLiveRegion="assertive">{msg.text}</AppText> : null}
      <View style={styles.between}>
        <AppText variant="bodyStrong">{LIST_TITLES[ctx.kind]}</AppText>
        <AppText variant="bodyStrong">{scanned.length} / {expected.length}</AppText>
      </View>
      {expected.map((p) => {
        const ok = isDone(p);
        return (
          <View key={p.id} style={[styles.pkg, ok ? { borderColor: t.green['200'], backgroundColor: c.successBg } : null]}>
            <View style={styles.between}>
              <AppText variant="bodyStrong" style={{ fontFamily: mono }}>{p.label}</AppText>
              <View style={styles.state}>
                {ok ? <CheckCircle size={16} weight="fill" color={c.success} /> : <Barcode size={16} color={c.textMuted} />}
                <AppText variant="caption" color={ok ? c.success : c.textMuted} style={{ fontWeight: t.fontWeight.bold }}>{ok ? 'SCANNED' : 'WAITING'}</AppText>
              </View>
            </View>
            <AppText variant="small" color={c.textMuted}>{p.description} · {p.orderNumber} · {p.perishable ? 'perishable' : 'standard'}{p.manualEntry ? ' · typed by hand' : ''}</AppText>
          </View>
        );
      })}
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  manual: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  input: { flex: 1, height: t.size.controlLg, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, paddingHorizontal: sp['3'], fontSize: t.fontSize.base, color: c.text, fontFamily: mono },
  between: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  pkg: { gap: 4, padding: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: c.border },
  state: { flexDirection: 'row', alignItems: 'center', gap: 4 }
});
