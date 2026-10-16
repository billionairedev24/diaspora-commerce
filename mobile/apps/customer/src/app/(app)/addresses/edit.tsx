/* Add or edit a delivery address (native form). The BFF checks deliverability: an address it can't verify comes back
   as a street-field problem and the person chooses to fix it or keep it as typed. */
import { AddressInputSchema, isApiError, US_STATES } from '@afrotransact/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { CaretDownIcon as CaretDown, WarningCircleIcon as WarningCircle } from 'phosphor-react-native';
import { useMemo, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, StyleSheet, Switch, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { accountKeys, checkoutKeys } from '@/api/keys';
import { useAddresses } from '@/api/queries';
import { c, sp, t } from '@/theme';
import { PickerSheet } from '@/ui/PickerSheet';
import { AppText, Banner, Button, Field } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';
import { Sheet } from '@/ui/Sheet';
import { useToast } from '@/ui/Toast';

interface Draft {
  name: string;
  phone: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  zip: string;
  isDefault: boolean;
}

const EMPTY: Draft = { name: '', phone: '', line1: '', line2: '', city: '', state: '', zip: '', isDefault: false };
const STATE_OPTIONS = US_STATES.map(([value, label]) => ({ value, label: `${label} (${value})` }));

export default function EditAddress() {
  const { id } = useLocalSearchParams<{ id?: string }>();
  const router = useRouter();
  const api = useApi();
  const qc = useQueryClient();
  const toast = useToast();
  const addresses = useAddresses();
  const existing = id ? addresses.data?.find((a) => a.id === id) : undefined;
  const initial = useMemo<Draft>(
    () => (existing ? { name: existing.name, phone: existing.phone ?? '', line1: existing.line1, line2: existing.line2 ?? '', city: existing.city, state: existing.state, zip: existing.zip, isDefault: existing.isDefault } : { ...EMPTY, isDefault: addresses.data?.length === 0 }),
    [existing, addresses.data?.length]
  );
  const [draft, setDraft] = useState<Draft | null>(null);
  const d = draft ?? initial;
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [unverified, setUnverified] = useState<string | null>(null);
  const [pickState, setPickState] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => {
    setDraft({ ...d, [k]: v });
    setErrors((e) => ({ ...e, [k]: '' }));
    if (k === 'line1' || k === 'line2' || k === 'zip') setUnverified(null);
  };

  const done = () => {
    void qc.invalidateQueries({ queryKey: accountKeys.addresses() });
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
    toast(existing ? 'Address saved' : 'Address added');
    router.back();
  };
  const save = useMutation({
    mutationFn: (acceptUnverified: boolean) => {
      const body = { ...d, line2: d.line2.trim() || undefined, acceptUnverified };
      return existing ? api.bff.updateAddress(existing.id, body) : api.bff.createAddress(body);
    },
    onSuccess: done,
    onError: (e) => {
      if (!isApiError(e)) return setErrors({ _: 'We couldn’t save the address. Check your connection and try again.' });
      const f = e.fieldErrors;
      const street = f.line1 ?? f.line2;
      if (street && Object.keys(f).every((k) => k === 'line1' || k === 'line2')) setUnverified(street);
      else setErrors(Object.keys(f).length ? f : { _: e.message });
    }
  });
  const makeDefault = useMutation({ mutationFn: () => api.bff.setDefaultAddress(existing?.id ?? ''), onSuccess: done, onError: (e) => toast(isApiError(e) ? e.message : 'Couldn’t change the default') });
  const remove = useMutation({
    mutationFn: () => api.bff.deleteAddress(existing?.id ?? ''),
    onSuccess: () => { setConfirmDelete(false); done(); },
    onError: (e) => { setConfirmDelete(false); toast(isApiError(e) ? e.message : 'Couldn’t remove the address'); }
  });

  const submit = () => {
    const parsed = AddressInputSchema.safeParse({ ...d, line2: d.line2.trim() || undefined });
    if (!parsed.success) {
      const next: Record<string, string> = {};
      parsed.error.issues.forEach((i) => { next[String(i.path[0])] ??= i.message; });
      return setErrors(next);
    }
    setErrors({});
    setUnverified(null);
    save.mutate(false);
  };

  return (
    <Screen
      header={<SubHeader title={existing ? 'Edit address' : 'New address'} />}
      footer={<Button label={existing ? 'Save address' : 'Add address'} onPress={submit} loading={save.isPending && !unverified} disabled={id !== undefined && !existing} />}
    >
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ gap: sp['3'] }}>
        <Field label="Full name" value={d.name} onChangeText={(v) => set('name', v)} error={errors.name} autoComplete="name" textContentType="name" autoCapitalize="words" returnKeyType="next" />
        <Field label="Phone" value={d.phone} onChangeText={(v) => set('phone', v)} error={errors.phone} keyboardType="phone-pad" autoComplete="tel" textContentType="telephoneNumber" hint="For the driver, if they need to reach you." />
        <Field label="Street address" value={d.line1} onChangeText={(v) => set('line1', v)} error={errors.line1} autoComplete="street-address" textContentType="streetAddressLine1" autoCapitalize="words" />
        <Field label="Apt, suite, unit (optional)" value={d.line2} onChangeText={(v) => set('line2', v)} error={errors.line2} textContentType="streetAddressLine2" autoCapitalize="words" />
        <Field label="City" value={d.city} onChangeText={(v) => set('city', v)} error={errors.city} textContentType="addressCity" autoCapitalize="words" />
        <View style={styles.pair}>
          <View style={{ flex: 1, gap: sp['1'] }}>
            <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>State</AppText>
            <Pressable accessibilityRole="button" accessibilityLabel={`State, ${d.state || 'choose'}`} onPress={() => setPickState(true)} style={[styles.select, errors.state ? { borderColor: c.danger } : null]}>
              <AppText color={d.state ? c.text : c.textSubtle} style={{ flex: 1 }}>{d.state || 'Choose'}</AppText>
              <CaretDown size={16} color={c.textMuted} />
            </Pressable>
            {errors.state ? <AppText variant="caption" color={c.danger} accessibilityRole="alert">{errors.state}</AppText> : null}
          </View>
          <View style={{ flex: 1 }}>
            <Field label="ZIP code" value={d.zip} onChangeText={(v) => set('zip', v.replace(/[^\d-]/g, '').slice(0, 10))} error={errors.zip} keyboardType="number-pad" autoComplete="postal-code" textContentType="postalCode" />
          </View>
        </View>
        {unverified ? (
          <View style={styles.callout} accessibilityRole="alert">
            <View style={{ flexDirection: 'row', gap: sp['2'] }}>
              <WarningCircle size={20} color={c.warning} />
              <AppText variant="small" style={{ flex: 1 }}><AppText variant="small" style={{ fontWeight: t.fontWeight.bold }}>We couldn’t verify this address. </AppText>{unverified}</AppText>
            </View>
            <View style={{ flexDirection: 'row', gap: sp['2'] }}>
              <Button size="md" kind="outline" label="Fix it" onPress={() => setUnverified(null)} style={{ flex: 1 }} />
              <Button size="md" label="Keep mine" onPress={() => save.mutate(true)} loading={save.isPending} style={{ flex: 1 }} />
            </View>
          </View>
        ) : null}
        <View style={styles.switchRow}>
          <AppText style={{ flex: 1 }}>Use as my default address</AppText>
          <Switch value={d.isDefault} onValueChange={(v) => set('isDefault', v)} disabled={existing?.isDefault} accessibilityLabel="Use as my default address" />
        </View>
        {errors._ ? <Banner tone="danger" icon={WarningCircle}>{errors._}</Banner> : null}
        {existing ? (
          <View style={{ gap: sp['2'], paddingTop: sp['2'] }}>
            {!existing.isDefault ? <Button kind="outline" label="Make default" onPress={() => makeDefault.mutate()} loading={makeDefault.isPending} /> : null}
            <Button kind="outline" label="Remove this address" onPress={() => setConfirmDelete(true)} />
          </View>
        ) : null}
      </KeyboardAvoidingView>
      <PickerSheet visible={pickState} title="State" options={STATE_OPTIONS} value={d.state} onPick={(v) => set('state', v)} onClose={() => setPickState(false)} />
      <Sheet visible={confirmDelete} onClose={() => setConfirmDelete(false)}>
        <AppText variant="heading">Remove this address?</AppText>
        <AppText color={c.textMuted}>{existing ? `${existing.line1}, ${existing.city}` : ''}</AppText>
        <Button label="Remove" onPress={() => remove.mutate()} loading={remove.isPending} />
        <Button kind="outline" label="Keep it" onPress={() => setConfirmDelete(false)} />
      </Sheet>
    </Screen>
  );
}

const styles = StyleSheet.create({
  pair: { flexDirection: 'row', gap: sp['3'], alignItems: 'flex-start' },
  select: { flexDirection: 'row', alignItems: 'center', minHeight: t.size.controlLg, paddingHorizontal: sp['3'], borderRadius: t.radius.md, borderWidth: 1, borderColor: c.borderStrong },
  callout: { gap: sp['3'], padding: sp['3'], borderRadius: t.radius.md, backgroundColor: c.warningBg },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: sp['3'], minHeight: 48 }
});
