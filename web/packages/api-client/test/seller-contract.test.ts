import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  type AddBillingPaymentMethodInput,
  type Availability,
  type BillingPlanChangePreview,
  type BillingSetupIntent,
  type BillingView,
  type BookingCard,
  type CalendarView,
  type CancellationPolicy,
  type ComplianceSummary,
  createSellerApiClient,
  type Dashboard,
  type Earnings,
  type InventoryView,
  isApiError,
  type LabelPurchase,
  type ListingContext,
  type ListingPage,
  type ListingRow,
  type ListingWriterDraft,
  type OnboardingBusinessInput,
  type OnboardingComplianceInput,
  type OnboardingDetailsInput,
  type OnboardingOwnersInput,
  type OnboardingPlanInput,
  type OnboardingPlans,
  type OnboardingStatus,
  type OnboardingStripeSession,
  type OnboardingUploadRequest,
  type PayoutsView,
  type ProductDraft,
  type ProductDraftList,
  type ProductImport,
  type ProductImportPage,
  type ProductImportRowPage,
  type ProductTypeSchema,
  type RateQuotes,
  type SavedProductDraft,
  type SellerFeesView,
  type SellerOfferList,
  type SellerOrderDetail,
  type SellerOrderPage,
  type SellerProductDetail,
  type SellerRewardsView,
  type SellerSession,
  type ServiceOffer,
  type ShipFromAddress,
  type TeamView
} from '../src';
import { type components } from '../src/generated/gateway-bff-seller';
import { getSellerDb, MOCK_ROLE_COOKIE, PENDING_ORG, PROVIDER_ORG, resetSellerDb, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

type S = components['schemas'];

const client = (cookie?: string) =>
  createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: cookie ? { cookie } : {}, fetch: (req) => handleSellerMockRequest(req) });

const keysOf = (s: SellerSession) => s.navGroups.flatMap((g) => g.modules.map((m) => m.key));

describe('Seller Zod schemas stay aligned with contracts/openapi/gateway-bff-seller.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<SellerSession>().toExtend<S['SellerSession']>();
    expectTypeOf<ComplianceSummary>().toExtend<S['ComplianceSummary']>();
    expectTypeOf<OnboardingStatus>().toExtend<S['OnboardingStatus']>();
    expectTypeOf<OnboardingBusinessInput>().toExtend<S['OnboardingBusinessInput']>();
    expectTypeOf<OnboardingDetailsInput>().toExtend<S['OnboardingDetailsInput']>();
    expectTypeOf<OnboardingOwnersInput>().toExtend<S['OnboardingOwnersInput']>();
    expectTypeOf<OnboardingPlanInput>().toExtend<S['OnboardingPlanInput']>();
    expectTypeOf<OnboardingComplianceInput>().toExtend<S['OnboardingComplianceInput']>();
    expectTypeOf<OnboardingStripeSession>().toExtend<S['OnboardingStripeSession']>();
    expectTypeOf<OnboardingPlans>().toExtend<S['OnboardingPlans']>();
    expectTypeOf<OnboardingUploadRequest>().toExtend<S['OnboardingUploadRequest']>();
    expectTypeOf<Dashboard>().toExtend<S['Dashboard']>();
    expectTypeOf<SellerOrderPage>().toExtend<S['SellerOrderPage']>();
    expectTypeOf<SellerOrderDetail>().toExtend<S['SellerOrderDetail']>();
    expectTypeOf<RateQuotes>().toExtend<S['RateQuotes']>();
    expectTypeOf<LabelPurchase>().toExtend<S['LabelPurchase']>();
    expectTypeOf<ListingPage>().toExtend<S['ListingPage']>();
    expectTypeOf<ListingRow>().toExtend<S['ListingRow']>();
    expectTypeOf<ProductTypeSchema>().toExtend<S['ProductTypeSchema']>();
    expectTypeOf<InventoryView>().toExtend<S['InventoryView']>();
    expectTypeOf<PayoutsView>().toExtend<S['PayoutsView']>();
    expectTypeOf<SellerFeesView>().toExtend<S['SellerFeesView']>();
    expectTypeOf<ShipFromAddress>().toExtend<S['ShipFromAddress']>();
    expectTypeOf<SellerOfferList>().toExtend<S['SellerOfferList']>();
    expectTypeOf<BillingView>().toExtend<S['BillingView']>();
    expectTypeOf<SellerRewardsView>().toExtend<S['SellerRewardsView']>();
    expectTypeOf<BillingPlanChangePreview>().toExtend<S['BillingPlanChangePreview']>();
    expectTypeOf<BillingSetupIntent>().toExtend<S['BillingSetupIntent']>();
    expectTypeOf<AddBillingPaymentMethodInput>().toExtend<S['AddBillingPaymentMethod']>();
    expectTypeOf<TeamView>().toExtend<S['TeamView']>();
    expectTypeOf<CalendarView>().toExtend<S['CalendarView']>();
    expectTypeOf<BookingCard>().toExtend<S['BookingCard']>();
    expectTypeOf<Availability>().toExtend<S['Availability']>();
    expectTypeOf<ServiceOffer>().toExtend<S['ServiceOffer']>();
    expectTypeOf<CancellationPolicy>().toExtend<S['CancellationPolicy']>();
    expectTypeOf<Earnings>().toExtend<S['Earnings']>();
    // One-step product form (S307) and bulk upload (S308)
    expectTypeOf<ProductDraft>().toExtend<S['ProductDraft']>();
    expectTypeOf<SellerProductDetail>().toExtend<S['ProductDetail']>();
    expectTypeOf<SavedProductDraft>().toExtend<S['SavedProductDraft']>();
    expectTypeOf<ProductDraftList>().toExtend<S['ProductDraftList']>();
    expectTypeOf<ListingContext>().toExtend<S['ListingContext']>();
    expectTypeOf<ListingWriterDraft>().toExtend<S['ListingWriterDraft']>();
    expectTypeOf<ProductImport>().toExtend<S['ProductImport']>();
    expectTypeOf<ProductImportPage>().toExtend<S['ProductImportPage']>();
    expectTypeOf<ProductImportRowPage>().toExtend<S['ProductImportRowPage']>();
  });

  it('generated file carries the do-not-edit banner', () => {
    const text = readFileSync(resolve(__dirname, '../src/generated/gateway-bff-seller.ts'), 'utf8');
    expect(text.startsWith('// GENERATED from contracts/openapi/gateway-bff-seller.yaml')).toBe(true);
  });
});

