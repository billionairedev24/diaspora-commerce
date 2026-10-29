import { keepPreviousData, queryOptions } from '@tanstack/react-query';

import { type LookupKind, type NotificationPreviewRequest, type ResourceKey, type RunDispatchStatus } from '../schemas/staff';
import { type ResourceQuery, type StaffApiClient } from '../staff-client';

/* TanStack Query key factories for the staff console. Everything hangs off ['staff'] so signing out (or a role change)
   invalidates the whole family; each resource has its own branch so an action refreshes its table and sheet only. */

export const staffKeys = {
  all: ['staff'] as const,
  session: () => [...staffKeys.all, 'session'] as const,
  dashboard: (rangeDays: number) => [...staffKeys.all, 'dashboard', rangeDays] as const,
  resource: (resource: ResourceKey) => [...staffKeys.all, 'resource', resource] as const,
  page: (resource: ResourceKey, q: ResourceQuery) => [...staffKeys.resource(resource), 'page', q] as const,
  record: (resource: ResourceKey, id: string) => [...staffKeys.resource(resource), 'record', id] as const,
  exports: () => [...staffKeys.all, 'exports'] as const,
  exportJob: (id: string) => [...staffKeys.exports(), id] as const,
  customers: (q: string) => [...staffKeys.all, 'support', 'customers', q] as const,
  customer: (id: string) => [...staffKeys.all, 'support', 'customer', id] as const,
  identityDocuments: () => [...staffKeys.all, 'identity', 'documents'] as const,
  fees: () => [...staffKeys.all, 'fees'] as const,
  feeOrgs: (q: string) => [...staffKeys.fees(), 'orgs', q] as const,
  lookup: (kind: string, q: string) => [...staffKeys.all, 'lookup', kind, q] as const,
  deliveryQuote: (orgId: string, miles: number, packages: number) => [...staffKeys.fees(), 'delivery-quote', orgId, miles, packages] as const,
  pickups: () => [...staffKeys.all, 'dispatch', 'pickups'] as const,
  pickupList: (nodeId: string) => [...staffKeys.pickups(), 'list', nodeId] as const,
  pickup: (shipmentId: string) => [...staffKeys.pickups(), 'pickup', shipmentId] as const,
  pickupSuggestions: (shipmentId: string) => [...staffKeys.pickup(shipmentId), 'suggestions'] as const,
  pickupAssignments: (shipmentId: string) => [...staffKeys.pickup(shipmentId), 'assignments'] as const,
  runs: () => [...staffKeys.all, 'dispatch', 'runs'] as const,
  runList: (status: string) => [...staffKeys.runs(), 'list', status] as const,
  run: (runId: string) => [...staffKeys.runs(), 'run', runId] as const,
  templates: () => [...staffKeys.all, 'notifications', 'templates'] as const,
  templateList: (q: string) => [...staffKeys.templates(), 'list', q] as const,
  template: (key: string) => [...staffKeys.templates(), 'template', key] as const,
  templatePreview: (key: string, body: NotificationPreviewRequest) => [...staffKeys.template(key), 'preview', body] as const
};

const MINUTE = 60_000;

