/* Exchange rates (ADR 0005 §7) in the mock staff BFF: the staff-managed table of rates to USD that receiving suggests,
   with a reason on every change. Mirrors internal/app/staff_resources_exchange.go of the Go BFF over inventory-service
   /v1/exchange-rates. The Inventory hub mock reads this table for the rate it prefills (see HubRates). */
import { type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type ActionCtx, type MockResource } from './resources';

export const INVENTORY_ADJUST = 'inventory:adjust';
const RATE_PATTERN = '^[0-9]+(\\.[0-9]{1,10})?$';
const RATE_HELP = 'US dollars for 1 unit of the currency, e.g. 0.0577 for one Mexican peso. At most 10 decimals.';

const DAY = 86_400_000;
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));

export const rateId = (currency: string, rateDate: string) => `${currency}_${rateDate}`;

/** Currencies a rate can be set for (everything stock is received in except USD). */
const RATE_CURRENCIES = [
  { value: 'MXN', label: 'MXN · Mexican Peso' }, { value: 'NGN', label: 'NGN · Nigerian Naira' }, { value: 'GHS', label: 'GHS · Ghanaian Cedi' },
  { value: 'CAD', label: 'CAD · Canadian Dollar' }, { value: 'EUR', label: 'EUR · Euro' }, { value: 'GBP', label: 'GBP · British Pound' },
  { value: 'ZAR', label: 'ZAR · South African Rand' }, { value: 'KES', label: 'KES · Kenyan Shilling' }, { value: 'XOF', label: 'XOF · West African CFA Franc' }
];

export function seedExchangeRates(now: Date): MockRecord[] {
  const day = (ago: number) => new Date(now.getTime() - ago * DAY).toISOString().slice(0, 10);
  const at = (ago: number) => new Date(now.getTime() - ago * DAY).toISOString();
  const rate = (currency: string, ago: number, rateToUsd: number, note: string): MockRecord => ({
    id: rateId(currency, day(ago)),
    fields: { currency, rateDate: day(ago), rateToUsd, note, updatedBy: 'Ifeoma Okafor', updatedAt: at(ago), version: 0 },
    activity: [{ at: at(ago), actor: 'Ifeoma Okafor', text: `Set to ${rateToUsd}: Weekly rate` }]
  });
  return [
    rate('MXN', 12, 0.0577, 'Banxico FIX'), rate('MXN', 19, 0.0561, 'Banxico FIX'), rate('NGN', 9, 0.00066, 'CBN official'), rate('GHS', 9, 0.0649, 'Bank of Ghana'),
    rate('CAD', 5, 0.7312, 'Bank of Canada'), rate('EUR', 5, 1.0841, 'ECB reference')
  ];
}

const rateField = (value?: number): FormField => field('rateToUsd', 'USD per unit', 'number', true, { pattern: RATE_PATTERN, help: RATE_HELP, ...(value === undefined ? {} : { value }) });
const noteField = (value?: string): FormField => field('note', 'Source', 'text', false, { maxLength: 200, ...(value ? { value } : {}) });
const reasonField = (): FormField => field('reason', 'Reason', 'text', true, { maxLength: 500 });
const form = (title: string, submitLabel: string, fields: FormField[], intro: string): StaffForm => ({ title, submitLabel, fields, intro });

export const exchangeRatesDef: MockResource = {
  title: 'Exchange rates',
  columns: [
    { key: 'currency', label: 'Currency', kind: 'mono', width: 12 }, { key: 'rateDate', label: 'Day', kind: 'date', width: 16 }, { key: 'rateToUsd', label: 'USD per unit', kind: 'number', width: 18 },
    { key: 'note', label: 'Source', kind: 'text', width: 24 }, { key: 'updatedBy', label: 'Set by', kind: 'text', width: 16 }, { key: 'updatedAt', label: 'Updated', kind: 'datetime', width: 14 }
  ],
  facets: [],
  search: ['currency'],
  searchPlaceholder: 'Currency, e.g. MXN',
  sort: (a, b) => str(b.fields.rateDate).localeCompare(str(a.fields.rateDate)) || str(a.fields.currency).localeCompare(str(b.fields.currency)),
  exportable: false,
  titleOf: (r) => `${str(r.fields.currency)} on ${str(r.fields.rateDate)}`,
  subtitleOf: (r) => `1 ${str(r.fields.currency)} = ${str(r.fields.rateToUsd)} USD`,
  sections: [['Rate', ['currency', 'rateDate', 'rateToUsd', 'note', 'updatedBy', 'updatedAt']]],
  actions: [],
  edit: {
    permission: INVENTORY_ADJUST,
    form: (r) => form('Change the rate', 'Save rate', [rateField(num(r.fields.rateToUsd)), noteField(str(r.fields.note)), reasonField()], 'Receipts already posted keep the rate they were received at. The change is recorded with your reason.'),
    apply: (r, v, ctx?: ActionCtx) => {
      const before = str(r.fields.rateToUsd);
      r.fields.rateToUsd = num(v.rateToUsd);
      r.fields.note = str(v.note) || null;
      r.fields.version = num(r.fields.version) + 1;
      if (ctx) {
        r.fields.updatedBy = ctx.actor;
        r.fields.updatedAt = ctx.now.toISOString();
        r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text: `Changed from ${before} to ${str(v.rateToUsd)}: ${str(v.reason)}` });
      }
    }
  },
  create: {
    permission: INVENTORY_ADJUST,
    form: form(
      'Set an exchange rate',
      'Save rate',
      [field('currency', 'Currency', 'select', true, { options: RATE_CURRENCIES }), field('rateDate', 'Day', 'date', true), rateField(), noteField(), reasonField()],
      'The latest rate on or before the receiving day is suggested on receipts. Setting a day that already has a rate replaces it, with your reason on record.'
    ),
    build: (v, _id, ctx) => {
      const currency = str(v.currency).toUpperCase();
      const rateDate = str(v.rateDate);
      return {
        id: rateId(currency, rateDate),
        fields: { currency, rateDate, rateToUsd: num(v.rateToUsd), note: str(v.note) || null, updatedBy: ctx.actor, updatedAt: ctx.now.toISOString(), version: 0 },
        activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: `Set to ${str(v.rateToUsd)}: ${str(v.reason)}` }]
      };
    }
  }
};
