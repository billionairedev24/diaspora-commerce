'use client';

import { inventoryHubQueries, type RetailItem, type Supplier, type SupplierInput } from '@afrotransact/api-client';
import { Button, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { toneOf, useHub, useHubMutation } from '@/components/inventory/shared';
import { StatusPill } from '@/components/ui/StatusPill';
import { countryOptions, currencyOptions } from '@/lib/iso';
import { humanize, idempotencyKey, toMinor } from '@/lib/format';

type Storage = 'AMBIENT' | 'CHILLED' | 'FROZEN' | 'HAZMAT';

export interface ItemDraft {
  name: string;
  category: string;
  supplierId: string;
  supplierSku: string;
  casePack: string;
  unitCost: string;
  hsCode: string;
  duty: string;
  shelfLifeDays: string;
  storage: string;
  reorder: string;
  packSize: string;
  price: string;
  atin: string;
}

/** Field errors of the new-item form (the retail service validates again). */
export function itemErrors(f: ItemDraft): Record<string, string> {
  const e: Record<string, string> = {};
  if (f.name.trim().length < 3) e.name = 'At least 3 characters';
  if (!f.supplierId) e.supplierId = 'Pick the supplier';
  if (!f.supplierSku.trim()) e.supplierSku = 'The supplier’s SKU';
  if (!/^\d+$/.test(f.casePack) || Number(f.casePack) < 1) e.casePack = 'Units per case';
  if (Number.isNaN(toMinor(f.unitCost)) || toMinor(f.unitCost) <= 0) e.unitCost = 'Cost in the supplier’s currency';
  if (!f.hsCode.trim()) e.hsCode = 'HTS code (customs)';
  if (!/^\d+$/.test(f.shelfLifeDays) || Number(f.shelfLifeDays) < 1) e.shelfLifeDays = 'Days from manufacture';
  if (Number.isNaN(toMinor(f.price)) || toMinor(f.price) <= 0) e.price = 'Retail price in USD';
  if (f.atin && !/^AT[0-9A-Z]{8}$/.test(f.atin.trim().toUpperCase())) e.atin = 'AT + 8 letters or digits';
  return e;
}

/** New AfroTransact product (item master): the SKU and GTIN barcode are issued; the listing starts as Draft. */
export function ItemForm({ onDone }: { onDone: (i: RetailItem) => void }) {
  const api = useHub();
  const suppliers = useQuery(inventoryHubQueries.suppliers(api));
  const [f, setF] = useState<ItemDraft>({ name: '', category: 'Groceries', supplierId: '', supplierSku: '', casePack: '12', unitCost: '', hsCode: '', duty: '0', shelfLifeDays: '365', storage: 'AMBIENT', reorder: '60', packSize: '', price: '', atin: '' });
  const [shown, setShown] = useState(false);
  const [key] = useState(idempotencyKey);
  const errors = itemErrors(f);
  const supplier: Supplier | undefined = suppliers.data?.items.find((s) => s.id === f.supplierId);
  const create = useHubMutation(
    () =>
      api.retail.createItem(
        {
          name: f.name.trim(),
          category: f.category.trim() || undefined,
          atin: f.atin.trim().toUpperCase() || undefined,
          primarySupplierId: f.supplierId,
          supplierItems: [{ supplierId: f.supplierId, supplierSku: f.supplierSku.trim(), casePack: Number(f.casePack), minOrderQty: Number(f.casePack), unitCostMinor: toMinor(f.unitCost), currency: supplier?.currency ?? 'USD' }],
          hsCode: f.hsCode.trim(),
          dutyRatePercent: Number(f.duty || 0),
          shelfLifeDays: Number(f.shelfLifeDays),
          storage: f.storage as Storage,
          reorderPoint: Number(f.reorder || 0),
          packSize: f.packSize.trim() || undefined,
          casePack: Number(f.casePack),
          standardCostMinor: 0,
          priceMinor: toMinor(f.price)
        },
        key
      ),
    (i) => `${i.name}: SKU ${i.sku}, GTIN ${i.gtin}.`,
    onDone
  );
  const field = (k: keyof ItemDraft, label: string, extra: Record<string, unknown> = {}) => (
    <TextField label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} error={shown ? errors[k] : undefined} {...extra} />
  );
  return (
    <form
      className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-2"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setShown(true);
        if (!Object.keys(errors).length) create.mutate(undefined);
      }}
    >
      {field('name', 'Product name', { className: 'col-span-full', placeholder: 'AfroTransact Brand Egusi Seeds, 1 lb' })}
      {field('category', 'Category')}
      <Select label="Supplier" value={f.supplierId} placeholder="Choose…" onChange={(e) => setF({ ...f, supplierId: e.target.value })} options={(suppliers.data?.items ?? []).map((s) => ({ value: s.id, label: `${s.name} (${s.currency})` }))} error={shown ? errors.supplierId : undefined} />
      {field('supplierSku', 'Supplier SKU')}
      {field('casePack', 'Units per case', { inputMode: 'numeric' })}
      {field('unitCost', `Unit cost (${supplier?.currency ?? 'supplier currency'})`, { inputMode: 'decimal' })}
      {field('hsCode', 'HTS code', { placeholder: '1207.70.0000' })}
      {field('duty', 'Duty rate %', { inputMode: 'decimal' })}
      {field('shelfLifeDays', 'Shelf life (days)', { inputMode: 'numeric' })}
      <Select label="Storage" value={f.storage} onChange={(e) => setF({ ...f, storage: e.target.value })} options={['AMBIENT', 'CHILLED', 'FROZEN', 'HAZMAT'].map((v) => ({ value: v, label: v.charAt(0) + v.slice(1).toLowerCase() }))} />
      {field('reorder', 'Reorder point (units)', { inputMode: 'numeric' })}
      {field('packSize', 'Pack size', { placeholder: '8 oz' })}
      {field('price', 'Retail price (USD)', { inputMode: 'decimal' })}
      {field('atin', 'Catalog ATIN (optional)', { help: 'Link it now or later to list it on the storefront' })}
      <Button type="submit" className="col-span-full" loading={create.isPending}>
        Create item
      </Button>
    </form>
  );
}

