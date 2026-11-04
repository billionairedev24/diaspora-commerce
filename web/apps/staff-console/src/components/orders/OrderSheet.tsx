'use client';

import { type DetailField, type DetailSection, type RecordDetail, staffKeys, staffQueries } from '@afrotransact/api-client';
import { IconButton, Menu, MenuItem, MenuSeparator, Sheet, useToast } from '@afrotransact/ui';
import { ArrowSquareOut, Copy, DotsThree, Headset, Truck } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { type ReactNode } from 'react';

import { fieldText } from '@/components/resources/cells';
import { CopyId, Details } from '@/components/resources/RecordSheet';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, money } from '@/lib/format';

import { ShipmentsSection, shipmentStatusLabel } from './ShipmentsSection';

/** Group headings and field labels the BFF sends in English, in the staff member's language (the BFF's own words when unknown). */
const GROUPS: Record<string, MessageKey> = {
  Customer: 'st.order.group.customer',
  Payment: 'st.order.group.payment',
  Origin: 'st.order.group.origin',
  'Notes and tags': 'st.order.group.notes',
  'Technical details': 'st.order.group.technical'
};
const FIELDS: Record<string, MessageKey> = {
  Name: 'st.order.field.name',
  'Ship to': 'st.order.field.shipTo',
  Phone: 'st.order.field.phone',
  'Delivery instructions': 'st.order.field.instructions',
  Status: 'st.order.field.status',
  Items: 'st.order.field.items',
  Shipping: 'st.order.field.shipping',
  Tax: 'st.order.field.tax',
  Discount: 'st.order.field.discount',
  Total: 'st.order.field.total',
  'Placed via': 'st.order.field.placedVia',
  Source: 'st.order.field.source',
  'Campaign slot': 'st.order.field.campaign',
  'Pre-order window': 'st.order.field.preorder',
  'Cancellation reason': 'st.order.field.cancelReason',
  Tags: 'st.order.field.tags',
  'Order ID': 'st.order.field.orderId',
  'Payment ID': 'st.order.field.paymentId',
  'Customer ID': 'st.order.field.customerId'
};
const word = (t: Translator<MessageKey>, map: Record<string, MessageKey>, english: string): string => {
  const key = map[english];
  return key ? t(key) : english;
};

const factOf = (sections: DetailSection[], group: string, label: string): DetailField | undefined => sections.find((s) => s.title === group)?.fields.find((f) => f.label === label);

/**
 * The detail sheet of an order for support and operations: the header (status, customer, placed, total), the shipments
 * with a status control each, then the facts grouped as flat definition lists. Internal ids never lead: they sit in a
 * collapsed "Technical details" with copy buttons.
 */
