'use client';

import { inventoryHubQueries, type RetailItem, type Supplier } from '@afrotransact/api-client';
import { Button, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { useHub, useHubMutation } from '@/components/inventory/shared';
import { idempotencyKey, toMinor } from '@/lib/format';

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

/** New supplier (starts Onboarding; approve it to send purchase orders). */
export function SupplierForm({ onDone }: { onDone: (s: Supplier) => void }) {
  const api = useHub();
  const [f, setF] = useState({ code: 'SUP-', name: '', country: '', city: '', currency: '', paymentTerms: 'Net 30', leadTimeDays: '30' });
  const [shown, setShown] = useState(false);
  const errors: Record<string, string> = {};
  if (!/^SUP-[A-Z0-9]{2,12}$/.test(f.code.toUpperCase())) errors.code = 'SUP- and 2 to 12 letters or digits';
  if (!f.name.trim()) errors.name = 'Required';
  if (!/^[A-Za-z]{2}$/.test(f.country)) errors.country = 'Two-letter country code';
  if (!/^[A-Za-z]{3}$/.test(f.currency)) errors.currency = 'Three-letter currency code';
  if (!/^\d+$/.test(f.leadTimeDays)) errors.leadTimeDays = 'Days';
  const create = useHubMutation(
    () => api.retail.createSupplier({ code: f.code.toUpperCase(), name: f.name.trim(), country: f.country.toUpperCase(), city: f.city.trim() || undefined, currency: f.currency.toUpperCase(), paymentTerms: f.paymentTerms.trim() || undefined, leadTimeDays: Number(f.leadTimeDays) }),
    (s) => `${s.name} added (onboarding).`,
    onDone
  );
  const field = (k: keyof typeof f, label: string) => <TextField label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} error={shown ? errors[k] : undefined} />;
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
      {field('code', 'Code')}
      {field('name', 'Name')}
      {field('country', 'Country (ISO)')}
      {field('city', 'City')}
      {field('currency', 'Currency')}
      {field('paymentTerms', 'Payment terms')}
      {field('leadTimeDays', 'Lead time (days)')}
      <Button type="submit" className="col-span-full" loading={create.isPending}>
        Add supplier
      </Button>
    </form>
  );
}