const PAYMENT_TERMS = ['Net 30', 'Net 45', 'Net 60', 'Prepaid', '30% deposit, balance Net 30', '50% deposit, balance on shipment'];

/** The supplier's next statuses and what each is called to the person deciding: one clear action each. */
const STATUS_ACTIONS: Record<string, { to: 'APPROVED' | 'ON_WATCH' | 'INACTIVE'; label: string; reason: boolean; tone: 'primary' | 'neutral' | 'danger' }[]> = {
  ONBOARDING: [{ to: 'APPROVED', label: 'Approve supplier', reason: false, tone: 'primary' }],
  APPROVED: [
    { to: 'ON_WATCH', label: 'Put on watch', reason: true, tone: 'neutral' },
    { to: 'INACTIVE', label: 'Stop doing business', reason: true, tone: 'danger' }
  ],
  ON_WATCH: [
    { to: 'APPROVED', label: 'Back to approved', reason: false, tone: 'primary' },
    { to: 'INACTIVE', label: 'Stop doing business', reason: true, tone: 'danger' }
  ],
  INACTIVE: [{ to: 'APPROVED', label: 'Resume business', reason: false, tone: 'primary' }]
};

/** Status of a supplier and the one-click changes: stopping or watching asks why; the history of orders is never touched. */
function SupplierStatus({ supplier, canApprove, onDone }: { supplier: Supplier; canApprove: boolean; onDone: (s: Supplier) => void }) {
  const api = useHub();
  const [pending, setPending] = useState<(typeof STATUS_ACTIONS)[string][number] | null>(null);
  const [reason, setReason] = useState('');
  const change = useHubMutation(
    (to: 'APPROVED' | 'ON_WATCH' | 'INACTIVE') => api.retail.setSupplierStatus(supplier.id, to, reason.trim() || (to === 'APPROVED' ? 'Vetted' : undefined)),
    (s) => `${s.name}: ${humanize(s.status).toLowerCase()}.`,
    (s) => {
      setPending(null);
      setReason('');
      onDone(s);
    }
  );
  const actions = STATUS_ACTIONS[supplier.status] ?? [];
  return (
    <section aria-label="Supplier status" className="flex flex-col gap-2 border-t border-border pt-3">
      <span className="flex flex-wrap items-center gap-2 text-sm">
        <strong>Status</strong>
        <StatusPill tone={toneOf(supplier.status)}>{humanize(supplier.status)}</StatusPill>
        {supplier.statusReason ? <span className="text-muted">{supplier.statusReason}</span> : null}
      </span>
      <p className="m-0 text-xs text-muted">
        {supplier.status === 'INACTIVE' || supplier.status === 'ONBOARDING'
          ? 'No new purchase orders can be raised for this supplier.'
          : 'New purchase orders can be raised. Stopping business never changes existing orders, receipts or history.'}
      </p>
      {canApprove && actions.length ? (
        pending ? (
          <form
            className="flex flex-col gap-2"
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              if (!pending.reason || reason.trim()) change.mutate(pending.to);
            }}
          >
            {pending.reason ? <TextField label={`Why? (${pending.label.toLowerCase()})`} value={reason} onChange={(e) => setReason(e.target.value)} /> : null}
            <span className="flex gap-2">
              <Button type="submit" size="sm" variant={pending.tone === 'danger' ? 'dark' : 'primary'} loading={change.isPending} disabled={pending.reason && !reason.trim()}>
                {pending.label}
              </Button>
              <Button type="button" size="sm" variant="neutral" onClick={() => setPending(null)}>
                Cancel
              </Button>
            </span>
          </form>
        ) : (
          <span className="flex flex-wrap gap-2">
            {actions.map((a) => (
              <Button key={a.to + a.label} size="sm" variant={a.tone === 'primary' ? 'primary' : 'neutral'} onClick={() => (a.reason ? setPending(a) : change.mutate(a.to))}>
                {a.label}
              </Button>
            ))}
          </span>
        )
      ) : null}
    </section>
  );
}

