/* Stop (design: STOP). Arrive → scan → seller pickup (who handed over) or proof of delivery (S085: customer code,
   someone else + signature, safe place; live GPS-stamped photo within 150 m) → or report a problem (S084/S086:
   customer unavailable needs call, text and a 5-minute wait before reattempt / pickup point / return). */
import { useLocalSearchParams, useRouter } from 'expo-router';
import { BarcodeIcon as Barcode, CameraIcon as Camera, ChatTextIcon as ChatText, CheckCircleIcon as CheckCircle, CircleIcon as Circle, KeyIcon as Key, NoteIcon as Note, PhoneIcon as Phone, RadioButtonIcon as RadioButton, TimerIcon as Timer, WarningIcon as Warning } from 'phosphor-react-native';
import { useEffect, useState } from 'react';
import { Image, Linking, StyleSheet, TextInput, View } from 'react-native';

import { handoverOptions, POD_MAX_DISTANCE_M, podCheck, podSummary, RELATIONSHIPS, SAFE_PLACES } from '@/domain/pod';
import { contactStateFrom, DELIVERY_PROBLEMS, noShowReady, PICKUP_PROBLEMS, validateDeliveryProblem, validatePickupProblem, waitLabel, waitRemainingMs } from '@/domain/problems';
import { allDropScanned, canArrive, canConfirmPickup, canStartRoute, perishableAt, stopPackages } from '@/domain/shift';
import { type DeliveryProblemType, type DeliveryResolution, type HandoverMethod, type PhotoCapture, type PickupProblemType, type PickupResolution } from '@/domain/types';
import { LiveCamera } from '@/features/camera/LiveCamera';
import { STOP_LABEL } from '@/features/route/labels';
import { SignaturePad } from '@/features/signature/SignaturePad';
import { useDriver } from '@/session/DriverProvider';
import { c, mono, sp, t } from '@/theme';
import { DriverScreen } from '@/ui/DriverScreen';
import { AppText, Banner, Button, Card, Chip, Field, Loading } from '@/ui/primitives';
import { useToast } from '@/ui/Toast';

function Radio({ on }: { on: boolean }) {
  return on ? <RadioButton size={22} weight="fill" color={c.text} /> : <Circle size={22} color={c.textMuted} />;
}

function useNow(active: boolean) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [active]);
  return now;
}

