'use client';

import { accountQueries, type AddressSuggestion, cartKeys, isApiError, preorderKeys, productKeys, searchKeys, serviceKeys, shopperQueries, siteKeys, ZipSchema } from '@afrotransact/api-client';
import { Crosshair, MapPin, Popover, useToast } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { type KeyboardEvent, type RefObject, useCallback, useEffect, useId, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './LocationPicker.module.css';

type Status = 'idle' | 'loading' | 'ready' | 'error';
type Input = { zip: string } | { lat: number; lon: number } | { suggestionId: string; sessionToken?: string };

const DEBOUNCE_MS = 200;

/** The ZIP in typed text only when the whole input is one ("78626"); "Georgetown" and "12 Main St" are searches. */
const zipOf = (text: string): string | null => {
  const t = text.trim();
  return ZipSchema.safeParse(t).success ? t : null;
};

/**
 * "Deliver to": a small popover under the header link (a bottom sheet on phones). Saved addresses first for a
 * signed-in shopper, then one city / address / ZIP combobox and a "Use my current location" text action. A pick applies
 * at once. Browser geolocation is only asked for on that click, never on load.
 */
export function LocationPicker({ open, onOpenChange, anchorRef }: { open: boolean; onOpenChange: (o: boolean) => void; anchorRef: RefObject<HTMLElement | null> }) {
  const t = useT();
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const api = browserApi();
  const listId = `lp${useId().replace(/:/g, '')}`;
  const me = useQuery({ ...accountQueries.me(api), enabled: open });
  const saved = useQuery({ ...shopperQueries.addresses(api), enabled: open && me.data?.authenticated === true });
  const [value, setValue] = useState('');
  const [items, setItems] = useState<AddressSuggestion[]>([]);
  const [status, setStatus] = useState<Status>('idle');
  const [active, setActive] = useState(-1);
  const [error, setError] = useState<string | null>(null);
  const [geoBusy, setGeoBusy] = useState(false);
  const [geoMsg, setGeoMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const token = useRef<string | undefined>(undefined);
  const skipSearch = useRef(false);

  // A fresh popover each time: nothing typed, no stale messages, a new autocomplete session.
  useEffect(() => {
    if (!open) return;
    setValue('');
    setItems([]);
    setStatus('idle');
    setActive(-1);
    setError(null);
    setGeoMsg('');
    token.current = undefined;
  }, [open]);

  useEffect(() => {
    if (skipSearch.current) {
      skipSearch.current = false;
      return;
    }
    const q = value.trim();
    // A ZIP needs no suggestions (Enter applies it); anything else is searched as the shopper types.
    if (q.length < 2 || /^\d{1,5}$/.test(q)) {
      setItems([]);
      setStatus('idle');
      return;
    }
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      setStatus('loading');
      api
        .searchAddresses(q, ctrl.signal, { kind: 'place', sessionToken: token.current })
        .then((r) => {
          token.current ||= r.sessionToken || undefined;
          setItems(r.suggestions);
          setActive(-1);
          setStatus('ready');
        })
        .catch((e: unknown) => {
          if ((e as { name?: string })?.name === 'AbortError') return;
          setItems([]);
          setStatus('error');
        });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [value, api]);

  const apply = useCallback(
    async (input: Input) => {
      setBusy(true);
      try {
        const loc = await api.setDeliveryLocation(input);
        qc.setQueryData(siteKeys.deliveryLocation(), loc);
        await Promise.all([siteKeys.home(), searchKeys.all, productKeys.all, cartKeys.all, preorderKeys.all, serviceKeys.all].map((queryKey) => qc.invalidateQueries({ queryKey })));
        onOpenChange(false);
        toast.show(t('location.delivering', { label: loc.label }));
        router.refresh();
      } catch (e) {
        setError(isApiError(e) ? (e.fieldErrors.zip ?? e.message) : t('location.notServed'));
      } finally {
        setBusy(false);
      }
    },
    [api, qc, onOpenChange, toast, router, t]
  );

  const pick = (s: AddressSuggestion) => {
    skipSearch.current = true;
    setValue(s.secondary ? `${s.label}, ${s.secondary}` : s.label);
    setItems([]);
    setStatus('idle');
    setError(null);
    void apply({ suggestionId: s.id, sessionToken: token.current });
  };

  const locateMe = () => {
    if (!('geolocation' in navigator)) {
      setGeoMsg(t('location.geoUnavailable'));
      return;
    }
    setGeoBusy(true);
    setGeoMsg('');
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setGeoBusy(false);
        void apply({ lat: pos.coords.latitude, lon: pos.coords.longitude });
      },
      (err) => {
        setGeoBusy(false);
        setGeoMsg(err.code === 1 ? t('location.geoDenied') : t('location.geoUnavailable'));
      },
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 600000 }
    );
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const n = items.length;
    if (e.key === 'ArrowDown' && n) {
      e.preventDefault();
      setActive((a) => (a + 1) % n);
    } else if (e.key === 'ArrowUp' && n) {
      e.preventDefault();
      setActive((a) => (a - 1 + n) % n);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const chosen = items[active] ?? items[0];
      const zip = zipOf(value);
      if (zip) void apply({ zip });
      else if (chosen) pick(chosen);
      else if (value.trim()) setError(t('location.invalidZip'));
    }
  };

  const showList = status !== 'idle' && value.trim().length >= 2;
  const addresses = saved.data ?? [];

  return (
    <Popover open={open} onOpenChange={onOpenChange} anchorRef={anchorRef} title={t('location.title')} closeLabel={t('location.close')}>
      {addresses.length > 0 && (
        <section aria-label={t('location.saved')} className={styles.saved}>
          <h3 className={styles.heading}>{t('location.saved')}</h3>
          <ul className={styles.list}>
            {addresses.map((a) => (
              <li key={a.id}>
                <button type="button" className={styles.row} disabled={busy} onClick={() => void apply({ zip: a.zip.slice(0, 5) })} data-testid="saved-address">
                  <MapPin className={styles.pin} aria-hidden />
                  <span className={styles.text}>
                    <strong>{a.name}</strong> <span className={styles.muted}>{[a.line1, a.city, a.state].filter(Boolean).join(', ')}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      <div className={styles.field}>
        <input
          className={styles.input}
          type="text"
          role="combobox"
          aria-label={t('location.addressLabel')}
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${listId}-err` : undefined}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          enterKeyHint="go"
          placeholder={t('location.placeholder')}
          value={value}
          disabled={busy}
          onChange={(e) => {
            setValue(e.target.value);
            setError(null);
          }}
          onKeyDown={onKeyDown}
        />
        {showList && (
          <ul id={listId} role="listbox" className={styles.options} aria-label={t('location.listLabel')}>
            {status === 'loading' && items.length === 0 && (
              <li role="presentation" className={styles.status}>
                {t('location.searching')}
              </li>
            )}
            {status === 'error' && (
              <li role="presentation" className={styles.status}>
                {t('location.searchFailed')}
              </li>
            )}
            {status === 'ready' && items.length === 0 && (
              <li role="presentation" className={styles.status}>
                {t('location.noMatches')}
              </li>
            )}
            {items.map((s, i) => (
              <li
                key={s.id}
                id={`${listId}-${i}`}
                role="option"
                aria-selected={i === active}
                className={styles.option}
                onMouseDown={(e) => {
                  e.preventDefault();
                  pick(s);
                }}
                onMouseEnter={() => setActive(i)}
              >
                <MapPin className={styles.pin} aria-hidden />
                <span>
                  <strong>{s.label}</strong>
                  {s.secondary ? <span className={styles.muted}>, {s.secondary}</span> : null}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p id={`${listId}-err`} className={styles.error} role="alert">
          {error}
        </p>
        <p className="at-visually-hidden" role="status">
          {status === 'ready' ? t('location.results', { n: items.length }) : ''}
        </p>
      </div>
      <button type="button" className={`link-button ${styles.geo}`} onClick={locateMe} disabled={geoBusy || busy}>
        <Crosshair aria-hidden />
        {geoBusy ? t('location.locating') : t('location.useGeo')}
      </button>
      <p className={styles.msg} role="status" aria-live="polite">
        {geoMsg}
      </p>
    </Popover>
  );
}
