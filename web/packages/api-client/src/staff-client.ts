import { type ApiClientOptions, createRequester } from './http';
import {
  type Customer360,
  Customer360Schema,
  type CustomerSearch,
  CustomerSearchSchema,
  type ExportJob,
  type ExportJobList,
  ExportJobListSchema,
  ExportJobSchema,
  type ExportRequest,
  type RecordDetail,
  RecordDetailSchema,
  type RecordValues,
  type ResourceKey,
  type ResourcePage,
  ResourcePageSchema,
  type StaffDashboard,
  StaffDashboardSchema,
  type StaffSession,
  StaffSessionSchema,
  type SupportActionResult,
  SupportActionResultSchema,
  type SupportRefundRequest
} from './schemas/staff';

/** Server-driven table query (page-based, or cursor-based where the upstream pages with cursors). */
export interface ResourceQuery {
  q?: string;
  status?: string;
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
    /* Shell & dashboard */
    getSession: (signal?: AbortSignal): Promise<StaffSession> => request('/v1/staff/session', StaffSessionSchema, { signal }),
    getDashboard: (rangeDays: number, signal?: AbortSignal): Promise<StaffDashboard> =>
      request('/v1/staff/dashboard', StaffDashboardSchema, { query: { rangeDays: String(rangeDays) }, signal }),

    /* Resources */
    listResource: (resource: ResourceKey, q: ResourceQuery, signal?: AbortSignal): Promise<ResourcePage> =>
      request(`/v1/staff/resources/${enc(resource)}`, ResourcePageSchema, {
        query: { q: q.q, status: q.status, page: String(q.page), size: String(q.size), cursor: q.cursor },
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
      request(`/v1/staff/support/shipments/${enc(shipmentId)}/cancel`, SupportActionResultSchema, { method: 'POST', body, headers: idem(key) })
  };
}

export type StaffApiClient = ReturnType<typeof createStaffApiClient>;