export default function StopScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const toast = useToast();
  const { sync, engine } = useDriver();
  const r = sync.route;
  const stop = r?.stops.find((s) => s.id === id);
  // proof of delivery
  const [method, setMethod] = useState<HandoverMethod>('CUSTOMER');
  const [code, setCode] = useState('');
  const [recipient, setRecipient] = useState('');
  const [relationship, setRelationship] = useState('');
  const [signature, setSignature] = useState<string | null>(null);
  const [safePlace, setSafePlace] = useState('');
  const [photo, setPhoto] = useState<PhotoCapture | null>(null);
  // pickup
  const [handedOverBy, setHandedOverBy] = useState('');
  const [missingReason, setMissingReason] = useState('');
  // problems
  const [exOpen, setExOpen] = useState(false);
  const [exType, setExType] = useState<DeliveryProblemType | PickupProblemType | null>(null);
  const [exRes, setExRes] = useState<DeliveryResolution | PickupResolution | null>(null);
  const [exNote, setExNote] = useState('');
  const [exPhoto, setExPhoto] = useState<PhotoCapture | null>(null);
  const [cam, setCam] = useState<'pod' | 'ex' | null>(null);
  const [err, setErr] = useState('');
  const contact = contactStateFrom(stop?.contacts ?? []);
  const waiting = waitRemainingMs(contact.waitStartedAt, Date.now());
  const now = useNow(exOpen && waiting !== null && waiting > 0);

  useEffect(() => {
    if (stop?.brief?.preference === 'door' && stop.safePlaceAllowed) { setMethod('SAFE_PLACE'); setSafePlace(stop.brief.place ?? ''); }
    else if (stop?.brief?.preference === 'desk') setMethod('SOMEONE_ELSE');
  }, [stop?.id]); // eslint-disable-line react-hooks/exhaustive-deps -- initialise once per stop

  if (!r || !stop) return <DriverScreen title="Stop" back><Loading /></DriverScreen>;
  const pickup = stop.kind === 'PICKUP';
  const pk = stopPackages(r, stop);
  const perishable = perishableAt(r, stop);
  const scannedCount = pk.filter((p) => (pickup ? p.pickupScanned : p.dropScanned)).length;
  const open = stop.status === 'PENDING' || stop.status === 'RETRY';
  const arrived = stop.status === 'ARRIVED';
  const closed = !open && !arrived;
  const n = r.stops.indexOf(stop) + 1;
  const back = () => router.navigate('/route');

  const arrive = async () => {
    const e = canArrive(r, stop);
    if (e) return setErr(e);
    setErr('');
    await engine.record({ type: 'stop.arrived', stopId: stop.id });
  };

  const complete = async () => {
    setErr('');
    if (pickup) {
      const e = canConfirmPickup(r, stop, handedOverBy, missingReason);
      if (e) return setErr(e);
      await engine.record({ type: 'pickup.confirmed', stopId: stop.id, pickup: { handedOverBy: handedOverBy.trim(), ...(scannedCount < pk.length ? { missingReason: missingReason.trim() } : {}) } });
      toast('Pickup confirmed — shipments now “Picked up”');
      return back();
    }
    if (!allDropScanned(r, stop)) return setErr(`Scan every package first (${scannedCount}/${pk.length}).`);
    const e = podCheck({ method, code, recipient, relationship, signatureLocalUri: signature, safePlace, photo, perishable, safePlaceAllowed: stop.safePlaceAllowed, stop: { lat: stop.lat, lon: stop.lon } });
    if (e || !photo) return setErr(e ?? 'Take a proof-of-delivery photo.');
    await engine.record({
      type: 'delivery.completed',
      stopId: stop.id,
      delivery: {
        handoverMethod: method,
        photo,
        ...(method === 'CUSTOMER' ? { handoverCode: code } : {}),
        ...(method === 'SOMEONE_ELSE' ? { recipientName: recipient.trim(), relationship, ...(signature ? { signatureLocalUri: signature } : {}) } : {}),
        ...(method === 'SAFE_PLACE' ? { safePlace } : {})
      }
    }, { note: podSummary(method, recipient, relationship, safePlace) });
    toast('Delivered — customer and seller notified');
    back();
  };

  const contactCustomer = async (kind: 'CALL' | 'TEXT') => {
    await engine.record({ type: 'customer.contacted', stopId: stop.id, contact: kind });
    // Calls and texts go through the masked number from the delivery brief (orders-service).
    const phone = stop.brief?.phoneMasked;
    if (phone && /\d{4}$/.test(phone) && !phone.includes('•')) void Linking.openURL(`${kind === 'CALL' ? 'tel' : 'sms'}:${phone.replace(/[^\d+]/g, '')}`);
    else toast(kind === 'CALL' ? 'Calling via masked number — call recorded for safety' : 'Sent: “Your AfroTransact driver is outside.”');
  };

  const submitProblem = async () => {
    if (pickup) {
      const e = validatePickupProblem(exType as PickupProblemType | null, exRes as PickupResolution | null, exNote, !!exPhoto);
      if (e) return setErr(e);
      await engine.record({ type: 'pickup.problem', stopId: stop.id, pickupProblem: { type: exType as PickupProblemType, resolution: exRes as PickupResolution, note: exNote.trim(), ...(exPhoto ? { photo: exPhoto } : {}) } });
    } else {
      const e = validateDeliveryProblem({ type: exType as DeliveryProblemType | null, resolution: exRes as DeliveryResolution | null, note: exNote, hasPhoto: !!exPhoto, contact, reattempts: stop.reattempts, now: Date.now() });
      if (e) return setErr(e);
      await engine.record({ type: 'delivery.problem', stopId: stop.id, problem: { type: exType as DeliveryProblemType, resolution: exRes as DeliveryResolution, note: exNote.trim(), ...(exPhoto ? { photo: exPhoto } : {}) } });
    }
    const label = (pickup ? PICKUP_PROBLEMS : DELIVERY_PROBLEMS).find((p) => p.type === exType)?.label ?? 'Problem';
    toast(`${label} logged · customer and dispatch notified`);
    setExOpen(false);
    if (exRes !== 'WAIT') back();
  };

  const problemDefs = pickup ? PICKUP_PROBLEMS : DELIVERY_PROBLEMS;
  const def = problemDefs.find((p) => p.type === exType);
  const readyErr = canStartRoute(r);

  return (
    <DriverScreen title={`Stop ${n}`} back={back} footer={arrived && !exOpen ? (
      <>
        <Button label={pickup ? 'Confirm pickup' : 'Mark delivered'} onPress={() => void complete()} />
        <Button kind="outline" icon={Warning} label="Report a problem" onPress={() => { setExOpen(true); setErr(''); }} />
      </>
    ) : open ? <Button label="I’ve arrived" onPress={() => void arrive()} /> : exOpen ? (
      <>
        <Button kind="danger" label="Submit" onPress={() => void submitProblem()} />
        <Button kind="ghost" label="Cancel" onPress={() => setExOpen(false)} />
      </>
    ) : undefined}>
      <View style={styles.map}>
        <AppText variant="small" color={c.textInverse}>Map · {stop.window}</AppText>
        <AppText variant="small" color={t.gold['500']} accessibilityRole="link" onPress={() => void Linking.openURL(`https://maps.google.com/?q=${encodeURIComponent(`${stop.addressLine}, ${stop.city} ${stop.postalCode}`)}`)} style={{ fontWeight: t.fontWeight.bold }}>Navigate ↗</AppText>
      </View>
      <View style={{ gap: 2 }}>
        <AppText variant="caption" color={c.textMuted}>STOP {n} · {pickup ? 'PICK UP' : 'DELIVER'} · {STOP_LABEL[stop.status].toUpperCase()}</AppText>
        <AppText variant="title">{stop.name}</AppText>
        <AppText>{stop.addressLine}, {stop.city} {stop.postalCode}</AppText>
        <AppText variant="small" color={c.textMuted}>{stop.window}{stop.note ? ` · ${stop.note}` : ''}</AppText>
      </View>
      {stop.brief ? (
        <Card>
          <View style={styles.row}><Note size={18} color={c.text} /><AppText variant="bodyStrong">Customer instructions</AppText></View>
          <AppText variant="bodyStrong">{stop.brief.preference === 'hand' ? 'Hand to customer' : stop.brief.preference === 'door' ? `Leave at ${stop.brief.place ?? 'door'} if not in` : 'Front desk or neighbour is OK'}</AppText>
          {stop.brief.instructions ? <AppText>“{stop.brief.instructions}”</AppText> : null}
          <AppText variant="small" color={c.textMuted}>{stop.brief.contact === 'text' ? 'Text on arrival' : stop.brief.contact === 'call' ? 'Call on arrival' : 'Don’t contact — follow instructions'}</AppText>
          {stop.handoverRequired || perishable ? <View style={styles.row}><Key size={16} color={c.text} /><AppText variant="small">Delivery code required at handover{perishable ? ' · perishable, no safe drop' : ''}</AppText></View> : null}
        </Card>
      ) : null}
      {!pickup ? (
        <View style={styles.row}>
          <Button size="md" kind="outline" icon={Phone} label="Call" onPress={() => void contactCustomer('CALL')} style={{ flex: 1 }} />
          <Button size="md" kind="outline" icon={ChatText} label="Text" onPress={() => void contactCustomer('TEXT')} style={{ flex: 1 }} />
        </View>
      ) : null}
      <View style={{ gap: sp['1'] }}>
        {pk.map((p) => {
          const ok = pickup ? p.pickupScanned : p.dropScanned;
          return (
            <View key={p.id} style={styles.row}>
              {ok ? <CheckCircle size={18} weight="fill" color={c.success} /> : <Barcode size={18} color={c.textMuted} />}
              <AppText variant="small" style={{ flex: 1 }}><AppText variant="small" style={{ fontFamily: mono, fontWeight: t.fontWeight.bold }}>{p.label}</AppText> · {p.description}</AppText>
            </View>
          );
        })}
      </View>

      {open && readyErr ? (
        <Banner tone="warning">Before you can arrive: {readyErr}</Banner>
      ) : null}
      {closed ? <Banner tone={stop.status === 'DONE' ? 'success' : 'danger'}>{STOP_LABEL[stop.status]}{stop.closedNote ? ` · ${stop.closedNote}` : ''}</Banner> : null}
      {stop.status === 'RETRY' ? <Banner tone="warning">Retry later · reattempt {stop.reattempts} of 2. Finish or fail this stop before the shift ends.</Banner> : null}

      {arrived && !exOpen ? (
        <View style={{ gap: sp['3'] }}>
          <Button kind="dark" icon={Barcode} label={`Scan packages (${scannedCount}/${pk.length})`} onPress={() => router.push(`/scan/${pickup ? 'pickup' : 'drop'}-${stop.id}`)} />
          {pickup ? (
            <>
              <Field label="Handed over by (seller staff name)" value={handedOverBy} onChangeText={setHandedOverBy} autoCapitalize="words" />
              {scannedCount < pk.length ? <Field label="Missing packages — reason" value={missingReason} onChangeText={setMissingReason} placeholder="e.g. tray not ready" /> : null}
            </>
          ) : (
            <>
              <AppText variant="bodyStrong">How was it delivered?</AppText>
              {handoverOptions(perishable, stop.safePlaceAllowed, stop.brief?.place).map((o) => (
                <Card key={o.method} selected={method === o.method} onPress={o.disabled ? undefined : () => { setMethod(o.method); setErr(''); }} style={o.disabled ? { opacity: 0.5 } : undefined} accessibilityLabel={o.label}>
                  <View style={styles.row}>
                    <View style={{ flex: 1 }}>
                      <AppText variant="bodyStrong">{o.label}</AppText>
                      <AppText variant="small" color={c.textMuted}>{o.hint}</AppText>
                    </View>
                    <Radio on={method === o.method} />
                  </View>
                </Card>
              ))}
              {method === 'CUSTOMER' ? <Field label="Customer’s 4-digit code" value={code} onChangeText={(v) => setCode(v.replace(/\D/g, '').slice(0, 4))} keyboardType="number-pad" placeholder="••••" hint="Sent to the customer by SMS when the route started" /> : null}
              {method === 'SOMEONE_ELSE' ? (
                <>
                  <Field label="Received by" value={recipient} onChangeText={setRecipient} autoCapitalize="words" />
                  <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>Relationship</AppText>
                  <View style={styles.chips}>{RELATIONSHIPS.map((x) => <Chip key={x} label={x} selected={relationship === x} onPress={() => setRelationship(x)} />)}</View>
                  <SignaturePad onChange={setSignature} />
                </>
              ) : null}
              {method === 'SAFE_PLACE' ? (
                <>
                  <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>Safe place</AppText>
                  <View style={styles.chips}>{SAFE_PLACES.map((x) => <Chip key={x} label={x} selected={safePlace === x} onPress={() => setSafePlace(x)} />)}</View>
                  <AppText variant="caption" color={c.textMuted}>Allowed because the customer opted in and nothing here is perishable or ID-restricted.</AppText>
                </>
              ) : null}
              {photo?.localUri ? (
                <View>
                  <Image source={{ uri: photo.localUri }} style={styles.pod} accessibilityLabel="Proof of delivery photo" />
                  <AppText variant="caption" color={c.textMuted}>{new Date(photo.capturedAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })} · GPS {photo.lat?.toFixed(4) ?? '—'}, {photo.lon?.toFixed(4) ?? '—'} · {photo.stampRouteId}</AppText>
                </View>
              ) : null}
              <Button kind="outline" icon={photo ? CheckCircle : Camera} label={photo ? 'Retake photo' : 'Take proof-of-delivery photo'} onPress={() => setCam('pod')} />
              <AppText variant="caption" color={c.textMuted}>Photos are GPS- and time-stamped and must be within {POD_MAX_DISTANCE_M} m of the address.</AppText>
            </>
          )}
        </View>
      ) : null}

      {exOpen ? (
        <View style={{ gap: sp['3'] }}>
          <AppText variant="heading">What went wrong?</AppText>
          {problemDefs.map((p) => (
            <Card key={p.type} selected={exType === p.type} onPress={() => { setExType(p.type); setExRes(p.resolutions[0]?.v ?? null); setErr(''); }} accessibilityLabel={p.label}>
              <View style={styles.row}>
                <View style={{ flex: 1 }}>
                  <AppText variant="bodyStrong">{p.label}</AppText>
                  <AppText variant="small" color={c.textMuted}>{p.hint}</AppText>
                </View>
                <Radio on={exType === p.type} />
              </View>
            </Card>
          ))}
          {exType === 'CUSTOMER_UNAVAILABLE' ? (
            <Card>
              <View style={styles.row}>
                <Button size="md" kind={contact.called ? 'muted' : 'outline'} icon={Phone} label={contact.called ? '✓ Called' : 'Call customer'} onPress={() => void contactCustomer('CALL')} style={{ flex: 1 }} />
                <Button size="md" kind={contact.texted ? 'muted' : 'outline'} icon={ChatText} label={contact.texted ? '✓ Texted' : 'Text customer'} onPress={() => void contactCustomer('TEXT')} style={{ flex: 1 }} />
              </View>
              <Button size="md" kind={noShowReady(contact, now) ? 'muted' : 'dark'} icon={Timer} label={waitLabel(contact.waitStartedAt, now)} disabled={contact.waitStartedAt !== null} onPress={() => void engine.record({ type: 'customer.contacted', stopId: stop.id, contact: 'WAIT_STARTED' })} />
              <AppText variant="caption" color={c.textMuted}>Policy: call and text, then wait 5 minutes before choosing a fallback. The customer gets a live “driver is waiting” message.</AppText>
            </Card>
          ) : null}
          {def ? (
            <>
              <AppText variant="small" style={{ fontWeight: t.fontWeight.semibold }}>What happens next</AppText>
              <View style={styles.chips}>{def.resolutions.map((x) => <Chip key={x.v} label={x.label} selected={exRes === x.v} onPress={() => setExRes(x.v)} />)}</View>
              <TextInput value={exNote} onChangeText={setExNote} multiline placeholder={exType === 'ACCESS_ISSUE' ? 'e.g. Gate code 4471 didn’t work; buzzer unanswered' : 'What happened?'} placeholderTextColor={c.textSubtle} accessibilityLabel="Notes" style={styles.textarea} />
              {exPhoto?.localUri ? <Image source={{ uri: exPhoto.localUri }} style={styles.pod} accessibilityLabel="Problem photo" /> : null}
              <Button kind="outline" icon={exPhoto ? CheckCircle : Camera} label={exPhoto ? 'Photo captured ✓' : def.photo ? 'Take photo (required)' : 'Take photo (optional)'} onPress={() => setCam('ex')} />
            </>
          ) : null}
        </View>
      ) : null}

      {err ? <Banner tone="danger">{err}</Banner> : null}
      <LiveCamera visible={cam !== null} title={cam === 'ex' ? 'Photo of the problem' : 'Proof of delivery'} routeId={r.id} onClose={() => setCam(null)} onCapture={(p) => { if (cam === 'ex') setExPhoto(p); else setPhoto(p); setErr(''); }} />
    </DriverScreen>
  );
}

const styles = StyleSheet.create({
  map: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', height: 110, padding: sp['4'], borderRadius: t.radius.lg, backgroundColor: t.ink['900'] },
  row: { flexDirection: 'row', alignItems: 'center', gap: sp['2'] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: sp['2'] },
  pod: { width: '100%', aspectRatio: 4 / 3, borderRadius: t.radius.md, backgroundColor: t.ink['100'] },
  textarea: { minHeight: 64, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, padding: sp['3'], fontSize: t.fontSize.base, color: c.text, textAlignVertical: 'top' }
});
