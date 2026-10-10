/* End shift (design: END): every stop finished (reattempts resolved), failed packages scanned back into the FC, end
   odometer above the start. */
import { useRouter } from 'expo-router';
import { BarcodeIcon as Barcode } from 'phosphor-react-native';
import { useState } from 'react';

import { canEndShift, returnsExpected } from '@/domain/shift';
import { useDriver } from '@/session/DriverProvider';
import { c } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button, Card, Field, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

export default function EndShift() {
  const router = useRouter();
  const toast = useToast();
  const { sync, engine } = useDriver();
  const [odo, setOdo] = useState('');
  const [err, setErr] = useState('');
  const r = sync.route;
  if (!r) return <DriverScreen title="End shift" back><Loading /></DriverScreen>;
  const delivered = r.stops.filter((s) => s.kind === 'DROPOFF' && s.status === 'DONE').length;
  const failed = r.stops.filter((s) => s.status === 'FAILED').length;
  const returns = returnsExpected(r);
  const scannedBack = returns.filter((p) => p.returnScanned).length;
  const end = async () => {
    const e = canEndShift(r, odo);
    if (e) return setErr(e);
    await engine.record({ type: 'shift.ended', odometer: Number(odo) });
    toast('Shift ended. Thank you!');
    router.navigate('/');
  };
  return (
    <DriverScreen title="End shift" back footer={<Button label="End shift" disabled={r.ended} onPress={() => void end()} />}>
      <Card>
        <AppText variant="heading">Shift summary</AppText>
        <AppText>{delivered} delivered · {failed} failed · {returns.length} to return</AppText>
        <AppText variant="small" color={c.textMuted}>Failed and undelivered packages must be scanned back in at {r.depotName}.</AppText>
      </Card>
      {returns.length ? <Button kind="dark" icon={Barcode} label={`Scan returns (${scannedBack}/${returns.length})`} onPress={() => router.push('/scan/return')} /> : null}
      <Field label="End odometer (mi)" value={odo} onChangeText={(v) => { setOdo(v.replace(/\D/g, '')); setErr(''); }} keyboardType="number-pad" hint={`Start: ${r.startOdometer ?? r.lastOdometer}`} />
      {err ? <Banner tone="danger">{err}</Banner> : null}
      {r.ended ? <Banner tone="success">Shift complete.</Banner> : null}
    </DriverScreen>
  );
}
