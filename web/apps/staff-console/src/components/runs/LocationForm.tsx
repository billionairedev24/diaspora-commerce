'use client';

import { isApiError, type RunLocationInput, runLocationProblems, staffRunKeys,type StaffRunLocation } from '@afrotransact/api-client';
import { Button, Checkbox, Sheet, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useId, useState } from 'react';

import { controlClass } from '@/components/preorders/primitives';
import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';

import { type LocationForm, locationFormChanged, locationFormOf, locationInputOf, newLocationForm } from './location-model';
import { useRunsApi } from './shared';

type Errors = Partial<Record<string, string>>;

/** The words for a problem the shared checks report on a field. */
function useProblemText() {
  const t = useT();
  return (problem: string): string => {
    const key = (problem === 'link' || problem === 'zone' || problem === 'order' ? `st.runs.loc.problem.${problem}` : `st.runs.lc.problem.${problem}`) as MessageKey;
    return t(key);
  };
}

function LocationFormFields({ saved, onClose, onSaved }: { saved: StaffRunLocation | null; onClose: () => void; onSaved: (location: StaffRunLocation) => void }) {
  const t = useT();
  const api = useRunsApi();
  const qc = useQueryClient();
  const toast = useToast();
  const say = useProblemText();
  const formId = useId();
  const creating = saved === null;
  const [initial] = useState<LocationForm>(() => (saved ? locationFormOf(saved) : newLocationForm()));
  const [form, setForm] = useState<LocationForm>(initial);
  const [attempted, setAttempted] = useState(false);
  const [server, setServer] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const set = (patch: Partial<LocationForm>) => {
    setForm((f) => ({ ...f, ...patch }));
    setServer({});
    setFailure(null);
  };
  const input: RunLocationInput = locationInputOf(form, saved, creating);
  const errors: Errors = Object.fromEntries(Object.entries(runLocationProblems(input)).map(([field, problem]) => [field === 'polygonGeoJson' ? 'outline' : field, say(problem)]));
  if (form.areaKind === 'POLYGON' && !form.keepArea && !form.outline.trim()) errors.outline = say('zone');
  const shown: Errors = attempted ? { ...errors, ...server } : server;
  const changed = creating || locationFormChanged(form, initial);

  const save = useMutation({
    mutationFn: (body: RunLocationInput) => (saved ? api.updateLocation(saved.id, body) : api.createLocation(body)),
    onSuccess: (location) => {
      void qc.invalidateQueries({ queryKey: staffRunKeys.all });
      toast.show(t(creating ? 'st.runs.loc.toast.created' : 'st.runs.loc.toast.saved'));
      onSaved(location);
    },
    onError: (e) => {
      if (isApiError(e) && Object.keys(e.fieldErrors).length) {
        setServer(Object.fromEntries(Object.entries(e.fieldErrors).map(([k, v]) => [k === 'polygonGeoJson' || k === 'geoZoneId' ? 'outline' : k, v])));
      }
      setFailure(problemMessage(e));
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (save.isPending || !changed) return;
    setAttempted(true);
    if (Object.keys(errors).length > 0) return;
    save.mutate(input);
  };

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5" data-testid="location-form" aria-label={t(creating ? 'st.runs.loc.title.create' : 'st.runs.loc.title.edit')}>
      <p className="m-0 max-w-[70ch] text-sm text-muted">{t(creating ? 'st.runs.loc.help.create' : 'st.runs.loc.help.edit')}</p>

      <section className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
        <TextField label={t('st.runs.loc.form.name')} value={form.name} error={shown.name} autoComplete="off" onChange={(e) => set({ name: e.currentTarget.value })} />
        <TextField label={t('st.runs.loc.form.market')} value={form.market} error={shown.market} autoComplete="off" onChange={(e) => set({ market: e.currentTarget.value })} />
        <TextField label={t('st.runs.loc.form.state')} value={form.state} error={shown.state} autoComplete="off" onChange={(e) => set({ state: e.currentTarget.value })} />
        <TextField
          label={t('st.runs.loc.form.timeZone')}
          help={t('st.runs.loc.form.timeZoneHelp')}
          value={form.timeZone}
          error={shown.timeZone}
          autoComplete="off"
          onChange={(e) => set({ timeZone: e.currentTarget.value })}
        />
      </section>

      <fieldset className="m-0 flex min-w-0 flex-col gap-2 border-0 p-0">
        <legend className="mb-1 p-0 text-sm font-semibold">{t('st.runs.loc.form.area')}</legend>
        {(['RADIUS', 'POLYGON'] as const).map((kind) => (
          <label key={kind} className="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
            <input type="radio" name={`${formId}-area`} className="size-5 shrink-0 cursor-pointer" checked={form.areaKind === kind} onChange={() => set({ areaKind: kind })} />
            {t(`st.runs.loc.form.area.${kind}`)}
          </label>
        ))}
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-4">
          {form.areaKind === 'RADIUS' ? (
            <TextField label={t('st.runs.loc.form.radius')} inputMode="decimal" value={form.radiusMiles} error={shown.radiusMiles} onChange={(e) => set({ radiusMiles: e.currentTarget.value })} />
          ) : null}
          <TextField label={t('st.runs.loc.form.centerLat')} inputMode="decimal" value={form.centerLat} error={shown.centerLat} onChange={(e) => set({ centerLat: e.currentTarget.value })} />
          <TextField label={t('st.runs.loc.form.centerLng')} inputMode="decimal" value={form.centerLng} error={shown.centerLng} onChange={(e) => set({ centerLng: e.currentTarget.value })} />
        </div>
        <p className="m-0 text-xs text-muted">{t('st.runs.loc.form.centerHelp')}</p>
        {form.areaKind === 'POLYGON' ? (
          <div className="flex flex-col gap-2">
            {saved?.area?.kind === 'POLYGON' && saved.area.geoZoneId ? (
              <Checkbox label={t('st.runs.loc.form.keepArea')} checked={form.keepArea} onChange={(e) => set({ keepArea: e.currentTarget.checked })} />
            ) : null}
            {!form.keepArea ? (
              <div className="flex flex-col gap-1">
                <label htmlFor={`${formId}-outline`} className="text-sm font-semibold">
                  {t('st.runs.loc.form.outline')}
                </label>
                <textarea
                  id={`${formId}-outline`}
                  value={form.outline}
                  rows={5}
                  spellCheck={false}
                  aria-invalid={shown.outline ? true : undefined}
                  aria-describedby={`${formId}-outline-help`}
                  onChange={(e) => set({ outline: e.currentTarget.value })}
                  className={`${controlClass} h-auto py-2 font-mono`}
                />
                <span id={`${formId}-outline-help`} className={`text-xs font-normal ${shown.outline ? 'text-danger' : 'text-muted'}`}>
                  {shown.outline ?? t('st.runs.loc.form.outlineHelp')}
                </span>
              </div>
            ) : null}
          </div>
        ) : null}
      </fieldset>

      <section aria-label={t('st.runs.loc.form.defaults')} className="flex flex-col gap-3">
        <h3 className="m-0 text-sm font-semibold">{t('st.runs.loc.form.defaults')}</h3>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-4">
          <TextField label={t('st.runs.loc.form.fee')} inputMode="decimal" value={form.fee} error={shown.defaultFeeMinor ?? shown.fee} onChange={(e) => set({ fee: e.currentTarget.value })} />
          <TextField label={t('st.runs.loc.form.minOrders')} inputMode="numeric" value={form.minOrders} error={shown.defaultMinOrders} onChange={(e) => set({ minOrders: e.currentTarget.value })} />
          <TextField label={t('st.runs.loc.form.capacity')} inputMode="numeric" value={form.capacityOrders} error={shown.defaultCapacityOrders} onChange={(e) => set({ capacityOrders: e.currentTarget.value })} />
        </div>
        <TextField
          label={t('st.runs.loc.form.link')}
          help={t('st.runs.loc.form.linkHelp')}
          type="url"
          inputMode="url"
          value={form.link}
          error={shown.whatsappInviteUrl}
          autoComplete="off"
          onChange={(e) => set({ link: e.currentTarget.value })}
        />
        {creating ? <Checkbox label={t('st.runs.loc.form.active')} checked={form.active} onChange={(e) => set({ active: e.currentTarget.checked })} /> : null}
      </section>

      {shown.slug ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {shown.slug}
        </p>
      ) : null}
      {failure ? (
        <p role="alert" className="m-0 text-sm text-danger" data-testid="location-form-failure">
          {failure}
        </p>
      ) : null}
      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <Button variant="neutral" onClick={onClose}>
          {t('st.runs.loc.form.discard')}
        </Button>
        <Button type="submit" loading={save.isPending} disabled={!changed}>
          {t(creating ? 'st.runs.loc.form.save.create' : 'st.runs.loc.form.save.edit')}
        </Button>
      </div>
    </form>
  );
}

/**
 * The right sheet that adds a delivery location (a town) or changes one: name, market, time zone, where it delivers (a
 * radius around the town's centre, or a drawn area), what new runs start from (fee, minimum, capacity) and the community
 * (WhatsApp) link. An edit never switches the town on or off; that is its own audited action.
 */
export function LocationFormSheet({ open, location, onClose, onSaved }: { open: boolean; location: StaffRunLocation | null; onClose: () => void; onSaved: (location: StaffRunLocation) => void }) {
  const t = useT();
  return (
    <Sheet open={open} onOpenChange={(o) => (o ? undefined : onClose())} title={t(location ? 'st.runs.loc.title.edit' : 'st.runs.loc.title.create')} closeLabel={t('st.runs.reason.close')}>
      <LocationFormFields key={location?.id ?? 'new'} saved={location} onClose={onClose} onSaved={onSaved} />
    </Sheet>
  );
}
