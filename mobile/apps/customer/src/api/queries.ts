/* TanStack Query hooks. Keys come from the factories in ./keys (BFF keys shared with the web via api-client). */
import { type Cart, creditQueries, type ReviewInput, type SearchQuery, SearchQuerySchema } from '@afrotransact/api-client';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useApi } from './ApiProvider';
import { accountKeys, bookingKeys, cartKeys, checkoutKeys, identityKeys, orderKeys, productKeys, reviewKeys, searchKeys } from './keys';

export function searchQuery(q: string, size = 24): SearchQuery {
  return SearchQuerySchema.parse({ q, size });
}

export function useSearch(q: string, size = 24) {
  const api = useApi();
  const query = searchQuery(q, size);
  return useQuery({ queryKey: searchKeys.result(query), queryFn: ({ signal }) => api.bff.search(query, signal) });
}

export function useProduct(atin: string) {
  const api = useApi();
  return useQuery({ queryKey: productKeys.detail(atin), queryFn: ({ signal }) => api.bff.getProduct(atin, signal), enabled: !!atin });
}

export function useCart() {
  const api = useApi();
  return useQuery({ queryKey: cartKeys.current(), queryFn: ({ signal }) => api.bff.getCart(signal) });
}

/** Cart mutations write the returned cart into the cache (the BFF always answers with the whole cart). */
export function useCartActions() {
  const api = useApi();
  const qc = useQueryClient();
  const set = (c: Cart) => {
    qc.setQueryData(cartKeys.current(), c);
    void qc.invalidateQueries({ queryKey: checkoutKeys.all });
  };
  const add = useMutation({ mutationFn: (v: { offerId: string; quantity: number }) => api.bff.addToCart(v), onSuccess: set });
  const update = useMutation({ mutationFn: (v: { lineId: string; quantity: number }) => api.bff.updateCartLine(v.lineId, { quantity: v.quantity }), onSuccess: set });
  const remove = useMutation({ mutationFn: (lineId: string) => api.bff.removeCartLine(lineId), onSuccess: set });
  return { add, update, remove };
}

export function useMe() {
  const api = useApi();
  return useQuery({ queryKey: accountKeys.me(), queryFn: ({ signal }) => api.bff.getMe(signal) });
}

export function useOrders() {
  const api = useApi();
  return useQuery({ queryKey: accountKeys.orders(), queryFn: () => api.bff.getOrders() });
}

export function useReturnEligibility(orderNumber: string) {
  const api = useApi();
  return useQuery({ queryKey: [...accountKeys.returns(), 'eligibility', orderNumber], queryFn: () => api.account.returnEligibility(orderNumber), enabled: orderNumber !== '' });
}

export function useReturns() {
  const api = useApi();
  return useQuery({ queryKey: accountKeys.returns(), queryFn: () => api.bff.getReturns() });
}

export function useTracking(orderNumber: string) {
  const api = useApi();
  return useQuery({ queryKey: orderKeys.tracking(orderNumber), queryFn: () => api.account.tracking(orderNumber), enabled: !!orderNumber, refetchInterval: 30_000 });
}

export function useAddresses() {
  const api = useApi();
  return useQuery({ queryKey: accountKeys.addresses(), queryFn: () => api.bff.getAddresses() });
}

/** AfroTransact credit balance and history, newest first (S320). Read only: credit is spent at checkout, never paid out. */
export function useCreditAccount() {
  const api = useApi();
  return useInfiniteQuery(creditQueries.account(api.bff));
}

export function usePaymentMethods() {
  const api = useApi();
  return useQuery({ queryKey: accountKeys.paymentMethods(), queryFn: () => api.bff.getPaymentMethods() });
}

export function useCheckoutSummary(addressId: string | null) {
  const api = useApi();
  return useQuery({ queryKey: checkoutKeys.summary(addressId), queryFn: () => api.checkout.summary(addressId ?? undefined), retry: 0 });
}

export function useServices(q: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.services(q), queryFn: () => api.bookings.searchServices(q || undefined) });
}

export function useProvider(orgId: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.provider(orgId), queryFn: () => api.bookings.provider(orgId), enabled: !!orgId });
}

export function useServiceDays(serviceId: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.days(serviceId), queryFn: () => api.bookings.days(serviceId), enabled: !!serviceId });
}

export function useServiceSlots(serviceId: string, date: string | null) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.slots(serviceId, date ?? ''), queryFn: () => api.bookings.slots(serviceId, date ?? ''), enabled: !!serviceId && !!date });
}

export function useServiceSummary(serviceId: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.summary(serviceId), queryFn: () => api.bookings.summary(serviceId), enabled: !!serviceId });
}

export function useMyBookings() {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.mine(), queryFn: () => api.bookings.mine() });
}

export function useBooking(id: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.detail(id), queryFn: () => api.bookings.get(id), enabled: !!id });
}

export function useBookingHistory(id: string) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.history(id), queryFn: () => api.bookings.history(id), enabled: !!id });
}

export function useCancellationQuote(id: string, enabled: boolean) {
  const api = useApi();
  return useQuery({ queryKey: bookingKeys.quote(id), queryFn: () => api.bookings.quote(id), enabled: !!id && enabled, staleTime: 0 });
}

/** Can the shopper review this product (a delivered order line, not yet reviewed), and their review in any state (S324). */
export function useMyReview(atin: string, enabled: boolean) {
  const api = useApi();
  return useQuery({ queryKey: reviewKeys.mine(atin), queryFn: ({ signal }) => api.bff.getMyProductReview(atin, signal), enabled: enabled && !!atin, retry: 0 });
}

/** Write, edit within 30 days, or delete the shopper's own review; the product's rating is refreshed. */
export function useReviewActions(atin: string) {
  const api = useApi();
  const qc = useQueryClient();
  const done = () => Promise.all([qc.invalidateQueries({ queryKey: reviewKeys.mine(atin) }), qc.invalidateQueries({ queryKey: productKeys.detail(atin) })]);
  const write = useMutation({ mutationFn: (body: ReviewInput) => api.bff.writeProductReview(atin, body), onSuccess: done });
  const edit = useMutation({ mutationFn: (body: ReviewInput) => api.bff.editMyProductReview(atin, body), onSuccess: done });
  const remove = useMutation({ mutationFn: () => api.bff.deleteMyProductReview(atin), onSuccess: done });
  return { write, edit, remove };
}

export function useNotificationPreferences() {
  const api = useApi();
  return useQuery({ queryKey: identityKeys.preferences(), queryFn: () => api.identity.getPreferences() });
}
