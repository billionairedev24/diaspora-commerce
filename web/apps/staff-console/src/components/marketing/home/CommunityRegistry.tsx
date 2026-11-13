'use client';

import { MAX_REASON_LENGTH, type StaffCommunity, staffHomeKeys, staffHomeQueries, type StaffLocale, type StaffLocaleLabels } from '@afrotransact/api-client/staff-home';
import { Button, Dialog, DialogActions, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { MIN_REASON, ReasonField, reasonRule } from '@/components/ui/ReasonField';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

const LOCALES: readonly StaffLocale[] = ['en', 'fr', 'es', 'ar', 'yo'];
const keyOf = (name: string) => name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

interface Draft {
  key: string;
  isNew: boolean;
  labels: StaffLocaleLabels;
  aliases: string;
  imageUrl: string;
}

/**
 * The community registry (S420): one canonical entry per community, with its five-language labels, the spellings that mean it
 * (`nigerian`, `Nigerian`) and an optional editorial photo. Without an entry a community uses its value as it is.
 * A community is shown to shoppers only while it has enough products and sellers (the Shop by community rule above).
 */
export function CommunityRegistry({ canEdit }: { canEdit: boolean }) {
  const t = useT();
  const api = staffApi().home;
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery(staffHomeQueries.communities(api));
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState('');
  const [removing, setRemoving] = useState<StaffCommunity | null>(null);
  const done = () => {
    void qc.invalidateQueries({ queryKey: staffHomeKeys.communities() });
    setDraft(null);
    setRemoving(null);
    setReason('');
  };
  const save = useMutation({
    mutationFn: (d: Draft) =>
      api.saveCommunity(d.key, { labels: d.labels, aliases: d.aliases.split(',').map((a) => a.trim()).filter(Boolean), imageUrl: d.imageUrl.trim() || null }, reason),
    onSuccess: () => {
      toast.show(t('st.home.communities.saved'));
      done();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const remove = useMutation({
    mutationFn: (c: StaffCommunity) => api.deleteCommunity(c.key, reason),
    onSuccess: () => {
      toast.show(t('st.home.communities.removed'));
      done();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const rule = reasonRule(t, reason, MIN_REASON);
  const open = (c?: StaffCommunity, value?: string) =>
    setDraft(c ? { key: c.key, isNew: false, labels: { ...c.labels }, aliases: c.aliases.join(', '), imageUrl: c.imageUrl ?? '' } : { key: keyOf(value ?? ''), isNew: true, labels: { en: value ?? '' }, aliases: '', imageUrl: '' });
  const validDraft = !!draft && draft.key !== '' && draft.labels.en.trim() !== '';

  return (
    <Card
      title={t('st.home.communities.title')}
      actions={
        canEdit ? (
          <Button type="button" size="sm" variant="neutral" onClick={() => open()}>
            {t('st.home.communities.add')}
          </Button>
        ) : null
      }
    >
      <p className="m-0 text-sm text-muted">{t('st.home.communities.help')}</p>
      {list.isPending ? (
        <Spinner label={t('st.home.loading')} />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : (
        <>
          {list.data.registered.length === 0 ? (
            <p className="m-0 text-sm text-muted">{t('st.home.communities.none')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0" data-testid="community-registry">
              {list.data.registered.map((c) => (
                <li key={c.key} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-border pb-2 text-sm">
                  <strong>{c.labels.en}</strong>
                  <StatusPill tone={c.visible ? 'good' : 'info'}>{c.visible ? t('st.home.communities.shown') : t('st.home.communities.hidden')}</StatusPill>
                  <span className="text-muted">{t('st.home.communities.counts', { products: c.eligibleProducts ?? 0, sellers: c.sellers ?? 0 })}</span>
                  {c.aliases.length > 0 ? <span className="text-muted">{t('st.home.communities.aliases', { list: c.aliases.join(', ') })}</span> : null}
                  {c.imageUrl && c.imageAccepted === false ? <StatusPill tone="warn">{t('st.home.communities.imageRejected')}</StatusPill> : null}
                  <span className="flex-1" />
                  {canEdit ? (
                    <>
                      <Button type="button" size="sm" variant="neutral" onClick={() => open(c)} aria-label={t('st.home.communities.edit', { name: c.labels.en })}>
                        {t('st.home.communities.editButton')}
                      </Button>
                      <Button type="button" size="sm" variant="neutral" onClick={() => setRemoving(c)} aria-label={t('st.home.communities.remove', { name: c.labels.en })}>
                        {t('st.home.communities.removeButton')}
                      </Button>
                    </>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {list.data.unregistered.length > 0 ? (
            <div className="flex flex-col gap-1">
              <strong className="text-sm">{t('st.home.communities.unregistered')}</strong>
              <ul className="m-0 flex list-none flex-wrap gap-2 p-0 text-sm">
                {list.data.unregistered.map((u) => (
                  <li key={u.value} className="flex items-center gap-2">
                    <span>{t('st.home.communities.value', { value: u.value, n: u.eligibleProducts })}</span>
                    {canEdit ? (
                      <Button type="button" size="sm" variant="neutral" onClick={() => open(undefined, u.value)} aria-label={t('st.home.communities.register', { name: u.value })}>
                        {t('st.home.communities.registerButton')}
                      </Button>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      )}

      <Dialog open={draft !== null} onOpenChange={(o) => !o && setDraft(null)} title={draft?.isNew ? t('st.home.communities.addTitle') : t('st.home.communities.editTitle')} width={560} closeLabel={t('st.home.close')}>
        {draft ? (
          <div className="flex flex-col gap-3">
            {LOCALES.map((l) => (
              <TextField
                key={l}
                label={t(`st.home.locale.${l}` as MessageKey)}
                lang={l}
                dir={l === 'ar' ? 'rtl' : 'ltr'}
                maxLength={80}
                value={draft.labels[l] ?? ''}
                onChange={(e) => {
                  const v = e.currentTarget.value;
                  setDraft((d) => (d ? { ...d, labels: { ...d.labels, [l]: v }, key: d.isNew && l === 'en' ? keyOf(v) : d.key } : d));
                }}
              />
            ))}
            <TextField label={t('st.home.communities.aliasesLabel')} help={t('st.home.communities.aliasesHelp')} value={draft.aliases} onChange={(e) => setDraft({ ...draft, aliases: e.currentTarget.value })} />
            <TextField label={t('st.home.communities.imageLabel')} help={t('st.home.communities.imageHelp')} type="url" inputMode="url" value={draft.imageUrl} onChange={(e) => setDraft({ ...draft, imageUrl: e.currentTarget.value })} />
            <ReasonField label={t('st.home.save.reason')} value={reason} onChange={setReason} single min={MIN_REASON} max={MAX_REASON_LENGTH} />
            <DialogActions>
              <Button type="button" variant="neutral" onClick={() => setDraft(null)}>
                {t('st.home.cancel')}
              </Button>
              <Button type="button" disabled={!validDraft || !rule.ok || save.isPending} onClick={() => save.mutate(draft)}>
                {t('st.home.communities.save')}
              </Button>
            </DialogActions>
          </div>
        ) : null}
      </Dialog>
      <Dialog open={removing !== null} onOpenChange={(o) => !o && setRemoving(null)} title={t('st.home.communities.removeTitle', { name: removing?.labels.en ?? '' })} description={t('st.home.communities.removeHelp')} closeLabel={t('st.home.close')}>
        <ReasonField label={t('st.home.save.reason')} value={reason} onChange={setReason} single min={MIN_REASON} max={MAX_REASON_LENGTH} />
        <DialogActions>
          <Button type="button" variant="neutral" onClick={() => setRemoving(null)}>
            {t('st.home.cancel')}
          </Button>
          <Button type="button" disabled={!rule.ok || remove.isPending} onClick={() => removing && remove.mutate(removing)}>
            {t('st.home.communities.removeConfirm')}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