describe('seller MSW mocks (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('serves every Seller Center screen and keeps navigation role-aware', async () => {
    const api = client();
    const session = await api.getSession();
    expect(session.portal).toBe('SELLER_CENTER');
    expect(keysOf(session)).toEqual(['home', 'orders', 'returns', 'disputes', 'listings', 'reviews', 'inventory', 'coupons', 'payouts', 'fees', 'billing', 'account-health', 'team']);
    expect(session.navGroups.map((g) => g.label)).toEqual(['Overview', 'Commerce', 'Growth', 'Finance', 'Trust & compliance', 'Settings']);
    // Returns and disputes: read only, filterable by status; the buyer is never in the payload.
    await expect(api.listReturns()).resolves.toHaveLength(2);
    await expect(api.listReturns('REQUESTED')).resolves.toMatchObject([{ number: 'RMA-7B20C9D4', status: 'REQUESTED' }]);
    const disputes = await api.listDisputes('AWAITING_SELLER');
    expect(disputes).toMatchObject([{ reference: 'CLM-1001', deadlineState: 'AT_RISK', responded: false }]);
    expect(JSON.stringify(disputes)).not.toContain('customerId');
    await expect(api.getCompliance()).resolves.toMatchObject({ showBanner: true, tone: 'warning', openItems: 2 });
    // Account health: each item names the Stripe embedded component that collects it; the payout bank is changed in
    // account management. The Account Session is the labelled local stand-in in the mock.
    const health = await api.getCompliance();
    expect(health.items.map((i) => i.component)).toEqual(['account_onboarding', 'account_management']);
    expect(health.payoutBank).toMatchObject({ last4: '6789', status: 'errored' });
    await expect(api.createSellerStripeSession()).resolves.toMatchObject({ localTest: true });
    const dash = await api.getDashboard(30);
    expect(dash.charts.map((c) => c.id)).toEqual(['sales', 'top-products', 'fulfilment', 'stock']);
    const orders = await api.listOrders({ limit: 10 });
    expect(orders.items).toHaveLength(10);
    expect(orders.nextCursor).toBe('10');
    await api.listListings({ page: 0, size: 25 });
    await api.getInventory(false);
    await api.getPayouts();
    const fees = await api.getFees();
    expect(fees.current.categories.find((c) => c.source === 'OVERRIDE')?.category).toBe('groceries');
    expect(fees.upcoming?.fbaPerUnitMinor).toBe(350);
    await api.getBilling();
    const team = await api.getTeam();
    expect(team.roles).toHaveLength(7);
    await api.getCategoryTree();

    const fulfil = client(`${MOCK_ROLE_COOKIE}=seller.fulfilment`);
    const keys = keysOf(await fulfil.getSession());
    expect(keys).toContain('orders');
    expect(keys).not.toContain('billing');
    await expect(fulfil.getBilling()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('lets sellers only confirm, pack and mark ready, and buys a label once per key', async () => {
    const api = client();
    const row = (await api.listOrders({ status: 'PLACED', limit: 1 })).items[0];
    if (!row) throw new Error('fixture has a placed order');
    await expect(api.updateShipmentStatus(row.orderNumber, row.shipmentId, 'PACKED')).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    for (const st of ['CONFIRMED', 'PACKED', 'READY'] as const) {
      const o = await api.updateShipmentStatus(row.orderNumber, row.shipmentId, st);
      expect(o.shipments[0]?.status).toBe(st);
    }
    const from = { name: 'Ade’s Kitchen', phone: null, line1: '1 Bellaire Blvd', line2: null, city: 'Houston', state: 'TX', postalCode: '77036', country: 'US' };
    const rates = await api.quoteRates(row.orderNumber, row.shipmentId, { weightLb: 3 }, from);
    expect(rates.quotes.length).toBeGreaterThanOrEqual(4);
    const carriers = new Set(rates.quotes.map((quote) => quote.carrier));
    expect(carriers.has('usps')).toBe(true);
    expect(carriers.has('ups')).toBe(true);
    expect(carriers.has('fedex')).toBe(true);
    expect(carriers.has('dhl')).toBe(true);

    const fedexRates = await api.quoteRates(row.orderNumber, row.shipmentId, { weightLb: 3 }, from, 'fedex');
    expect(fedexRates.quotes.length).toBeGreaterThan(0);
    expect(fedexRates.quotes.every((quote) => quote.carrier === 'fedex')).toBe(true);

    const q = rates.quotes[0];
    if (!q) throw new Error('quotes');
    const label = await api.buyLabel(row.orderNumber, row.shipmentId, { carrier: q.carrier, service: q.service, parcel: { weightLb: 3 }, shipFrom: from }, 'key-123456789');
    expect(label.order.shipments[0]?.canBuyLabel).toBe(false);
    const again = await api.buyLabel(row.orderNumber, row.shipmentId, { carrier: q.carrier, service: q.service, parcel: { weightLb: 3 }, shipFrom: from }, 'key-123456789');
    expect(again.trackingNumber).toBe(label.trackingNumber);
  });

  it('submits a product for review or pending category approval', async () => {
    const api = client();
    const base = {
      title: 'Ankara Wrap Dress with Puff Sleeves', attributes: { brand: 'Lagos Textile Hub' }, images: [{ url: 'https://img.afrotransact.dev/a.webp', contentType: 'image/webp' }],
      variationTheme: 'Size', variants: [{ options: { Size: 'M' }, sellerSku: 'ANK-M', gtinExempt: true, priceMinor: 3900, quantity: 10 }]
    };
    await expect(api.submitProduct({ ...base, categoryId: 'pt-dresses' })).resolves.toMatchObject({ status: 'IN_REVIEW' });
    const req = await api.requestCategory({ parentPath: 'Fashion › Women', name: 'Bridal aso-ebi sets', exampleProduct: '6-piece set', detailsNeeded: 'Pieces, colour' });
    const other = { ...base, variants: [{ options: { Size: 'L' }, sellerSku: 'ANK-L', gtinExempt: true, priceMinor: 3900, quantity: 10 }] };
    await expect(api.submitProduct({ ...other, categoryRequestId: req.id })).resolves.toMatchObject({ status: 'PENDING_CATEGORY_APPROVAL' });
    await expect(api.submitProduct({ ...other, categoryId: 'pt-dresses' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });

  it('adds a product in one step with listing details, edits it and autosaves drafts (S307)', async () => {
    const api = client();
    const ctx = await api.getListingContext();
    expect(ctx.importLimits).toMatchObject({ maxRows: 5000, maxFileBytes: 10 * 1024 * 1024 });
    expect(ctx.aiListingWriter.available).toBe(getSellerDb().subscription.SELLER.planCode !== 'seller_essentials');
    const draft = await api.createProductDraft({ title: 'Suya Pepper', payload: { title: 'Suya Pepper' } });
    const saved = await api.saveProductDraft(draft.id, { title: 'Suya Pepper Spice', payload: { title: 'Suya Pepper Spice' }, version: draft.version });
    await expect(api.saveProductDraft(draft.id, { payload: {}, version: draft.version })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    expect((await api.listProductDrafts()).items.map((d) => d.id)).toContain(saved.id);

    const input: ProductDraft = {
      categoryId: 'pt-spices', title: 'Suya Pepper Spice Blend from Kano', attributes: { brand: 'Mama Kano', shelf: '12 months' },
      images: [{ url: 'https://img.afrotransact.dev/suya.webp', contentType: 'image/webp' }], variationTheme: '',
      variants: [{ options: {}, sellerSku: 'suya-1', gtinExempt: true, priceMinor: 899, compareAtMinor: 1199, quantity: 40 }],
      listing: { description: 'Hand-ground in Kano.', condition: 'NEW', keywords: ['suya'], fulfilment: 'AFROTRANSACT', handlingDays: 2 }, draftId: saved.id
    };
    await expect(api.submitProduct(input)).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const row = await api.submitProduct({ ...input, listing: { ...input.listing, packaging: { lengthIn: 6, widthIn: 4, heightIn: 2, weightLb: 0.5 } } });
    expect(row).toMatchObject({ status: 'IN_REVIEW', sku: 'SUYA-1', fulfilment: 'AFROTRANSACT' });
    expect((await api.listProductDrafts()).items).toHaveLength(0);
    const detail = await api.getProduct(row.productId);
    expect(detail).toMatchObject({ productType: 'grocery', categoryPath: 'Groceries › Pantry › Spices & seasonings', variationTheme: '' });
    expect(detail.variants[0]).toMatchObject({ compareAtMinor: 1199, sellerSku: 'SUYA-1' });
    const edited = await api.updateProduct(row.productId, { ...input, draftId: undefined, title: 'Suya Pepper Spice Blend, Kano Style', version: detail.version,
      listing: { ...input.listing, fulfilment: 'SELLER' } });
    expect(edited.title).toBe('Suya Pepper Spice Blend, Kano Style');
    await expect(api.updateProduct(row.productId, { ...input, draftId: undefined, version: detail.version, listing: { fulfilment: 'SELLER' } })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);

    const live = getSellerDb().listings.find((l) => l.status === 'LIVE' && l.variants.length > 1);
    if (!live) throw new Error('fixture has a live listing with variants');
    const liveDetail = await api.getProduct(live.productId);
    await expect(api.updateProduct(live.productId, { categoryId: liveDetail.categoryId ?? undefined, title: liveDetail.title, attributes: liveDetail.attributes, images: liveDetail.images,
      variationTheme: liveDetail.variationTheme, variants: liveDetail.variants.slice(0, 1).map((v) => ({ ...v, gtin: v.gtin ?? undefined })) })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });

  it('bulk uploads a CSV: template, preview with per-row errors, error report, import of valid rows (S308)', async () => {
    const api = client();
    const template = await api.downloadImportTemplate({ format: 'csv', productType: 'grocery' });
    expect(template.fileName).toBe('afrotransact-grocery-template.csv');
    const text = await template.blob.text();
    expect(text).toContain('SKU *,');
    const csv = [
      'SKU *,Category,Title *,Description,Image URLs *,Price (USD) *,Quantity *,Barcode (GTIN/UPC/EAN),No barcode',
      'JOLLOF-4,Groceries › Pantry › Spices & seasonings,Jollof Seasoning Smoky 4 oz,Our blend,https://example.com/j.jpg,6.99,40,,Yes',
      'EGUSI-1,"Groceries › Pantry › Rice, grains & flours",Egusi Ground 1 lb,Ground melon seed,https://example.com/e.jpg,12.5O,25,4006381333932,No',
      'JOLLOF-4,Groceries › Pantry › Spices & seasonings,Jollof Seasoning Mild 4 oz,,http://example.com/m.jpg,5.99,10,,Yes',
      'ZOBO-8,Groceries › Pantry › Spices & seasonings,"Zobo Leaves, Dried, 8 oz",Hibiscus,https://example.com/z.jpg,7.50,30,,Yes'
    ].join('\n');
    const file = new File([csv], 'products.csv', { type: 'text/csv' });
    const upload = await api.uploadImport(file, 'CREATE');
    expect(upload).toMatchObject({ status: 'VALIDATED', totalRows: 4, errorRows: 3, validRows: 1, format: 'CSV' });
    const errors = await api.listImportRows(upload.id, { status: 'ERROR', page: 0, size: 50 });
    expect(errors.items.map((r) => r.rowNumber)).toEqual([2, 3, 4]);
    const messages = errors.items.flatMap((r) => r.messages.map((m) => m.message)).join(' / ');
    expect(messages).toContain('more than once');
    expect(messages).toContain('not a price');
    expect(messages).toContain('check digit');
    expect(messages).toContain('https image link');
    const report = await api.downloadImportReport(upload.id, 'csv');
    expect(await report.blob.text()).toContain('Errors');
    await expect(api.startImport(upload.id, false)).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    await expect(api.startImport(upload.id, true)).resolves.toMatchObject({ status: 'QUEUED', validOnly: true });
    let job = await api.getImport(upload.id);
    for (let i = 0; i < 5 && job.status !== 'COMPLETED'; i++) job = await api.getImport(upload.id);
    expect(job).toMatchObject({ status: 'COMPLETED', createdProducts: 1, processedRows: 4 });
    expect((await api.listImports(0, 25)).items[0]?.id).toBe(upload.id);
    expect(getSellerDb().listings[0]?.title).toBe('Zobo Leaves, Dried, 8 oz');
    await expect(api.uploadImport(new File(['x'], 'products.xls'), 'CREATE')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });

  it('switches organisations: Provider Hub tools and onboarding-only before approval', async () => {
    const api = client();
    const provider = await api.setActiveOrg(PROVIDER_ORG);
    expect(provider.portal).toBe('PROVIDER_HUB');
    expect(keysOf(provider)).toContain('calendar');
    expect(keysOf(provider)).not.toContain('orders');
    const cal = await api.getCalendar('2026-10-05', '2026-10-18');
    expect(cal.days).toHaveLength(14);
    const reqs = await api.listBookingRequests();
    const first = reqs[0];
    if (!first) throw new Error('fixture has requests');
    await expect(api.actOnBooking(first.id, { action: 'DECLINE' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    await expect(api.actOnBooking(first.id, { action: 'CONFIRM' })).resolves.toMatchObject({ status: 'CONFIRMED' });
    const earnings = await api.getEarnings(undefined, undefined);
    const t = earnings.totals;
    expect(t.netMinor).toBe(t.collectedMinor - t.commissionMinor - t.cardFeesMinor - t.lateCancelFeesMinor);
    await expect(api.saveCancellationPolicy({ kind: 'CUSTOM', freeHours: 24, partialRefund: true, partialFromHours: 48, partialPercent: 50 })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422
    );
    await expect(api.getCancellationPolicy()).resolves.toMatchObject({ kind: 'MODERATE', label: 'Moderate' });

    const pending = await api.setActiveOrg(PENDING_ORG);
    expect(keysOf(pending)).toEqual(['onboarding']);
    await expect(api.listListings({ page: 0, size: 25 })).rejects.toSatisfy((e) => isApiError(e) && e.code === 'NOT_APPROVED');
    await expect(api.getOnboarding()).resolves.toMatchObject({ status: 'INFO_REQUESTED', canResume: true });
    await api.setActiveOrg(SELLER_ORG);
  });

  it('starts selling once for a customer, prefilled from the account, and autosaves the first steps (S286)', async () => {
    const api = client();
    await expect(api.startOnboarding('SELLER')).rejects.toSatisfy((e) => isApiError(e) && e.code === 'ALREADY_APPROVED');
    getSellerDb().orgs = [];
    const none = await api.getSession();
    expect(none.activeOrg).toBeNull();
    const started = await api.startOnboarding('SELLER');
    expect(started).toMatchObject({ status: 'DRAFT', form: { details: { legalFirstName: 'Titilayo', address: { city: 'Houston' } } } });
    expect(started.form.prefilled).toContain('details.address');
    await expect(api.startOnboarding('SELLER')).resolves.toMatchObject({ orgId: started.orgId });
    await expect(api.startOnboarding('PROVIDER')).rejects.toSatisfy((e) => isApiError(e) && e.code === 'APPLICATION_EXISTS');
    expect(keysOf(await api.getSession())).toEqual(['onboarding']);
    await expect(api.saveOnboardingBusiness({ storeName: 'x' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const saved = await api.saveOnboardingBusiness({ offering: 'PRODUCTS', entityType: 'SOLE_PROPRIETOR', country: 'US', storeName: 'Titi Foods', contactEmail: 'titilayo@adeskitchen.com', phone: '+17132490142' });
    expect(saved.form.prefilled).toEqual(['business.contactEmail', 'business.phone', 'details.address', 'details.legalFirstName', 'details.legalLastName']);
    const details = await api.saveOnboardingDetails({ legalFirstName: 'Titi', legalLastName: 'Adebayo', address: { line1: '1 Main St', city: 'Houston', state: 'tx', postalCode: '77002' } });
    expect(details.form.details.address?.state).toBe('TX');
    expect(details.form.prefilled).toEqual(['business.contactEmail', 'business.phone', 'details.legalLastName']);
  });

  it('runs the whole wizard: completion only with real data, Stripe stand-in collecting the payout bank, submit (S289)', async () => {
    const api = client();
    getSellerDb().orgs = [];
    const started = await api.startOnboarding('SELLER');
    expect(started.resumeStep).toBe('business');
    // Stripe collects the payout bank account: nothing to show before the Stripe account.
    expect(started.form.payouts).toMatchObject({ status: 'NOT_STARTED', linked: false, accountLast4: null });
    expect(started.steps.find((s) => s.key === 'PAYOUTS')?.state).toBe('TODO');
    const b = await api.saveOnboardingBusiness({ offering: 'PRODUCTS', entityType: 'SINGLE_MEMBER_LLC', country: 'US', storeName: 'Titi Foods', contactEmail: 't@titi.test', phone: '+17132490142' });
    expect(b.steps.map((s) => `${s.slug}:${s.state}`)).toEqual(['business:DONE', 'business-details:TODO', 'owners:TODO', 'stripe-verification:TODO', 'payouts:TODO', 'plan:TODO', 'catalog-compliance:TODO', 'review:TODO']);
    expect(b.resumeStep).toBe('business-details');
    const d = await api.saveOnboardingDetails({ legalName: 'Titi Foods LLC', taxId: '12-3456789', stateOfFormation: 'TX', dateOfFormation: '2021-03-01', formationDocumentAssetId: 'asset-doc-1',
      address: { line1: '1 Main St', city: 'Houston', state: 'TX', postalCode: '77002' } });
    expect(d.form.details.taxIdMasked).toBe('••-•••6789');
    expect(JSON.stringify(d)).not.toContain('3456789');
    await expect(api.saveOnboardingOwners({ owners: [{ firstName: 'A', ownershipPercent: 70 }, { firstName: 'B', ownershipPercent: 40 }] })).rejects.toSatisfy(
      (e) => isApiError(e) && e.fieldErrors['owners.ownershipTotal'] !== undefined
    );
    const o = await api.saveOnboardingOwners({ owners: [{ firstName: 'Titi', lastName: 'Adebayo', title: 'Owner', email: 't@titi.test', ownershipPercent: 100, significantControl: true, dateOfBirth: '1985-01-02',
      address: { line1: '1 Main St', city: 'Houston', state: 'TX', postalCode: '77002' } }] });
    expect(o.resumeStep).toBe('stripe-verification');
    const session = await api.createOnboardingStripeSession();
    expect(session.localTest).toBe(true);
    expect((await api.getOnboarding()).form.payouts.status).toBe('NEEDS_BANK');
    // The stand-in, like Stripe's embedded onboarding, collects the payout bank too: "Payouts" completes from it.
    const verified = await api.refreshOnboardingStripe();
    expect(verified.stripe.status).toBe('VERIFIED');
    expect(verified.form.payouts).toMatchObject({ status: 'READY', linked: true, accountLast4: '6789', bankStatus: 'new' });
    expect(verified.steps.find((s) => s.key === 'PAYOUTS')?.state).toBe('DONE');
    expect('saveOnboardingPayouts' in api).toBe(false);
    const plans = await api.getOnboardingPlans();
    expect(plans.plans.map((x) => x.code)).toEqual(['seller_essentials', 'seller_growth', 'seller_pro']);
    await api.saveOnboardingPlan({ planCode: 'seller_essentials', cadence: 'MONTHLY' });
    await expect(api.submitOnboarding()).rejects.toSatisfy((e) => isApiError(e) && e.fieldErrors['compliance.agreementsAccepted'] !== undefined);
    const c = await api.saveOnboardingCompliance({ categories: ['groceries'], foodPermit: { permitType: 'COTTAGE_FOOD', permitNumber: 'CF-1', expiresOn: '2030-01-01', documentAssetId: 'asset-doc-2' },
      agreementsAccepted: true, w9Certified: true, w9SignerName: 'Titi Adebayo' });
    expect(c.resumeStep).toBe('review');
    const submitted = await api.submitOnboarding();
    expect(submitted).toMatchObject({ status: 'PENDING_REVIEW', canResume: false });
    expect(submitted.steps.at(-1)).toMatchObject({ key: 'REVIEW', state: 'WAITING' });
  });

  it('turns AfroTransact pickup on per address and lets offers use it (S255)', async () => {
    const api = client();
    const [store, katy] = await api.listShipFrom();
    if (!store || !katy) throw new Error('no addresses');
    expect(store.position?.precision).toBe('ADDRESS');
    expect(store.afrotransactPickup).toMatchObject({ enabled: true, zoneName: 'Houston Metro' });
    await expect(api.setAfrotransactPickup(katy.id, { enabled: true, hours: [] })).rejects.toSatisfy((e) => isApiError(e) && e.code === 'PICKUP_INVALID_HOURS');
    const hours = [{ day: 'TUESDAY' as const, opens: '10:00', closes: '16:00' }];
    const on = await api.setAfrotransactPickup(katy.id, { enabled: true, hours, version: katy.version });
    expect(on.afrotransactPickup).toMatchObject({ enabled: true, hours });
    const austin = await api.createShipFrom({ name: 'Ade Okafor', line1: '500 Congress Ave', line2: '', city: 'Austin', state: 'TX', postalCode: '78701', phone: '' });
    await expect(api.setAfrotransactPickup(austin.id, { enabled: true, hours })).rejects.toSatisfy((e) => isApiError(e) && e.code === 'PICKUP_OUTSIDE_DELIVERY_AREA');
    const lost = await api.createShipFrom({ name: 'Ade Okafor', line1: '1 Nowhere Rd', line2: '', city: 'Houston', state: 'TX', postalCode: '00000', phone: '' });
    expect(lost.position).toBeNull();
    await expect(api.setAfrotransactPickup(lost.id, { enabled: true, hours })).rejects.toSatisfy((e) => isApiError(e) && e.code === 'PICKUP_NOT_GEOCODED');

    const listing = (await api.listListings({ page: 0, size: 5 })).items.find((l) => l.fulfilment === 'SELLER');
    if (!listing) throw new Error('no seller-shipped listing');
    const offers = await api.listOffers(listing.productId);
    const offer = offers.items[0];
    if (!offer) throw new Error('no offer');
    await expect(api.setOfferFulfilment(offer.offerId, { fulfilment: 'AFROTRANSACT_DELIVERY' })).rejects.toSatisfy((e) => isApiError(e) && !!e.fieldErrors.shipFromLocationId);
    await expect(api.setOfferFulfilment(offer.offerId, { fulfilment: 'AFROTRANSACT_DELIVERY', shipFromLocationId: austin.id })).rejects.toSatisfy(
      (e) => isApiError(e) && !!e.fieldErrors.shipFromLocationId
    );
    const atd = await api.setOfferFulfilment(offer.offerId, { fulfilment: 'AFROTRANSACT_DELIVERY', shipFromLocationId: store.id });
    expect(atd).toMatchObject({ fulfilment: 'AFROTRANSACT_DELIVERY', shipFromLocationId: store.id, shipsFrom: 'Houston, TX' });

    const fees = await api.getFees();
    expect(fees.current.deliveryFee).toMatchObject({ source: 'DEFAULT', summary: '$2.00 per package', maxRadiusMiles: 25 });
    const page = await api.listOrders({ limit: 100 });
    const localRow = page.items.find((r) => r.fulfilment === 'AFROTRANSACT_DELIVERY');
    if (!localRow) throw new Error('no AfroTransact Delivery order');
    expect(localRow.canBuyLabel).toBe(false);
    const order = await api.getOrder(localRow.orderNumber);
    expect(order.shipments[0]?.localDelivery?.sellerFeeMinor).toBe(200);
    expect(order.earnings?.deliveryFeesMinor).toBe(200);
  });

  it('shows Plan & billing: credit balance, rewards with progress, proration preview and a new card via SetupIntent (S292–S297)', async () => {
    const api = client();
    const billing = await api.getBilling();
    expect(billing.subscription).toMatchObject({ planVersion: 1, creditBalanceMinor: 2500, trialSource: 'PLAN' });
    const rewards = await api.getBillingRewards();
    expect(rewards.available[0]).toMatchObject({ percent: 64, label: '$640 of $1,000 in sales', rewardLabel: '1 month free' });
    expect(rewards.earned[0]).toMatchObject({ type: 'CREDIT', summary: '$25 credit' });
    // S299: applied low-sales relief says when the card is charged next.
    expect(rewards.earned.find((e) => e.comparison === 'LESS_THAN')).toMatchObject({ type: 'FREE_DAYS', status: 'APPLIED', nextChargeAt: expect.any(String) });
    expect(await api.previewPlanChange('seller_pro', 'QUARTERLY')).toMatchObject({ change: 'IMMEDIATE_UPGRADE', trialing: false });
    expect((await api.previewPlanChange('seller_pro', 'QUARTERLY')).amountDueNowMinor).toBeGreaterThan(0);
    expect(await api.previewPlanChange('seller_essentials', 'QUARTERLY')).toMatchObject({ change: 'AT_RENEWAL', amountDueNowMinor: 0 });
    const si = await api.createBillingSetupIntent();
    expect(si.localTest).toBe(true);
    await expect(api.addBillingPaymentMethod({ stripePaymentMethodId: 'card_1', kind: 'CARD', last4: '4242' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const pm = await api.addBillingPaymentMethod({ stripePaymentMethodId: 'pm_new', kind: 'CARD', brand: 'Visa', last4: '1881', expMonth: 4, expYear: 2031, makeDefault: true });
    expect(pm).toMatchObject({ label: 'Visa ending in 1881', isDefault: true });
    expect((await api.getBilling()).paymentMethods.filter((m) => m.isDefault).map((m) => m.id)).toEqual(['pm_new']);
    const finance = client(`${MOCK_ROLE_COOKIE}=seller.finance`);
    await expect(finance.getBillingRewards()).resolves.toMatchObject({ available: expect.any(Array) });
    await expect(finance.createBillingSetupIntent()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('lets owner and finance choose how the plan is paid and shows what sales paid (S298)', async () => {
    const api = client();
    const billing = await api.getBilling();
    expect(billing.planFeeCollection).toMatchObject({ available: true, mode: 'EARNINGS_FIRST', canChange: true });
    expect(billing.planFeeCollection?.appliesFromLabel).toMatch(/^Applies from your next invoice/);
    expect(billing.invoices[0]).toMatchObject({ paidFromSalesMinor: 9000, paidByCardMinor: 2115 });
    const finance = client(`${MOCK_ROLE_COOKIE}=seller.finance`);
    expect(await finance.setPlanFeeCollection('CARD')).toMatchObject({ mode: 'CARD', source: 'SELLER' });
    expect((await api.getBilling()).planFeeCollection?.mode).toBe('CARD');
    const catalog = client(`${MOCK_ROLE_COOKIE}=seller.catalog`);
    await expect(catalog.setPlanFeeCollection('EARNINGS_FIRST')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    getSellerDb().planFeeCollectionAvailable = false;
    await expect(api.setPlanFeeCollection('EARNINGS_FIRST')).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    const payouts = await api.getPayouts();
    expect(payouts.planFeeDeductions[0]).toMatchObject({ label: 'Plan fee (invoice AT-1003)', withheldMinor: 9000 });
    expect(payouts.planFeeDeductions[0]?.transfers[0]?.label).toMatch(/deducted from order/);
  });

  it('takes a trial campaign promo code on the Plan step (S295)', async () => {
    const api = client();
    getSellerDb().orgs = [];
    await api.startOnboarding('SELLER');
    await expect(api.saveOnboardingPlan({ planCode: 'seller_essentials', cadence: 'MONTHLY', promoCode: 'GROW60' })).rejects.toSatisfy(
      (e) => isApiError(e) && !!e.fieldErrors['plan.promoCode']
    );
    const saved = await api.saveOnboardingPlan({ planCode: 'seller_growth', cadence: 'MONTHLY', promoCode: 'grow60' });
    expect(saved.form.plan?.firstCharge?.sentence).toMatch(/^Free for 60 days/);
    // S298: the Plan step offers paying the plan from sales; the choice is kept with the plan.
    expect(saved.form.plan).toMatchObject({ planFeeCollection: null, planFeeCollectionAvailable: true, planFeeCollectionDefault: 'CARD' });
    const card = await api.saveOnboardingPlan({ planCode: 'seller_growth', cadence: 'MONTHLY', planFeeCollection: 'CARD' });
    expect(card.form.plan?.planFeeCollection).toBe('CARD');
  });
});