/** Add a supplier, or edit one: a supplier is a record we buy from (it never signs in). New ones start in Onboarding until approved. */
export function SupplierForm({ supplier, canApprove = false, onDone }: { supplier?: Supplier; canApprove?: boolean; onDone: (s: Supplier) => void }) {
  const api = useHub();
  const countries = useMemo(() => countryOptions(), []);
  const currencies = useMemo(() => currencyOptions(), []);
  const first = supplier?.contacts[0];
  const [f, setF] = useState({
    code: supplier?.code ?? 'SUP-', name: supplier?.name ?? '', country: supplier?.country ?? '', city: supplier?.city ?? '', currency: supplier?.currency ?? '',
    paymentTerms: supplier?.paymentTerms ?? 'Net 30', leadTimeDays: String(supplier?.leadTimeDays ?? 30),
    contactName: first?.name ?? '', contactEmail: first?.email ?? '', contactPhone: first?.phone ?? ''
  });
  const [shown, setShown] = useState(false);
  const errors: Record<string, string> = {};
  if (!supplier && !/^SUP-[A-Z0-9]{2,12}$/.test(f.code.toUpperCase())) errors.code = 'SUP- and 2 to 12 letters or digits';
  if (!f.name.trim()) errors.name = 'Required';
  if (!f.country) errors.country = 'Choose the country';
  if (!f.currency) errors.currency = 'Choose the currency';
  if (!/^\d+$/.test(f.leadTimeDays) || Number(f.leadTimeDays) > 365) errors.leadTimeDays = '0 to 365 days';
  if (f.contactEmail.trim() && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.contactEmail.trim())) errors.contactEmail = 'An email address';
  const body = (): SupplierInput => {
    const primary = f.contactName.trim() ? [{ name: f.contactName.trim(), email: f.contactEmail.trim() || undefined, phone: f.contactPhone.trim() || undefined, role: first?.role ?? undefined }] : [];
    // Other contacts already on file are kept as they are.
    const others = (supplier?.contacts ?? []).slice(1).map((c) => ({ name: c.name, role: c.role ?? undefined, email: c.email ?? undefined, phone: c.phone ?? undefined }));
    return {
      code: (supplier?.code ?? f.code).toUpperCase(), name: f.name.trim(), country: f.country, city: f.city.trim() || undefined, currency: f.currency,
      paymentTerms: f.paymentTerms.trim() || undefined, leadTimeDays: Number(f.leadTimeDays), contacts: [...primary, ...(primary.length ? others : [])]
    };
  };
  const save = useHubMutation(
    () => (supplier ? api.retail.updateSupplier(supplier.id, body(), supplier.version) : api.retail.createSupplier(body())),
    (s) => (supplier ? `${s.name} saved.` : `${s.name} added: approve it to raise purchase orders.`),
    onDone
  );
  const text = (k: keyof typeof f, label: string, extra: { list?: string; inputMode?: 'numeric' | 'email' | 'tel'; className?: string } = {}) => (
    <TextField label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} error={shown ? errors[k] : undefined} {...extra} />
  );
  return (
    <div className="flex flex-col gap-3">
      <form
        className="grid grid-cols-2 gap-x-3 gap-y-1"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          setShown(true);
          if (!Object.keys(errors).length) save.mutate(undefined);
        }}
      >
        <div className="col-span-2">{text('name', 'Supplier name')}</div>
        {supplier ? (
          <p className="col-span-2 m-0 text-xs text-muted">
            Code <strong className="font-mono">{supplier.code}</strong> · the country cannot be changed (add a new supplier instead).
          </p>
        ) : (
          text('code', 'Code')
        )}
        <Select label="Country" value={f.country} placeholder="Choose…" disabled={!!supplier} onChange={(e) => setF({ ...f, country: e.target.value })} options={countries} error={shown ? errors.country : undefined} />
        {text('city', 'City')}
        <Select label="Currency we pay in" value={f.currency} placeholder="Choose…" onChange={(e) => setF({ ...f, currency: e.target.value })} options={currencies} error={shown ? errors.currency : undefined} />
        <datalist id="supplier-terms">
          {PAYMENT_TERMS.map((t) => (
            <option key={t} value={t} />
          ))}
        </datalist>
        {text('paymentTerms', 'Payment terms', { list: 'supplier-terms' })}
        {text('leadTimeDays', 'Usual lead time (days)', { inputMode: 'numeric' })}
        <strong className="col-span-2 mt-1 text-sm">Contact (optional)</strong>
        <div className="col-span-2">{text('contactName', 'Name')}</div>
        {text('contactEmail', 'Email', { inputMode: 'email' })}
        {text('contactPhone', 'Phone', { inputMode: 'tel' })}
        <Button type="submit" className="col-span-2 mt-2" loading={save.isPending}>
          {supplier ? 'Save changes' : 'Add supplier'}
        </Button>
      </form>
      {supplier ? <SupplierStatus supplier={supplier} canApprove={canApprove} onDone={onDone} /> : null}
    </div>
  );
}
