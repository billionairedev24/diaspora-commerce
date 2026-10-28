/* Street-address field with suggestions from the first character (S139): picking one fills street, city, state, ZIP.
   The search and the Place Details resolve go through the BFF geo endpoints, one session token per address entry. */
import { addressLookup, newAddressSessionToken, type PostalAddress, type ResolvableAddressSuggestion } from '@afrotransact/api-client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, View } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { c, sp } from '@/theme';
import { AppText, Field } from '@/ui/primitives';

const DEBOUNCE_MS = 200;

export interface AddressSuggestProps {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  /** The picked suggestion's structured address. */
  onPick: (a: PostalAddress) => void;
  error?: string;
}

export function AddressSuggest({ label, value, onChangeText, onPick, error }: AddressSuggestProps) {
  const api = useApi();
  const lookup = useMemo(() => addressLookup(api.bff), [api]);
  const [items, setItems] = useState<ResolvableAddressSuggestion[]>([]);
  const [open, setOpen] = useState(false);
  const token = useRef(newAddressSessionToken());
  const picked = useRef(false);

  useEffect(() => {
    const q = value.trim();
    if (picked.current) {
      picked.current = false;
      return;
    }
    if (q.length < 1 || !open) {
      setItems([]);
      return;
    }
    const ctl = new AbortController();
    const timer = setTimeout(() => {
      lookup(q, { signal: ctl.signal, sessionToken: token.current })
        .then((r) => setItems(r.slice(0, 5)))
        .catch(() => setItems([]));
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  }, [value, open, lookup]);

  const pick = async (s: ResolvableAddressSuggestion) => {
    const address = s.resolve ? await s.resolve().catch(() => s.address) : s.address;
    picked.current = true;
    setItems([]);
    setOpen(false);
    token.current = newAddressSessionToken();
    onChangeText(address.line1);
    onPick(address);
  };

  return (
    <View style={{ gap: sp['1'] }}>
      <Field
        label={label}
        value={value}
        onChangeText={(v) => { setOpen(true); onChangeText(v); }}
        error={error}
        autoComplete="street-address"
        textContentType="streetAddressLine1"
        autoCapitalize="words"
      />
      {items.length > 0 ? (
        <View accessibilityRole="list" style={{ borderWidth: 1, borderColor: c.border, borderRadius: 8, backgroundColor: c.surface }}>
          {items.map((s) => (
            <Pressable key={s.id} accessibilityRole="button" accessibilityLabel={`${s.label}, ${s.secondary}`} onPress={() => void pick(s)} style={{ paddingVertical: sp['2'], paddingHorizontal: sp['3'] }}>
              <AppText>{s.label}</AppText>
              <AppText variant="caption" color={c.textMuted}>{s.secondary}</AppText>
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}
