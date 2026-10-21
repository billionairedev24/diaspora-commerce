import { type AddressSearchOptions } from './address-lookup';
import { createStaffFeeRatesApi } from './fee-rates';
import { type ApiClientOptions, createRequester } from './http';
import { createInventoryHubApi } from './inventory-hub';
import { type ExportJob, type ExportJobList, ExportJobListSchema, ExportJobSchema } from './schemas/exports';
import { type AddressSuggestions, AddressSuggestionsSchema, type PostalAddress, PostalAddressSchema } from './schemas/location';
import {
  type Customer360,
  Customer360Schema,
  type CustomerSearch,
  CustomerSearchSchema,
  type DeliveryQuote,
  type DeliveryQuoteRequest,
  DeliveryQuoteSchema,
  type DriverSuggestions,
  DriverSuggestionsSchema,
  type ExportRequest,
  type FeeOrgSearch,
  FeeOrgSearchSchema,
  type FeeScheduleDraft,
  type FeeScheduleView,
  FeeScheduleViewSchema,
  type FeesOverview,
  FeesOverviewSchema,
  type NotificationPreview,
  type NotificationPreviewRequest,
  NotificationPreviewSchema,
  type NotificationTemplateDetail,
  NotificationTemplateDetailSchema,
  type NotificationTemplateList,
  NotificationTemplateListSchema,
  type NotificationTemplateVersion,
  NotificationTemplateVersionSchema,
  type NotificationTestSendResult,
  NotificationTestSendResultSchema,
  type NotificationVersionDraft,
  type PickupAssignmentList,
  PickupAssignmentListSchema,
  type PickupAssignRequest,
  type PickupAssignResult,
  PickupAssignResultSchema,
  type PickupList,
  PickupListSchema,
  type RecordDetail,
  RecordDetailSchema,
  type RecordValues,
  type ResourceKey,
  type ResourcePage,
  ResourcePageSchema,
  type ShipFromLocation,
  ShipFromLocationSchema,
  type StaffAssistantChatRequest,
  type StaffAssistantReply,
  StaffAssistantReplySchema,
  type StaffAssistantStatus,
  StaffAssistantStatusSchema,
  type StaffDashboard,
  StaffDashboardSchema,
  type StaffSession,
  StaffSessionSchema,
  type SupportActionResult,
  SupportActionResultSchema,
  type SupportRefundRequest,
  type TaxRatesImportResult,
  TaxRatesImportResultSchema} from './schemas/staff';
import { createStaffMarketsApi } from './staff-markets';
import { createStaffPlansApi } from './staff-plans';
import { createStaffPreordersApi } from './staff-preorders';
import { tableSearchParams } from './table-query';

/** Server-driven table query: one search over every column, the sort (`<column>,asc|desc`), page or cursor, size. */
export interface ResourceQuery {
  q?: string;
  status?: string;
  sort?: string[];
  page: number;
  size: number;
  cursor?: string;
}

const enc = encodeURIComponent;

