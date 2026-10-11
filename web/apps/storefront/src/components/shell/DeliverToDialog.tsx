'use client';

import { cartKeys, isApiError, preorderKeys, productKeys, searchKeys, siteKeys, ZipSchema } from '@afrotransact/api-client';
import { AddressAutocomplete, type AddressSuggestionItem, Button, Crosshair, Dialog, type SearchAddresses, useToast } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { useCallback, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './DeliverToDialog.module.css';

/** Pluggable address search backed by the BFF geo endpoint (Google Places / OSM in production, fixtures in mocks). */
export const bffAddressSearch: SearchAddresses = async (q, { signal }) => (await browserApi().searchAddresses(q, signal)).suggestions;

/** "Choose your location": current location, or an address/ZIP with autocomplete. */
export function DeliverToDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const t = useT();
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [geoBusy, setGeoBusy] = useState(false);
  const [geoMsg, setGeoMsg] = useState('');

  const apply = useCallback(
    async (input: { zip: string } | { lat: number; lon: number }) => {
      try {
        const loc = await browserApi().setDeliveryLocation(input);
        qc.setQueryData(siteKeys.deliveryLocation(), loc);
        await Promise.all([siteKeys.home(), searchKeys.all, productKeys.all, cartKeys.all, preorderKeys.all].map((queryKey) => qc.invalidateQueries({ queryKey })));
        onOpenChange(false);
        setValue('');
        setError(null);
        toast.show(t('location.delivering', { label: loc.label }));
        router.refresh();
      } catch (e) {
        setError(isApiError(e) ? (e.fieldErrors.zip ?? e.message) : t('location.notServed'));
      }
    },
    [qc, onOpenChange, toast, router, t]
  );

  const applyTyped = () => {
    const zip = value.trim().match(/\b\d{5}\b/)?.[0] ?? value.trim();
    if (!ZipSchema.safeParse(zip).success) {
      setError(t('location.invalidZip'));
      return;
    }
    void apply({ zip });
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

  const onSelect = (s: AddressSuggestionItem) => {
    setValue(`${s.label}, ${s.secondary}`);
    if (s.address.zip) void apply({ zip: s.address.zip.slice(0, 5) });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('location.title')} description={t('location.intro')}>
      <Button block onClick={locateMe} loading={geoBusy} icon={<Crosshair aria-hidden />}>
        {geoBusy ? t('location.locating') : t('location.useGeo')}
      </Button>
      <p className={styles.msg} aria-live="polite">
        {geoMsg}
      </p>
      <div className={styles.divider}>
        <span />
        {t('location.or')}
        <span />
      </div>
      <form
        className={styles.row}
        onSubmit={(e) => {
          e.preventDefault();
          applyTyped();
        }}
      >
        <div className={styles.grow}>
          <AddressAutocomplete
            label={t('location.addressLabel')}
            value={value}
            onChange={(v) => {
              setValue(v);
              setError(null);
            }}
            onSelect={onSelect}
            searchAddresses={bffAddressSearch}
            placeholder={t('location.placeholder')}
            error={error}
            attribution={t('location.attribution')}
          />
        </div>
        <Button type="submit" variant="neutral" className={styles.apply}>
          {t('location.apply')}
        </Button>
      </form>
    </Dialog>
  );
}
