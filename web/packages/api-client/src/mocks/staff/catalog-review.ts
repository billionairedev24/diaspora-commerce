/* The catalog review queues in the mock staff BFF (S385 to S387, S390): change requests, wrong matches, duplicate pages,
   no-barcode items and held images. Mirrors internal/app/staff_resources_catalog_review.go of the Go BFF over catalog and
   media. Every decision needs a reason of 3 or more characters (the seller reads it) and catalog:moderate. */
import { type DetailSection, type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type ActionCtx, type MockAction, type MockResource } from './resources';

const PERMISSION = 'catalog:moderate';
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const log = (r: MockRecord, ctx: ActionCtx, text: string) => r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text });
const waiting = (r: MockRecord) => (str(r.fields.status) === 'PENDING' ? null : `Not possible while ${str(r.fields.status).toLowerCase()}.`);
const reasonField = (): FormField => field('reason', 'Reason (the seller reads it)', 'textarea', true, { maxLength: 1000, minLength: 3, help: 'At least 3 characters.' });
const form = (title: string, submitLabel: string, intro: string, ...extra: FormField[]): StaffForm => ({ title, submitLabel, intro, fields: [...extra, reasonField()] });

/** A picture that needs no network: a grey square with a label. */
const PICTURE = (label: string) => `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="480" height="480"><rect width="480" height="480" fill="#e8e8e8"/><text x="240" y="250" font-size="28" text-anchor="middle" fill="#555">${label}</text></svg>`)}`;

const TYPE_LABELS: Record<string, string> = {
  IMAGE_ADD: 'Add a picture', IMAGE_REPLACE: 'Replace a picture', TITLE: 'Change the title', ATTRIBUTE: 'Change a detail', DESCRIPTION: 'Change the description',
  CATEGORY: 'Change the category', BRAND: 'Change the brand', NEW_VARIANT: 'Add a size or colour', WRONG_MATCH: 'Wrong match', DUPLICATE_PAGE: 'Duplicate page'
};
const RESOLUTION_LABELS: Record<string, string> = {
  CORRECT_GTIN: 'Barcode corrected', MOVE_VARIANT: 'Variant moved to another page', SPLIT_VARIANT: 'Variant split onto its own page', MERGE: 'Pages merged', NEW_VARIANT: 'New variant added'
};

function decide(status: 'APPLIED' | 'REJECTED' | 'APPROVED', resolution?: string): MockAction['apply'] {
  return (r, v, ctx) => {
    r.fields.status = status;
    r.fields.decidedByName = ctx.actor;
    r.fields.decidedAt = ctx.now.toISOString();
    r.fields.reason = str(v.reason).trim();
    r.fields.waiting = '';
    if (resolution) r.fields.resolution = resolution;
    log(r, ctx, `${status === 'REJECTED' ? 'Not applied' : status === 'APPROVED' ? 'Accepted' : 'Applied'}${resolution ? ` (${RESOLUTION_LABELS[resolution] ?? resolution})` : ''}: ${str(v.reason).trim()}`);
  };
}

const requestSections = (r: MockRecord): DetailSection[] => {
  const f = r.fields;
  const out: DetailSection[] = [
    {
      title: 'Request',
      fields: [
        { label: 'Product page', value: str(f.title), kind: 'text' },
        { label: 'ATIN', value: str(f.atin), kind: 'mono' },
        { label: 'Asked by', value: str(f.requestedByOrgName), kind: 'text' },
        { label: 'Kind', value: str(f.change), kind: 'text' },
        { label: 'What is asked', value: str(f.asked), kind: 'text' },
        { label: "Seller's note", value: str(f.note) || '—', kind: 'text' },
        { label: 'Filed', value: str(f.requestedAt), kind: 'datetime' },
        { label: 'Answer due', value: str(f.slaDueAt), kind: 'datetime' },
        ...(str(f.status) === 'PENDING' ? [{ label: 'Waiting', value: str(f.waiting), kind: 'text' as const }] : [])
      ]
    }
  ];
  if (str(f.pictureUrl)) out.push({ title: 'Picture', fields: [{ label: 'Asked', value: str(f.pictureUrl), kind: 'image' }] });
  if (str(f.status) !== 'PENDING') {
    out.push({
      title: 'Decision',
      fields: [
        { label: 'Status', value: str(f.status), kind: 'status' },
        { label: 'Decided by', value: str(f.decidedByName), kind: 'text' },
        { label: 'When', value: str(f.decidedAt), kind: 'datetime' },
        { label: 'Reason', value: str(f.reason), kind: 'text' },
        { label: 'How', value: RESOLUTION_LABELS[str(f.resolution)] ?? '—', kind: 'text' }
      ]
    });
  }
  return out;
};

const requestColumns = (asked: string): MockResource['columns'] => [
  { key: 'title', label: 'Product page', kind: 'strong', width: 24, sortable: true },
  { key: 'change', label: 'Kind', kind: 'text', width: 16, sortable: true },
  { key: 'asked', label: asked, kind: 'text', width: 30, sortable: false },
  { key: 'requestedByOrgName', label: 'Seller', kind: 'text', width: 14, sortable: true },
  { key: 'waiting', label: 'Waiting', kind: 'text', width: 12, sortable: false },
  { key: 'status', label: 'Status', kind: 'status', width: 10, sortable: true }
];

const requestFacets = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPLIED', label: 'Applied' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'WITHDRAWN', label: 'Withdrawn' }
];

const rejectAction = (title: string, intro: string, label = 'Reject'): MockAction => ({
  key: 'reject', label, tone: 'danger', permission: PERMISSION, level: 'APPROVE', when: waiting, form: () => form(title, 'Reject', intro), apply: decide('REJECTED')
});

const baseRequest = (def: Partial<MockResource> & Pick<MockResource, 'title' | 'columns' | 'actions'>): MockResource => ({
  statusKey: 'status',
  facets: requestFacets,
  exportable: true,
  titleOf: (r) => str(r.fields.title),
  subtitleOf: (r) => `${str(r.fields.change)} · ${str(r.fields.requestedByOrgName)}`,
  sheetSections: requestSections,
  rowActions: ['reject'],
  ...def
});

export const changeRequestsDef: MockResource = baseRequest({
  title: 'Change requests',
  columns: requestColumns('What is asked'),
  actions: [
    {
      key: 'approve', label: 'Approve', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: (r) =>
        form(
          'Approve the change',
          'Approve',
          str(r.fields.type) === 'NEW_VARIANT'
            ? 'The new size or colour becomes a variant of the page with its own ATIN. A page that is on sale cannot take a variant that has no tax decision unless you accept that it leaves sale until tax review has approved it.'
            : 'The change is applied to the page and its owner is told. Everything is recorded with your name.',
          ...(str(r.fields.type) === 'NEW_VARIANT' ? [field('holdPageForTax', 'Take the page off sale until tax review approves the new variant', 'toggle', false, { help: 'Only needed when the page is on sale.' })] : [])
        ),
      apply: (r, v, ctx) => decide('APPLIED', str(r.fields.type) === 'NEW_VARIANT' ? 'NEW_VARIANT' : undefined)(r, v, ctx)
    },
    rejectAction('Do not apply the change', 'The seller sees “Not applied, because …” with your reason.')
  ]
});

export const wrongMatchesDef: MockResource = baseRequest({
  title: 'Wrong matches',
  columns: [
    { key: 'title', label: 'Product page shown', kind: 'strong', width: 26, sortable: true },
    { key: 'asked', label: 'What the seller says', kind: 'text', width: 30, sortable: false },
    { key: 'requestedByOrgName', label: 'Seller', kind: 'text', width: 14, sortable: true },
    { key: 'waiting', label: 'Waiting', kind: 'text', width: 12, sortable: false },
    { key: 'status', label: 'Status', kind: 'status', width: 10, sortable: true }
  ],
  sheetSections: (r) => [
    ...requestSections(r).slice(0, 1),
    {
      title: 'The match the seller was shown',
      fields: [
        { label: 'Barcode typed', value: str(r.fields.gtin), kind: 'mono' },
        { label: 'Product shown', value: str(r.fields.shown), kind: 'text' },
        { label: 'Variant ATIN', value: str(r.fields.variantAtin), kind: 'mono' }
      ]
    },
    ...requestSections(r).slice(1)
  ],
  actions: [
    {
      key: 'correct', label: 'Correct the barcode', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: () => form('Correct the barcode on the product', 'Correct', "The page's variant gets the barcode you type. A barcode another page has is a merge, not a correction.", field('gtin', 'Correct barcode', 'text', true, { maxLength: 20 })),
      apply: decide('APPLIED', 'CORRECT_GTIN')
    },
    {
      key: 'move', label: 'Move to another page', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: () =>
        form(
          'Move the variant to the page it belongs on',
          'Move',
          'The variant keeps its ATIN and its offers follow it. They wait for the tax check again before they are on sale. The page it leaves keeps at least one variant.',
          field('targetAtin', 'ATIN of the page it belongs on', 'text', true, { maxLength: 12 }),
          field('holdPageForTax', 'Take the other page off sale until tax review approves', 'toggle', false, { help: 'Only needed when that page is on sale and taxed differently.' })
        ),
      apply: decide('APPLIED', 'MOVE_VARIANT')
    },
    {
      key: 'split', label: 'Split onto its own page', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: () => form('Make the variant a page of its own', 'Split', 'The variant keeps its ATIN and becomes the only variant of a new page in review. It is not on sale until it is reviewed and its tax is approved.'),
      apply: decide('APPLIED', 'SPLIT_VARIANT')
    },
    rejectAction('The match is right', 'The seller sees “Not applied, because …” with your reason.', 'Reject the report')
  ]
});

export const duplicatePagesDef: MockResource = baseRequest({
  title: 'Duplicate pages',
  columns: [
    { key: 'title', label: 'Page', kind: 'strong', width: 24, sortable: true },
    { key: 'asked', label: 'Duplicate of', kind: 'text', width: 30, sortable: false },
    { key: 'requestedByOrgName', label: 'Reported by', kind: 'text', width: 14, sortable: true },
    { key: 'waiting', label: 'Waiting', kind: 'text', width: 12, sortable: false },
    { key: 'status', label: 'Status', kind: 'status', width: 10, sortable: true }
  ],
  sheetSections: (r) => [
    ...requestSections(r).slice(0, 1),
    { title: 'This page', fields: [{ label: 'Title', value: str(r.fields.title), kind: 'text' }, { label: 'ATIN', value: str(r.fields.atin), kind: 'mono' }, { label: 'Variants', value: '2', kind: 'text' }] },
    { title: 'The other page', fields: [{ label: 'Title', value: str(r.fields.otherTitle), kind: 'text' }, { label: 'ATIN', value: str(r.fields.otherAtin), kind: 'mono' }, { label: 'Variants', value: '1', kind: 'text' }] },
    ...requestSections(r).slice(1)
  ],
  actions: [
    {
      key: 'merge', label: 'Merge the pages', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: (r) =>
        form(
          'Merge the two pages',
          'Merge',
          'The page that goes is never on sale again and its ATIN redirects. Offers move to the page that stays and wait for the tax check again. This cannot be undone.',
          field('survivorAtin', 'The page that stays', 'select', true, {
            options: [{ value: str(r.fields.atin), label: `This page: ${str(r.fields.title)}` }, { value: str(r.fields.otherAtin), label: `The other page: ${str(r.fields.otherTitle)}` }],
            value: str(r.fields.atin)
          }),
          field('variantMap', 'Which variant becomes which (only when asked)', 'textarea', false, { maxLength: 2000, help: 'One per line: the ATIN of a variant of the page that goes = the ATIN of the variant that stays.' })
        ),
      apply: decide('APPLIED', 'MERGE')
    },
    rejectAction('Keep both pages', 'The seller sees “Not applied, because …” with your reason.', 'They are different products')
  ]
});

export const gtinExemptionsDef: MockResource = {
  title: 'No-barcode items',
  columns: [
    { key: 'title', label: 'Product', kind: 'strong', width: 24, sortable: true },
    { key: 'brand', label: 'Brand', kind: 'text', width: 14, sortable: true },
    { key: 'mpn', label: 'Part number', kind: 'mono', width: 12, sortable: true },
    { key: 'requestedByOrgName', label: 'Seller', kind: 'text', width: 14, sortable: true },
    { key: 'because', label: 'Why staff decide', kind: 'text', width: 20, sortable: false },
    { key: 'waiting', label: 'Waiting', kind: 'text', width: 12, sortable: false },
    { key: 'status', label: 'Status', kind: 'status', width: 10, sortable: true }
  ],
  statusKey: 'status',
  facets: [
    { value: 'PENDING', label: 'Pending' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' }
  ],
  exportable: true,
  titleOf: (r) => str(r.fields.title),
  subtitleOf: (r) => `No barcode · ${str(r.fields.requestedByOrgName)}`,
  rowActions: ['reject'],
  sheetSections: (r) => [
    {
      title: 'Request',
      fields: [
        { label: 'Product', value: str(r.fields.title), kind: 'text' },
        { label: 'ATIN', value: str(r.fields.productAtin), kind: 'mono' },
        { label: 'Seller', value: str(r.fields.requestedByOrgName), kind: 'text' },
        { label: 'Brand', value: str(r.fields.brand), kind: 'text' },
        { label: 'Part number', value: str(r.fields.mpn) || '—', kind: 'text' },
        { label: 'Why staff decide', value: str(r.fields.because), kind: 'text' },
        { label: "Seller's reason", value: str(r.fields.sellerReason) || '—', kind: 'text' },
        { label: 'What the seller reads', value: 'We are checking that this item has no barcode.', kind: 'text' },
        { label: 'Answer due', value: str(r.fields.slaDueAt), kind: 'datetime' }
      ]
    },
    ...(str(r.fields.status) !== 'PENDING'
      ? [{ title: 'Decision', fields: [{ label: 'Decided by', value: str(r.fields.decidedByName), kind: 'text' as const }, { label: 'Reason', value: str(r.fields.reason), kind: 'text' as const }] }]
      : [])
  ],
  linesTitle: 'Pages that may be the same item',
  lineColumns: [
    { key: 'title', label: 'Page', kind: 'strong', width: 34, sortable: false },
    { key: 'atin', label: 'ATIN', kind: 'mono', width: 14, sortable: false },
    { key: 'why', label: 'Matched on', kind: 'text', width: 16, sortable: false },
    { key: 'score', label: 'Similarity', kind: 'text', width: 10, sortable: false }
  ],
  actions: [
    {
      key: 'approve', label: 'Accept: no barcode', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: waiting,
      form: () => form('Accept that the item has no barcode', 'Accept', 'The page moves to listing review. It is not on sale until it is approved there and its tax decision exists.'),
      apply: decide('APPROVED')
    },
    rejectAction('The item needs a barcode', 'The page is rejected and the seller reads your reason.')
  ]
};

const held = (r: MockRecord) => str(r.fields.state) === 'HELD';
const heldGone = (r: MockRecord) => (held(r) ? null : 'This photo was already decided.');
const heldDecision =
  (state: 'RELEASED' | 'REJECTED'): MockAction['apply'] =>
  (r, v, ctx) => {
    r.fields.state = state;
    r.fields.waiting = '';
    log(r, ctx, `${state === 'RELEASED' ? 'Released' : 'Rejected'}: ${str(v.reason).trim()}`);
  };

export const heldImagesDef: MockResource = {
  title: 'Held images',
  columns: [
    { key: 'fileName', label: 'Photo', kind: 'strong', width: 20, sortable: true },
    { key: 'ownerOrgName', label: 'Seller', kind: 'text', width: 16, sortable: true },
    { key: 'slot', label: 'Used as', kind: 'text', width: 10, sortable: false },
    { key: 'found', label: 'What was found', kind: 'text', width: 32, sortable: false },
    { key: 'waiting', label: 'Waiting', kind: 'text', width: 14, sortable: false },
    { key: 'due', label: 'Answer due', kind: 'datetime', width: 14, sortable: true }
  ],
  statusKey: 'state',
  facets: [],
  exportable: true,
  titleOf: (r) => str(r.fields.fileName),
  subtitleOf: (r) => `${str(r.fields.ownerOrgName)} · ${str(r.fields.slot)}`,
  rowActions: ['reject'],
  banner: () => ({ tone: 'info', title: 'Private photo', text: 'The picture is private to staff. The link to it works for 5 minutes; close and open the sheet again if it stops loading.' }),
  sheetSections: (r) => [
    { title: 'Photo', fields: [{ label: 'Held photo', value: str(r.fields.pictureUrl), kind: 'image' }] },
    {
      title: 'Why it is held',
      fields: [
        { label: 'Seller', value: str(r.fields.ownerOrgName), kind: 'text' },
        { label: 'Used as', value: str(r.fields.slot), kind: 'text' },
        { label: 'Size', value: str(r.fields.size), kind: 'text' },
        { label: 'Checks found', value: str(r.fields.found), kind: 'text' },
        { label: 'Labels the AI found', value: str(r.fields.labels), kind: 'text' },
        { label: 'Match with the title and brand', value: `${str(r.fields.mismatch)}% mismatch (0% matches, 100% does not)`, kind: 'text' }
      ]
    }
  ],
  actions: [
    {
      key: 'approve', label: 'Release the photo', tone: 'primary', permission: PERMISSION, level: 'APPROVE', when: heldGone,
      form: () => form('Release the photo', 'Release', 'The photo is published and the listings that use it can go on.'),
      apply: heldDecision('RELEASED')
    },
    {
      key: 'reject', label: 'Reject the photo', tone: 'danger', permission: PERMISSION, level: 'APPROVE', when: heldGone,
      form: () => form('Reject the photo', 'Reject', 'The photo is deleted and the seller reads your reason.'),
      apply: heldDecision('REJECTED')
    }
  ]
};

type Key = 'change-requests' | 'wrong-matches' | 'duplicate-pages' | 'gtin-exemptions' | 'held-images';

export function seedCatalogReview(now: Date): Record<Key, MockRecord[]> {
  const ago = (hours: number) => new Date(now.getTime() - hours * 3_600_000).toISOString();
  const due = (hours: number) => new Date(now.getTime() - hours * 3_600_000 + 2 * 86_400_000).toISOString();
  const request = (id: string, type: string, title: string, atin: string, seller: string, asked: string, hours: number, extra: Record<string, Scalar> = {}): MockRecord => ({
    id,
    fields: {
      type, change: TYPE_LABELS[type] ?? type, title, atin, requestedByOrgName: seller, asked, status: 'PENDING', requestedAt: ago(hours), slaDueAt: due(hours), note: '',
      waiting: hours >= 48 ? `${Math.floor(hours / 24)} days${hours > 48 ? ' · overdue' : ''}` : `${hours} h`, ...extra
    },
    activity: [{ at: ago(hours), actor: seller, text: 'Filed' }]
  });
  const decidedRequest = request('cr-4', 'DESCRIPTION', 'Black soap', 'AT10000031', 'Kente House', 'New description: Hand-made in Ghana', 100);
  Object.assign(decidedRequest.fields, { status: 'APPLIED', waiting: '', decidedByName: 'Mariam Bello', decidedAt: ago(30), reason: 'Matches the pack' });
  return {
    'change-requests': [
      request('cr-1', 'TITLE', 'Ankara print tote', 'AT10000001', 'Kente House', 'New title: Ankara print tote, large', 70, { note: 'The pack says large' }),
      request('cr-2', 'IMAGE_REPLACE', 'Shea butter 8 oz', 'AT10000011', 'Lagos Spice Co', 'A picture to replace picture 1', 20, { pictureUrl: PICTURE('New main photo') }),
      request('cr-3', 'NEW_VARIANT', 'Ankara wrap dress', 'AT10000021', 'Adire Lagos', 'A new size: XL', 5),
      decidedRequest
    ],
    'wrong-matches': [
      request('wm-1', 'WRONG_MATCH', 'Ankara wrap dress', 'AT10000021', 'Kente House', 'Seller says the barcode 012345678905 is not this product', 26, { gtin: '012345678905', shown: 'Size: M, Color: Gold', variantAtin: 'AT10000022' })
    ],
    'duplicate-pages': [
      request('dp-1', 'DUPLICATE_PAGE', 'Ankara tote', 'AT10000001', 'Lagos Spice Co', 'Same product as Ankara tote bag', 8, { otherTitle: 'Ankara tote bag', otherAtin: 'AT10000009' })
    ],
    'gtin-exemptions': [
      {
        id: 'ex-1',
        fields: {
          title: 'Hand carved stool', productAtin: 'AT10000041', brand: 'Adire Lagos', mpn: 'AB-123', requestedByOrgName: 'Kente House', because: 'Brand that carries barcodes on other pages', sellerReason: 'Handmade, no barcode',
          status: 'PENDING', waiting: '5 h', slaDueAt: due(5)
        },
        activity: [{ at: ago(5), actor: 'Kente House', text: 'Filed' }],
        lines: [{ title: 'Hand carved teak stool', atin: 'AT10000049', why: 'brand and part number and title', score: '100%' }]
      }
    ],
    'held-images': [
      {
        id: 'img-1',
        fields: {
          fileName: 'tote-main.jpg', ownerOrgName: 'Kente House', slot: 'Main photo', found: 'Text, logo or watermark', labels: 'Watermark', mismatch: 62, size: '1600 by 1200 pixels', state: 'HELD', pictureUrl: PICTURE('Held photo'),
          waiting: '3 h', due: due(3)
        },
        activity: [{ at: ago(3), actor: 'Media', text: 'Held: text, logo or watermark' }]
      },
      {
        id: 'img-2',
        fields: {
          fileName: 'soap-front.png', ownerOrgName: 'Lagos Spice Co', slot: 'Extra photo', found: 'Does not match the title or brand', labels: '', mismatch: 81, size: '1200 by 1200 pixels', state: 'HELD',
          pictureUrl: PICTURE('Held photo 2'), waiting: '2 days · overdue', due: due(60)
        },
        activity: [{ at: ago(60), actor: 'Media', text: 'Held: picture does not match the title' }]
      }
    ]
  };
}