/** queryOptions for every staff screen (same keys, fetchers and stale times on server and client). */
export const staffQueries = {
  session: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.session(), queryFn: ({ signal }) => api.getSession(signal), staleTime: MINUTE }),
  dashboard: (api: StaffApiClient, rangeDays: number) =>
    queryOptions({ queryKey: staffKeys.dashboard(rangeDays), queryFn: ({ signal }) => api.getDashboard(rangeDays, signal), staleTime: MINUTE }),
  page: (api: StaffApiClient, resource: ResourceKey, q: ResourceQuery) =>
    queryOptions({
      queryKey: staffKeys.page(resource, q),
      queryFn: ({ signal }) => api.listResource(resource, q, signal),
      placeholderData: keepPreviousData,
      staleTime: 15_000
    }),
  record: (api: StaffApiClient, resource: ResourceKey, id: string) =>
    queryOptions({ queryKey: staffKeys.record(resource, id), queryFn: ({ signal }) => api.getRecord(resource, id, signal), staleTime: 10_000 }),
  exports: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.exports(), queryFn: ({ signal }) => api.listExports(signal), staleTime: 5_000 }),
  exportJob: (api: StaffApiClient, id: string) =>
    queryOptions({ queryKey: staffKeys.exportJob(id), queryFn: ({ signal }) => api.getExport(id, signal), staleTime: 0 }),
  customers: (api: StaffApiClient, q: string) =>
    queryOptions({ queryKey: staffKeys.customers(q), queryFn: ({ signal }) => api.searchCustomers(q, signal), staleTime: 30_000 }),
  customer: (api: StaffApiClient, id: string) =>
    queryOptions({ queryKey: staffKeys.customer(id), queryFn: ({ signal }) => api.getCustomer(id, signal), staleTime: 15_000 }),
  identityDocuments: (api: StaffApiClient) =>
    queryOptions({ queryKey: staffKeys.identityDocuments(), queryFn: ({ signal }) => api.getIdentityDocuments(signal), staleTime: 60_000 }),
  fees: (api: StaffApiClient) => queryOptions({ queryKey: staffKeys.fees(), queryFn: ({ signal }) => api.getFees(signal), staleTime: 15_000 }),
  lookup: (api: StaffApiClient, kind: LookupKind, q: string) =>
    queryOptions({ queryKey: staffKeys.lookup(kind, q), queryFn: ({ signal }) => api.lookup(kind, q, signal), staleTime: 30_000 }),
  feeOrgs: (api: StaffApiClient, q: string) =>
    queryOptions({ queryKey: staffKeys.feeOrgs(q), queryFn: ({ signal }) => api.searchFeeOrgs(q, signal), staleTime: 30_000 }),
  deliveryQuote: (api: StaffApiClient, orgId: string, miles: number, packages: number) =>
    queryOptions({
      queryKey: staffKeys.deliveryQuote(orgId, miles, packages),
      queryFn: ({ signal }) => api.quoteDelivery({ sellerOrgId: orgId, distanceMiles: miles, packages }, signal),
      staleTime: 30_000
    }),
  pickups: (api: StaffApiClient, nodeId = '') =>
    queryOptions({ queryKey: staffKeys.pickupList(nodeId), queryFn: ({ signal }) => api.listPickups(nodeId, signal), staleTime: 10_000 }),
  pickupSuggestions: (api: StaffApiClient, shipmentId: string) =>
    queryOptions({ queryKey: staffKeys.pickupSuggestions(shipmentId), queryFn: ({ signal }) => api.getPickupSuggestions(shipmentId, signal), staleTime: 10_000 }),
  pickupAssignments: (api: StaffApiClient, shipmentId: string) =>
    queryOptions({ queryKey: staffKeys.pickupAssignments(shipmentId), queryFn: ({ signal }) => api.getPickupAssignments(shipmentId, signal), staleTime: 10_000 }),
  runs: (api: StaffApiClient, status: RunDispatchStatus | '' = '') =>
    queryOptions({ queryKey: staffKeys.runList(status), queryFn: ({ signal }) => api.listRuns(status || undefined, signal), staleTime: 10_000, refetchInterval: 30_000 }),
  run: (api: StaffApiClient, runId: string) =>
    queryOptions({ queryKey: staffKeys.run(runId), queryFn: ({ signal }) => api.getRun(runId, signal), staleTime: 10_000, refetchInterval: 30_000 }),
  templates: (api: StaffApiClient, q = '') =>
    queryOptions({ queryKey: staffKeys.templateList(q), queryFn: ({ signal }) => api.listNotificationTemplates(q, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  template: (api: StaffApiClient, key: string) =>
    queryOptions({ queryKey: staffKeys.template(key), queryFn: ({ signal }) => api.getNotificationTemplate(key, signal), staleTime: 15_000 }),
  /** Live preview: keyed by the whole request, so edits (debounced by the caller) render once each and stay cached. */
  templatePreview: (api: StaffApiClient, key: string, body: NotificationPreviewRequest) =>
    queryOptions({
      queryKey: staffKeys.templatePreview(key, body),
      queryFn: ({ signal }) => api.previewNotificationTemplate(key, body, signal),
      placeholderData: keepPreviousData,
      staleTime: 60_000,
      retry: false
    })
};