export function OrderSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const q = useQuery({ ...staffQueries.record(api, 'orders', id ?? ''), enabled: id !== null });
  const record = q.data;

  /** The table pages and the console's queue counts after a change. */
  const refreshLists = () => {
    void qc.invalidateQueries({ queryKey: staffKeys.resource('orders'), predicate: (query) => query.queryKey[3] === 'page' });
    void qc.invalidateQueries({ queryKey: staffKeys.session() });
  };
  const updated = (r: RecordDetail) => {
    qc.setQueryData(staffKeys.record('orders', r.id), r);
    refreshLists();
  };
  const stale = () => {
    void qc.invalidateQueries({ queryKey: staffKeys.record('orders', id ?? '') });
    refreshLists();
  };

  const copyNumber = async () => {
    try {
      await navigator.clipboard.writeText(record?.id ?? '');
      toast.show(t('st.order.numberCopied'));
    } catch {
      toast.show(t('st.order.copyFailed'), 'error');
    }
  };

  const sections = record?.sections ?? [];
  const placed = factOf(sections, 'Order', 'Placed')?.value;
  const customer = factOf(sections, 'Customer', 'Name')?.value;
  const total = factOf(sections, 'Payment', 'Total')?.value;
  const facts = sections.filter((s) => s.title !== 'Order' && s.title !== 'Technical details' && !s.title.startsWith('Proof of delivery'));
  const proof = sections.filter((s) => s.title.startsWith('Proof of delivery'));
  const technical = sections.find((s) => s.title === 'Technical details');
  const links = (record?.links ?? []).map((l) => ({ ...l, label: l.href.startsWith('/support') ? t('st.order.openSupport') : l.href.startsWith('/dispatch') ? t('st.order.assignDriver') : l.label }));

  const title = t('st.order.title', { number: record?.id ?? id ?? '' });
  const header: ReactNode = (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      <span className="min-w-0 flex-1 truncate text-base font-bold">{title}</span>
      {record ? (
        <Menu
          label={t('st.order.actions')}
          trigger={
            <IconButton label={t('st.order.actions')} onDark>
              <DotsThree size={22} weight="bold" aria-hidden />
            </IconButton>
          }
          width={260}
        >
          <MenuItem icon={<Copy size={16} aria-hidden />} onSelect={() => void copyNumber()}>
            {t('st.order.copyNumber')}
          </MenuItem>
          {links.length ? <MenuSeparator /> : null}
          {links.map((l) =>
            l.external ? (
              <MenuItem key={l.href} href={l.href} external icon={<ArrowSquareOut size={16} aria-hidden />}>
                {l.label}
              </MenuItem>
            ) : (
              <MenuItem key={l.href} icon={l.href.startsWith('/dispatch') ? <Truck size={16} aria-hidden /> : <Headset size={16} aria-hidden />} onSelect={() => router.push(l.href)}>
                {l.label}
              </MenuItem>
            )
          )}
        </Menu>
      ) : null}
    </div>
  );

  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={title} description={record?.subtitle} header={header} closeLabel={t('st.order.confirm.close')}>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {record ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              {record.status ? <StatusPill tone={record.tone}>{shipmentStatusLabel(t, record.status)}</StatusPill> : null}
              {total ? <strong className="ml-auto text-lg tabular-nums">{fieldText('money', total)}</strong> : null}
            </div>
            <p className="m-0 text-sm text-muted">{[customer, placed ? t('st.order.placedOn', { date: dateTime(placed) }) : ''].filter(Boolean).join(' · ')}</p>
          </div>
          <ShipmentsSection order={record.id} shipments={record.shipments ?? []} onUpdated={updated} onStale={stale} />
          {facts.map((s) => (
            <section key={s.title} aria-label={word(t, GROUPS, s.title)} className="flex flex-col gap-2">
              <h3 className="m-0 text-sm font-bold">{word(t, GROUPS, s.title)}</h3>
              <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1.5 text-sm">
                {s.fields.map((f) => (
                  <div key={f.label} className="contents">
                    <dt className="text-muted">{word(t, FIELDS, f.label)}</dt>
                    <dd className="m-0 [overflow-wrap:anywhere]">{f.kind === 'money' ? money(Number(f.value)) : fieldText(f.kind, f.value)}</dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
          <Details record={{ ...record, sections: proof }} />
          {technical ? (
            <details className="text-sm" data-testid="technical-details">
              <summary className="cursor-pointer font-bold">{word(t, GROUPS, technical.title)}</summary>
              <dl className="m-0 mt-2 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1.5">
                {technical.fields.map((f) => (
                  <div key={f.label} className="contents">
                    <dt className="text-muted">{word(t, FIELDS, f.label)}</dt>
                    <dd className="m-0 [overflow-wrap:anywhere]">
                      <span className="font-mono text-xs">{f.value}</span>
                      <CopyId value={f.value} label={word(t, FIELDS, f.label)} />
                    </dd>
                  </div>
                ))}
              </dl>
            </details>
          ) : null}
        </div>
      ) : null}
    </Sheet>
  );
}
