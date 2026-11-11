/* Settings > Flat delivery fee in the mock staff BFF (listing rules D16): the one staff-edited record that sets the shopper's
   delivery charge. Mirrors internal/app/staff_resources_shipping.go of the Go BFF over orders' shipping settings. An edit
   needs a reason and also updates the storefront mock, so the cart and checkout follow what staff set. */
import { type FieldValue, type FormField } from '../../schemas/staff';
import { getShippingSettings, setShippingSettings } from '../shipping-settings';
import { type MockRecord, type Scalar } from './db';
import { type MockResource } from './resources';

const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const dollars = (minor: number) => `$${(minor / 100).toFixed(2)}`;

function summaryFields() {
  const s = getShippingSettings();
  return {
    name: 'Flat delivery fee',
    flatFeeText: dollars(s.flatFeeMinor),
    freeAboveText: s.freeAboveMinor === null ? 'Never free' : `Free from ${dollars(s.freeAboveMinor)}`,
    carrierText: s.sellerCarrierShippingEnabled ? 'On' : 'Off',
    version: s.version
  };
}

export function seedShippingSettings(now: Date): MockRecord[] {
  return [
    {
      id: 'shipping',
      fields: { ...summaryFields(), updatedByName: 'System', updatedAt: now.toISOString() },
      activity: [{ at: now.toISOString(), actor: 'System', text: 'Changed: Launch default (listing rules D16)' }]
    }
  ];
}

export const shippingSettingsDef: MockResource = {
  title: 'Flat delivery fee',
  columns: [
    { key: 'name', label: 'Setting', kind: 'strong', width: 22 },
    { key: 'flatFeeText', label: 'Delivery fee', kind: 'text', width: 16 },
    { key: 'freeAboveText', label: 'Free delivery', kind: 'text', width: 20 },
    { key: 'carrierText', label: 'Seller carrier shipping', kind: 'text', width: 18 },
    { key: 'updatedByName', label: 'Last changed by', kind: 'text', width: 24 }
  ],
  facets: [],
  exportable: false,
  titleOf: () => 'Flat delivery fee',
  subtitleOf: (r) => `${str(r.fields.flatFeeText)} · ${str(r.fields.freeAboveText)} · version ${str(r.fields.version)}`,
  sheetSections: (r) => [
    {
      title: 'What shoppers pay',
      fields: [
        { label: 'Delivery by AfroTransact', value: str(r.fields.flatFeeText), kind: 'text' },
        { label: 'AfroTransact collects from the store and delivers', value: `${str(r.fields.flatFeeText)} (the same fee)`, kind: 'text' },
        { label: 'Free delivery', value: str(r.fields.freeAboveText), kind: 'text' },
        { label: 'Pickup at an AfroTransact point, collect from the store', value: 'Free', kind: 'text' },
        { label: 'Pre-order deliveries', value: 'The fee set on their own pre-order or run (Settings > Pre-orders)', kind: 'text' },
        { label: 'Address too far for standard delivery', value: 'The shopper asks for a quote; customer care agrees the amount', kind: 'text' }
      ]
    },
    {
      title: 'Seller carrier shipping',
      fields: [
        { label: 'Offer seller-shipped carrier options at checkout', value: str(r.fields.carrierText), kind: 'text' },
        { label: 'Why it is off', value: 'At launch AfroTransact sets all shipping. Turn it on only when sellers ship themselves.', kind: 'text' }
      ]
    },
    {
      title: 'How it applies',
      fields: [
        { label: 'One source', value: 'The estimate in the cart and checkout and the amount charged when the order is placed both read this record.', kind: 'text' },
        { label: 'When a change starts', value: 'Within 15 seconds in the cart and checkout. Orders already placed keep what they were charged.', kind: 'text' }
      ]
    },
    { title: 'Last change', fields: [{ label: 'Changed by', value: str(r.fields.updatedByName), kind: 'text' }, { label: 'Changed at', value: str(r.fields.updatedAt), kind: 'datetime' }] }
  ],
  actions: [],
  edit: {
    permission: 'orders:shipping.edit',
    form: () => {
      const s = getShippingSettings();
      return {
        title: 'Change the delivery fee',
        submitLabel: 'Save',
        intro: 'Shoppers see the new fee within 15 seconds and are charged it on new orders; placed orders keep what they paid. Every change is kept with who made it and why.',
        fields: [
          field('flatFee', 'Delivery fee (dollars)', 'number', true, { min: 0, max: 100, value: s.flatFeeMinor / 100, help: 'One flat fee for Delivery by AfroTransact and for AfroTransact collecting from a store. 0 is free.', section: 'What shoppers pay' }),
          field('freeAbove', 'Free delivery on packages of (dollars, blank = never free)', 'number', false, { min: 0, max: 100_000, value: s.freeAboveMinor === null ? null : s.freeAboveMinor / 100, help: 'A package whose items cost this much or more is delivered free.', section: 'What shoppers pay' }),
          field('sellerCarrierShipping', 'Offer seller-shipped carrier options at checkout', 'toggle', false, { value: s.sellerCarrierShippingEnabled, help: 'Off at launch: AfroTransact sets all shipping. Turn on only when sellers ship themselves.', section: 'Carrier shipping' }),
          field('reason', 'Why it changes (at least 10 characters)', 'textarea', true, { maxLength: 500, minLength: 10, section: 'Why' })
        ],
        changeKeys: ['flatFee', 'freeAbove', 'sellerCarrierShipping']
      };
    },
    validate: (_r, v) => {
      const errors: Record<string, string> = {};
      const fee = Number(v.flatFee);
      if (!Number.isFinite(fee) || fee < 0 || fee > 100) errors.flatFee = 'The delivery fee must be between 0 and 100.00 (0 is free)';
      if (str(v.freeAbove).trim() !== '') {
        const free = Number(v.freeAbove);
        if (!Number.isFinite(free) || free <= 0) errors.freeAbove = 'Free delivery above must be more than 0, or left empty for never';
      }
      if (str(v.reason).trim().length < 10) errors.reason = 'Say why: give a reason of at least 10 characters. It is logged with your name.';
      return errors;
    },
    apply: (r, v, ctx) => {
      const at = ctx?.now.toISOString() ?? new Date().toISOString();
      const before = getShippingSettings();
      const next = setShippingSettings({
        flatFeeMinor: Math.round(num(v.flatFee) * 100),
        freeAboveMinor: str(v.freeAbove).trim() === '' ? null : Math.round(num(v.freeAbove) * 100),
        sellerCarrierShippingEnabled: v.sellerCarrierShipping === true || v.sellerCarrierShipping === 'true'
      });
      Object.assign(r.fields, summaryFields());
      r.fields.updatedByName = ctx?.actor ?? 'Unknown user';
      r.fields.updatedAt = at;
      r.activity.unshift({ at, actor: ctx?.actor ?? 'Unknown user', text: `Changed ${dollars(before.flatFeeMinor)} → ${dollars(next.flatFeeMinor)}: ${str(v.reason)}` });
    }
  }
};