/** Typed client for the staff console routes (contracts/openapi/gateway-bff-staff.yaml). */
export function createStaffApiClient(options: ApiClientOptions) {
  const request = createRequester(options);
  const idem = (key?: string): Record<string, string> | undefined => (key ? { 'Idempotency-Key': key } : undefined);
  const record = (resource: ResourceKey, id: string) => `/v1/staff/resources/${enc(resource)}/${enc(id)}`;

  return {
    /** Address suggestions (geo service: Google Places / OSM), the same search the storefront uses. */
    searchAddresses: (q: string, signal?: AbortSignal, options: AddressSearchOptions = {}): Promise<AddressSuggestions> =>
      request('/v1/geo/address-suggestions', AddressSuggestionsSchema, { query: { q, sessionToken: options.sessionToken, country: options.country }, signal }),
    resolveAddressSuggestion: (id: string, sessionToken?: string, signal?: AbortSignal): Promise<PostalAddress> =>
      request(`/v1/geo/address-suggestions/${encodeURIComponent(id)}`, PostalAddressSchema, { query: { sessionToken }, signal }),
    /* Shell & dashboard */
    getSession: (signal?: AbortSignal): Promise<StaffSession> => request('/v1/staff/session', StaffSessionSchema, { signal }),
    getDashboard: (rangeDays: number, signal?: AbortSignal): Promise<StaffDashboard> =>
      request('/v1/staff/dashboard', StaffDashboardSchema, { query: { rangeDays: String(rangeDays) }, signal }),

    /* Lola (read-only) */
    getAssistantStatus: (signal?: AbortSignal): Promise<StaffAssistantStatus> => request('/v1/staff/assistant', StaffAssistantStatusSchema, { signal }),
    askAssistant: (body: StaffAssistantChatRequest): Promise<StaffAssistantReply> =>
      request('/v1/staff/assistant/chat', StaffAssistantReplySchema, { method: 'POST', body }),

    /* Resources */
    listResource: (resource: ResourceKey, q: ResourceQuery, signal?: AbortSignal): Promise<ResourcePage> =>
      request(`/v1/staff/resources/${enc(resource)}`, ResourcePageSchema, {
        query: tableSearchParams({ q: q.q, status: q.status, page: q.page, size: q.size, cursor: q.cursor }, q.sort),
        signal
      }),
    getRecord: (resource: ResourceKey, id: string, signal?: AbortSignal): Promise<RecordDetail> => request(record(resource, id), RecordDetailSchema, { signal }),
    createRecord: (resource: ResourceKey, values: RecordValues, key?: string): Promise<RecordDetail> =>
      request(`/v1/staff/resources/${enc(resource)}`, RecordDetailSchema, { method: 'POST', body: { values }, headers: idem(key) }),
    updateRecord: (resource: ResourceKey, id: string, values: RecordValues, version: string | null): Promise<RecordDetail> =>
      request(record(resource, id), RecordDetailSchema, { method: 'PATCH', body: { values, version } }),
    runAction: (resource: ResourceKey, id: string, action: string, values: RecordValues, key?: string): Promise<RecordDetail> =>
      request(`${record(resource, id)}/actions/${enc(action)}`, RecordDetailSchema, { method: 'POST', body: { values }, headers: idem(key) }),
    addNote: (resource: ResourceKey, id: string, text: string): Promise<RecordDetail> =>
      request(`${record(resource, id)}/notes`, RecordDetailSchema, { method: 'POST', body: { text } }),

    /* Exports */
    listExports: (signal?: AbortSignal): Promise<ExportJobList> => request('/v1/staff/exports', ExportJobListSchema, { signal }),
    createExport: (body: ExportRequest, key?: string): Promise<ExportJob> => request('/v1/staff/exports', ExportJobSchema, { method: 'POST', body, headers: idem(key) }),
    getExport: (id: string, signal?: AbortSignal): Promise<ExportJob> => request(`/v1/staff/exports/${enc(id)}`, ExportJobSchema, { signal }),

    /* Support console */
    searchCustomers: (q: string, signal?: AbortSignal): Promise<CustomerSearch> =>
      request('/v1/staff/support/customers', CustomerSearchSchema, { query: { q }, signal }),
    getCustomer: (customerId: string, signal?: AbortSignal): Promise<Customer360> =>
      request(`/v1/staff/support/customers/${enc(customerId)}`, Customer360Schema, { signal }),
    refund: (body: SupportRefundRequest, key: string): Promise<SupportActionResult> =>
      request('/v1/staff/support/refunds', SupportActionResultSchema, { method: 'POST', body, headers: idem(key) }),
    resendNotification: (notificationId: string, customerId: string, key?: string): Promise<SupportActionResult> =>
      request(`/v1/staff/support/notifications/${enc(notificationId)}/resend`, SupportActionResultSchema, { method: 'POST', body: { customerId }, headers: idem(key) }),
    cancelShipment: (shipmentId: string, body: { customerId: string; orderNumber: string; reason: string }, key?: string): Promise<SupportActionResult> =>
      request(`/v1/staff/support/shipments/${enc(shipmentId)}/cancel`, SupportActionResultSchema, { method: 'POST', body, headers: idem(key) }),

    /* Fees & commissions */
    getFees: (signal?: AbortSignal): Promise<FeesOverview> => request('/v1/staff/fees', FeesOverviewSchema, { signal }),
    searchFeeOrgs: (q: string, signal?: AbortSignal): Promise<FeeOrgSearch> => request('/v1/staff/fees/orgs', FeeOrgSearchSchema, { query: { q }, signal }),
    createFeeSchedule: (body: FeeScheduleDraft, key?: string): Promise<FeeScheduleView> =>
      request('/v1/staff/fees/schedules', FeeScheduleViewSchema, { method: 'POST', body, headers: idem(key) }),
    cancelFeeSchedule: (id: string, reason: string): Promise<FeeScheduleView> =>
      request(`/v1/staff/fees/schedules/${enc(id)}/cancel`, FeeScheduleViewSchema, { method: 'POST', body: { reason } }),
    quoteDelivery: (body: DeliveryQuoteRequest, signal?: AbortSignal): Promise<DeliveryQuote> =>
      request('/v1/staff/fees/delivery-quote', DeliveryQuoteSchema, { method: 'POST', body, signal }),
    importTaxRates: (csv: string): Promise<TaxRatesImportResult> =>
      request('/v1/staff/tax-rates/import', TaxRatesImportResultSchema, { method: 'POST', body: { csv } }),

    /* Dispatch: seller pickups (S255) */
    listPickups: (nodeId?: string, signal?: AbortSignal): Promise<PickupList> =>
      request('/v1/staff/dispatch/pickups', PickupListSchema, { query: { nodeId: nodeId || undefined }, signal }),
    getPickupSuggestions: (shipmentId: string, signal?: AbortSignal): Promise<DriverSuggestions> =>
      request(`/v1/staff/dispatch/pickups/${enc(shipmentId)}/driver-suggestions`, DriverSuggestionsSchema, { signal }),
    assignPickup: (shipmentId: string, body: PickupAssignRequest): Promise<PickupAssignResult> =>
      request(`/v1/staff/dispatch/pickups/${enc(shipmentId)}/assign`, PickupAssignResultSchema, { method: 'POST', body }),
    getPickupAssignments: (shipmentId: string, signal?: AbortSignal): Promise<PickupAssignmentList> =>
      request(`/v1/staff/dispatch/pickups/${enc(shipmentId)}/assignments`, PickupAssignmentListSchema, { signal }),
    getShipFromLocation: (locationId: string, signal?: AbortSignal): Promise<ShipFromLocation> =>
      request(`/v1/staff/ship-from-locations/${enc(locationId)}`, ShipFromLocationSchema, { signal }),

    /* Notifications → Templates (S268) */
    listNotificationTemplates: (q?: string, signal?: AbortSignal): Promise<NotificationTemplateList> =>
      request('/v1/staff/notifications/templates', NotificationTemplateListSchema, { query: { q: q || undefined }, signal }),
    getNotificationTemplate: (key: string, signal?: AbortSignal): Promise<NotificationTemplateDetail> =>
      request(`/v1/staff/notifications/templates/${enc(key)}`, NotificationTemplateDetailSchema, { signal }),
    previewNotificationTemplate: (key: string, body: NotificationPreviewRequest, signal?: AbortSignal): Promise<NotificationPreview> =>
      request(`/v1/staff/notifications/templates/${enc(key)}/preview`, NotificationPreviewSchema, { method: 'POST', body, signal }),
    createNotificationTemplateVersion: (key: string, body: NotificationVersionDraft): Promise<NotificationTemplateVersion> =>
      request(`/v1/staff/notifications/templates/${enc(key)}/versions`, NotificationTemplateVersionSchema, { method: 'POST', body }),
    testSendNotificationTemplate: (key: string, body: NotificationPreviewRequest): Promise<NotificationTestSendResult> =>
      request(`/v1/staff/notifications/templates/${enc(key)}/test-send`, NotificationTestSendResultSchema, { method: 'POST', body }),

    /* Settings → Markets & locations: /v1/staff/markets/** (location tree) and /v1/staff/places (gazetteer) */
    markets: createStaffMarketsApi(request),

    /* Plans & billing (S292–S297): /v1/staff/billing/** (catalogue, trials, rewards, grants, per-seller panel) */
    plans: createStaffPlansApi(request),
    feeRates: createStaffFeeRatesApi(request),

    /* Pre-orders (S274): /v1/staff/preorders/** mirrors delivery-runs' staff API */
    preorders: createStaffPreordersApi(request, options),

    /* Inventory hub and AfroTransact Retail (1P) (S281): /v1/staff/inventory/** and /v1/staff/retail/** */
    inventory: createInventoryHubApi(request, options)
  };
}

export type StaffApiClient = ReturnType<typeof createStaffApiClient>;
