import { z } from 'zod';

import { PreorderAttributionSchema, PreorderConfirmationSchema } from '../preorders';
import { AddressSchema, PaymentMethodSchema, ReturnSummarySchema } from './account';
import { CartPackageSchema } from './cart';
import { ProductSummarySchema } from './catalog';
import { MinorSchema } from './common';
import { RailSchema } from './home';

/* Storefront account (address input, order detail & tracking, returns, login & security), checkout, services &
   bookings and Lola — contracts/openapi/gateway-bff.yaml. */

const nullableString = z.string().nullable();

/* ── Addresses ─────────────────────────────────────────────────── */

export const AddressInputSchema = z.object({
  name: z.string().trim().min(1, 'Enter the full name'),
  phone: z.string().trim().min(7, 'Enter a phone number'),
  line1: z.string().trim().min(1, 'Enter the street address'),
  line2: z.string().optional(),
  city: z.string().trim().min(1, 'Enter the city'),
  state: z.string().regex(/^[A-Z]{2}$/, 'Choose a state'),
  zip: z.string().regex(/^\d{5}(-\d{4})?$/, 'Enter a valid ZIP code'),
  isDefault: z.boolean().optional(),
  acceptUnverified: z.boolean().optional()
});
export type AddressInput = z.infer<typeof AddressInputSchema>;

/* ── Payments ──────────────────────────────────────────────────── */

export const SetupIntentSchema = z.object({ clientSecret: z.string(), publishableKey: z.string() });
export type SetupIntent = z.infer<typeof SetupIntentSchema>;

export const PaymentSheetKeysSchema = z.object({ customerId: z.string(), ephemeralKey: z.string(), publishableKey: z.string() });
export type PaymentSheetKeys = z.infer<typeof PaymentSheetKeysSchema>;

/* ── Orders ────────────────────────────────────────────────────── */

export const DeliveryMethodSchema = z.enum(['carrier', 'express', 'afrotransact_delivery', 'pickup']);
export type DeliveryMethod = z.infer<typeof DeliveryMethodSchema>;

export const TimelineEventSchema = z.object({ status: z.string(), label: z.string(), at: z.string() });
export type TimelineEvent = z.infer<typeof TimelineEventSchema>;

export const OrderLineViewSchema = z.object({
  lineId: z.string(),
  atin: z.string(),
  title: z.string(),
  imageUrl: nullableString,
  quantity: z.number().int(),
  unitPriceMinor: MinorSchema,
  itemsMinor: MinorSchema,
  /** Refunded on this line so far (returns, cancellations, claims). */
  refundedMinor: MinorSchema.optional()
});
export type OrderLineView = z.infer<typeof OrderLineViewSchema>;

/** Where a pickup shipment is collected (order page and confirmation). */
export const OrderPickupSchema = z.object({ id: z.string(), name: z.string(), address: z.string(), hours: z.string(), ready: z.string(), setBy: z.enum(['platform', 'seller']) });
export type OrderPickup = z.infer<typeof OrderPickupSchema>;

/** How our driver handed a parcel over (photo, who received it); the links last about five minutes. */
export const DeliveryProofSchema = z.object({
  method: z.enum(['CUSTOMER', 'SOMEONE_ELSE', 'SAFE_PLACE']),
  recipientName: nullableString,
  relationship: nullableString,
  safePlace: nullableString,
  photoUrl: nullableString,
  signatureUrl: nullableString,
  at: z.string()
});
export type DeliveryProof = z.infer<typeof DeliveryProofSchema>;

export const ShipmentDetailSchema = z.object({
  shipmentId: z.string(),
  label: z.string(),
  status: z.string(),
  statusLabel: z.string(),
  items: z.string(),
  sellerName: z.string(),
  deliveryMethod: DeliveryMethodSchema,
  cancellable: z.boolean(),
  /** When Cancel stops being offered (before packing, inside the cancellation window); null when it is not offered. */
  cancellableUntil: z.string().nullable().optional(),
  carrier: nullableString,
  trackingNumber: nullableString,
  promisedFrom: nullableString,
  promisedBy: nullableString,
  deliveredAt: nullableString,
  /** How our driver handed it over; null for carrier, pickup and by-hand deliveries. */
  proof: DeliveryProofSchema.nullable().optional(),
  deliveryCode: nullableString,
  lines: z.array(OrderLineViewSchema),
  timeline: z.array(TimelineEventSchema),
  /** Returns (S148/S166): "Return items" when returnable; "Report a problem" when only that is possible. */
  returnable: z.boolean().optional(),
  returnWindowEndsAt: z.string().nullable().optional(),
  problemReportOnly: z.boolean().optional(),
  /** AfroTransact Delivery from the seller's store (S255), as charged at checkout. */
  localDelivery: z.object({ roadMiles: z.number().nullable(), durationMinutes: z.number().nullable(), chargeMinor: MinorSchema }).nullable().optional(),
  /** Pickup shipments: where to collect it. */
  pickup: OrderPickupSchema.nullable().optional()
});
export type ShipmentDetail = z.infer<typeof ShipmentDetailSchema>;

export const OrderDetailSchema = z.object({
  orderNumber: z.string(),
  placedAt: z.string(),
  status: z.string(),
  currency: z.string(),
  itemsMinor: MinorSchema,
  shippingMinor: MinorSchema,
  taxMinor: MinorSchema,
  discountMinor: MinorSchema,
  totalMinor: MinorSchema,
  shipTo: z.string(),
  shippingAddress: z.object({ name: z.string(), line1: z.string(), line2: z.string(), city: z.string(), state: z.string(), zip: z.string() }).nullable(),
  shipments: z.array(ShipmentDetailSchema),
  /** What was charged and refunded (payments); absent when payments cannot answer. */
  payment: z
    .object({ status: z.string(), capturedMinor: MinorSchema, refundedMinor: MinorSchema, refundableMinor: MinorSchema, shippingTaxMinor: MinorSchema })
    .optional()
});
export type OrderDetail = z.infer<typeof OrderDetailSchema>;

/* ── Returns ───────────────────────────────────────────────────── */

export const RETURN_REASONS = [
  'DAMAGED',
  'DEFECTIVE',
  'WRONG_ITEM',
  'NOT_AS_DESCRIBED',
  'MISSING_PARTS',
  'EXPIRED_OR_SPOILED',
  'ARRIVED_LATE',
  'NO_LONGER_NEEDED',
  'ORDERED_BY_MISTAKE',
  'BETTER_PRICE_AVAILABLE'
] as const;
export const ReturnReasonSchema = z.enum(RETURN_REASONS);
export type ReturnReason = z.infer<typeof ReturnReasonSchema>;
/** Reasons the seller is at fault for (food/perishables can only be reported with these). */
export const SELLER_FAULT_REASONS: readonly ReturnReason[] = ['DAMAGED', 'DEFECTIVE', 'WRONG_ITEM', 'EXPIRED_OR_SPOILED', 'NOT_AS_DESCRIBED', 'MISSING_PARTS', 'ARRIVED_LATE'];

export const ReturnMethodSchema = z.enum(['DROP_OFF', 'DRIVER_PICKUP', 'NO_RETURN']);
export type ReturnMethod = z.infer<typeof ReturnMethodSchema>;

export const ReturnableLineSchema = z.object({
  lineId: z.string(),
  atin: z.string(),
  title: z.string(),
  quantity: z.number().int(),
  maxQuantity: z.number().int(),
  unitPriceMinor: MinorSchema,
  eligible: z.boolean(),
  reportProblemOnly: z.boolean(),
  sellerFaultOnly: z.boolean(),
  windowEndsAt: nullableString,
  methods: z.array(ReturnMethodSchema),
  ineligibleReason: nullableString
});
export type ReturnableLine = z.infer<typeof ReturnableLineSchema>;

export const ReturnEligibilitySchema = z.object({
  orderNumber: z.string(),
  currency: z.string(),
  shipments: z.array(z.object({ shipmentId: z.string(), sellerName: z.string(), deliveredAt: nullableString, lines: z.array(ReturnableLineSchema) }))
});
export type ReturnEligibility = z.infer<typeof ReturnEligibilitySchema>;

export const StartReturnRequestSchema = z.object({
  orderNumber: z.string().min(1),
  shipmentId: z.string().min(1),
  method: ReturnMethodSchema,
  lines: z
    .array(z.object({ lineId: z.string().min(1), quantity: z.number().int().min(1), reason: ReturnReasonSchema, comment: z.string().max(500).optional() }))
    .min(1, 'Choose at least one item')
});
export type StartReturnRequest = z.infer<typeof StartReturnRequestSchema>;

export const RefundQuoteSchema = z.object({
  itemsMinor: MinorSchema,
  shippingMinor: MinorSchema,
  taxMinor: MinorSchema,
  restockingFeeMinor: MinorSchema,
  totalMinor: MinorSchema
});
export type RefundQuote = z.infer<typeof RefundQuoteSchema>;

export const ReturnDetailSchema = ReturnSummarySchema.extend({
  lines: z.array(
    z.object({ lineId: z.string(), atin: z.string(), title: z.string(), quantity: z.number().int(), unitPriceMinor: MinorSchema, reason: z.string(), outcome: nullableString })
  ),
  quote: RefundQuoteSchema.nullable()
});
export type ReturnDetail = z.infer<typeof ReturnDetailSchema>;

/* ── Login & security ──────────────────────────────────────────── */

export const LinkedAccountsSchema = z.object({
  accounts: z.array(z.object({ provider: z.string(), email: nullableString, canUnlink: z.boolean() })),
  hasPassword: z.boolean()
});
export type LinkedAccounts = z.infer<typeof LinkedAccountsSchema>;

export const SecurityOverviewSchema = z.object({
  mfa: z.object({
    totp: z.boolean(),
    sms: z.boolean(),
    smsPhone: nullableString,
    recoveryCodesLeft: z.number().int(),
    required: z.boolean(),
    mayDisable: z.boolean(),
    mayRemoveTotp: z.boolean(),
    mayRemoveSms: z.boolean()
  }),
  passkeys: z.array(z.object({ credentialId: z.string(), label: z.string(), createdAt: nullableString, lastUsedAt: nullableString })),
  trustedBrowsers: z.array(z.object({ id: z.string(), label: z.string(), createdAt: nullableString, expiresAt: nullableString })),
  sessions: z.array(z.object({ id: z.string(), clientName: z.string(), signedInAt: nullableString, lastActiveAt: nullableString, current: z.boolean() })),
  devices: z.array(z.object({ id: z.string(), platform: z.string(), deviceName: z.string(), lastSeenAt: nullableString })),
  linkedAccounts: LinkedAccountsSchema,
  passkeyRegistrationUrl: z.string()
});
export type SecurityOverview = z.infer<typeof SecurityOverviewSchema>;

export const TotpSetupSchema = z.object({ secret: z.string(), otpauthUri: z.string() });
export type TotpSetup = z.infer<typeof TotpSetupSchema>;

export const RecoveryCodesSchema = z.object({ recoveryCodes: z.array(z.string()) });
export type RecoveryCodes = z.infer<typeof RecoveryCodesSchema>;

export const NotificationPreferencesSchema = z.object({ email: z.boolean(), sms: z.boolean(), push: z.boolean(), marketingOptIn: z.boolean() });
export type NotificationPreferences = z.infer<typeof NotificationPreferencesSchema>;

export const PasswordChangeSchema = z.object({ currentPassword: z.string().max(128).optional(), newPassword: z.string().min(12, 'Use at least 12 characters').max(128) });
export type PasswordChange = z.infer<typeof PasswordChangeSchema>;

/* ── Checkout ──────────────────────────────────────────────────── */

export const PickupPointSchema = z.object({
  id: z.string(),
  name: z.string(),
  address: z.string(),
  hours: z.string(),
  ready: z.string(),
  setBy: z.enum(['platform', 'seller']),
  /** carrier-gateway kind: AfroTransact counter or locker, or the seller's own location. */
  kind: z.enum(['PLATFORM_COUNTER', 'LOCKER', 'SELLER']).optional(),
  /** Straight-line miles from the delivery address; null when either isn't located. */
  distanceMiles: z.number().nullable().optional()
});
export type PickupPoint = z.infer<typeof PickupPointSchema>;

/** Why AfroTransact Delivery from a seller's store can't be quoted for the chosen address (S255). */
export const LocalDeliveryReasonSchema = z.enum(['OUT_OF_RANGE', 'UNAVAILABLE', 'PICKUP_UNAVAILABLE', 'ADDRESS_NOT_LOCATED', 'QUOTE_UNAVAILABLE']);
export type LocalDeliveryReason = z.infer<typeof LocalDeliveryReasonSchema>;
export const CheckoutLocalDeliverySchema = z.object({
  available: z.boolean(),
  chargeMinor: MinorSchema.nullable(),
  roadMiles: z.number().nullable(),
  durationMinutes: z.number().nullable(),
  fromCity: z.string(),
  unavailableReason: LocalDeliveryReasonSchema.nullable(),
  message: nullableString,
  maxRadiusMiles: z.number().nullable(),
  offerIds: z.array(z.string())
});
export type CheckoutLocalDelivery = z.infer<typeof CheckoutLocalDeliverySchema>;

export const CheckoutPackageSchema = CartPackageSchema.extend({
  pickupPoints: z.array(PickupPointSchema).optional(),
  localDelivery: CheckoutLocalDeliverySchema.optional()
});
export type CheckoutPackage = z.infer<typeof CheckoutPackageSchema>;

export const CheckoutCouponSellerShareSchema = z.object({
  sellerOrgId: z.string(),
  sellerName: z.string().optional(),
  discountMinor: MinorSchema,
  funding: z.enum(['platform', 'seller'])
});
export type CheckoutCouponSellerShare = z.infer<typeof CheckoutCouponSellerShareSchema>;

export const CheckoutCouponSchema = z.object({
  code: z.string(),
  applicable: z.boolean(),
  rejection: z.string().nullable().optional(),
  message: z.string().optional(),
  discountMinor: MinorSchema,
  shippingDiscountMinor: MinorSchema,
  funding: z.enum(['platform', 'seller']).optional(),
  fundedByOrg: z.string().optional(),
  offerDiscounts: z
    .array(z.object({ offerId: z.string(), sellerOrgId: z.string().optional(), discountMinor: MinorSchema }))
    .optional(),
  sellerShares: z.array(CheckoutCouponSellerShareSchema).optional()
});
export type CheckoutCoupon = z.infer<typeof CheckoutCouponSchema>;

export const CheckoutSummarySchema = z.object({
  packages: z.array(CheckoutPackageSchema),
  addresses: z.array(AddressSchema),
  paymentMethods: z.array(PaymentMethodSchema),
  subtotalMinor: MinorSchema,
  shippingMinor: MinorSchema,
  discountMinor: MinorSchema.default(0),
  shippingDiscountMinor: MinorSchema.default(0),
  coupon: CheckoutCouponSchema.nullish(),
  taxMinor: MinorSchema,
  taxRateLabel: z.string(),
  totalMinor: MinorSchema,
  currency: z.string(),
  sellerCount: z.number().int(),
  publishableKey: z.string().optional(),
  stripeCustomerId: z.string().optional(),
  crossSell: RailSchema.nullish()
});
export type CheckoutSummary = z.infer<typeof CheckoutSummarySchema>;

export const DeliveryPreferencesSchema = z.object({
  handover: z.enum(['hand', 'door', 'desk']).optional(),
  safePlace: z.string().optional(),
  instructions: z.string().max(240).optional(),
  contact: z.enum(['text', 'call', 'none']).optional()
});
export type DeliveryPreferences = z.infer<typeof DeliveryPreferencesSchema>;

export const DeliverySelectionSchema = z.object({
  packageId: z.string(),
  option: DeliveryMethodSchema,
  pickupPointId: z.string().optional(),
  preferences: DeliveryPreferencesSchema.optional()
});
export type DeliverySelection = z.infer<typeof DeliverySelectionSchema>;

export const WalletSchema = z.enum(['apple_pay', 'google_pay', 'link']);
export type Wallet = z.infer<typeof WalletSchema>;

export const PlaceOrderRequestSchema = z.object({
  addressId: z.string().min(1),
  paymentMethodId: z.string().optional(),
  wallet: WalletSchema.optional(),
  /** Paid with a new method from Stripe's Payment Element (deferred intent): confirmed with the order's client secret. */
  newPaymentMethod: z.boolean().optional(),
  /** With newPaymentMethod: keep it on the shopper's Stripe Customer (setup_future_usage=off_session). */
  savePaymentMethod: z.boolean().optional(),
  gift: z.boolean().optional(),
  couponCode: z.string().max(20).optional(),
  deliverySelections: z.array(DeliverySelectionSchema),
  /** Last-touch channel (?src=&cr=&po= or the on-site pre-order placement), stored on the order (S273). */
  attribution: PreorderAttributionSchema.optional()
});
export type PlaceOrderRequest = z.infer<typeof PlaceOrderRequestSchema>;

export const OrderConfirmationSchema = z.object({
  orderNumber: z.string(),
  status: z.enum(['placed', 'awaiting_payment', 'processing', 'requires_action']),
  paymentClientSecret: nullableString.optional(),
  totalMinor: MinorSchema,
  currency: z.string(),
  shipments: z.array(z.object({ shipmentId: z.string(), label: z.string(), eta: z.string(), deliveryCode: nullableString.optional() })),
  /** Present when the order holds a pre-order package (the "You're in" confirmation). */
  preorder: PreorderConfirmationSchema.nullable().optional()
});
export type OrderConfirmation = z.infer<typeof OrderConfirmationSchema>;

/** The pay-now page of a late pre-order add (/orders/{orderNumber}/pay?token=…): AfroTransact's own page, where the
 *  customer confirms the order's existing PaymentIntent with Stripe Elements. No client secret once paid. */
export const PayLinkSchema = z.object({
  orderNumber: z.string(),
  status: z.enum(['awaiting_payment', 'paid']),
  totalMinor: MinorSchema,
  deliveryFeeMinor: MinorSchema,
  currency: z.string(),
  clientSecret: nullableString,
  publishableKey: z.string(),
  failureMessage: nullableString.optional(),
  expiresAt: nullableString.optional(),
  windowName: nullableString.optional(),
  deliversOn: nullableString.optional(),
  fulfilment: nullableString.optional(),
  lines: z.array(z.object({ title: z.string(), quantity: z.number().int(), totalMinor: MinorSchema }))
});
export type PayLink = z.infer<typeof PayLinkSchema>;

/* ── Services & bookings ───────────────────────────────────────── */

export const ServiceModeSchema = z.enum(['IN_PERSON', 'AT_HOME', 'VIRTUAL']);
export type ServiceMode = z.infer<typeof ServiceModeSchema>;

export const ServicePolicySchema = z.object({ kind: z.string(), label: z.string(), text: z.string() });
export type ServicePolicy = z.infer<typeof ServicePolicySchema>;

export const ServiceMemberSchema = z.object({ id: z.string(), displayName: z.string() });

export const ServiceListingSchema = z.object({
  serviceId: z.string(),
  orgId: z.string(),
  name: z.string(),
  providerName: z.string(),
  description: z.string(),
  category: z.string(),
  durationMinutes: z.number().int(),
  priceFromMinor: MinorSchema,
  depositMinor: MinorSchema,
  currency: z.string(),
  modes: z.array(ServiceModeSchema),
  rating: z.number(),
  ratingCount: z.number().int(),
  nextAvailable: nullableString,
  location: z.string()
});
export type ServiceListing = z.infer<typeof ServiceListingSchema>;

export const ServiceSearchSchema = z.object({ zip: z.string(), city: z.string(), services: z.array(ServiceListingSchema) });
export type ServiceSearch = z.infer<typeof ServiceSearchSchema>;

export const ServiceDetailSchema = z.object({
  id: z.string(),
  orgId: z.string(),
  providerName: z.string(),
  name: z.string(),
  description: z.string(),
  category: z.string(),
  durationMinutes: z.number().int(),
  priceMinor: MinorSchema,
  depositMinor: MinorSchema,
  balanceMinor: MinorSchema,
  currency: z.string(),
  modes: z.array(ServiceModeSchema),
  addOns: z.array(z.object({ id: z.string(), name: z.string(), priceMinor: MinorSchema, extraMinutes: z.number().int() })),
  policy: ServicePolicySchema,
  instantBooking: z.boolean(),
  team: z.array(ServiceMemberSchema)
});
export type ServiceDetail = z.infer<typeof ServiceDetailSchema>;

export const AvailableDaySchema = z.object({ date: z.string(), slots: z.number().int().min(0) });
export type AvailableDay = z.infer<typeof AvailableDaySchema>;

export const AvailableSlotSchema = z.object({ start: z.string(), end: z.string(), memberIds: z.array(z.string()) });
export type AvailableSlot = z.infer<typeof AvailableSlotSchema>;

export const BookingQuoteSchema = z.object({
  serviceId: z.string(),
  serviceName: z.string(),
  providerName: z.string(),
  durationMinutes: z.number().int(),
  priceMinor: MinorSchema,
  depositNowMinor: MinorSchema,
  balanceLaterMinor: MinorSchema,
  currency: z.string(),
  mode: z.string(),
  addOns: z.array(z.string()),
  policy: z.string(),
  policyText: z.string(),
  instantBooking: z.boolean(),
  providerAnswersWithinMinutes: z.number().int().nullable()
});
export type BookingQuote = z.infer<typeof BookingQuoteSchema>;

export const ProviderPageSchema = z.object({
  orgId: z.string(),
  displayName: z.string(),
  timeZone: z.string(),
  location: z.string(),
  policy: ServicePolicySchema,
  instantBooking: z.boolean(),
  team: z.array(ServiceMemberSchema),
  services: z.array(ServiceDetailSchema)
});
export type ProviderPage = z.infer<typeof ProviderPageSchema>;

/** A business's plain public page: made from its application, never edited as a page. */
export const BusinessPageSchema = z.object({
  slug: z.string(),
  kind: z.enum(['SELLER', 'PROVIDER']),
  name: z.string(),
  description: nullableString,
  /** #rrggbb, or null for the platform colour. */
  brandColor: nullableString,
  logoUrl: nullableString,
  contactEmail: nullableString,
  /** False until the business is approved: keep the page out of search engines. */
  indexable: z.boolean(),
  services: z.array(z.object({ serviceId: z.string(), name: z.string(), durationMinutes: z.number().int(), priceMinor: z.number().int(), currency: z.string() })),
  /** A seller's live products (at most twelve); empty for providers. */
  products: z.array(ProductSummarySchema)
});
export type BusinessPage = z.infer<typeof BusinessPageSchema>;

export const CancellationQuoteSchema = z.object({
  refundSharePercent: z.number().int(),
  depositMinor: MinorSchema,
  refundMinor: MinorSchema,
  keptMinor: MinorSchema,
  releaseHold: z.boolean(),
  policy: z.string(),
  explanation: z.string(),
  currency: z.string()
});
export type CancellationQuote = z.infer<typeof CancellationQuoteSchema>;

export const CustomerBookingSchema = z.object({
  id: z.string(),
  reference: z.string(),
  orgId: z.string(),
  status: z.string(),
  statusLabel: z.string(),
  startAt: z.string(),
  endAt: z.string(),
  proposedStartAt: nullableString,
  location: z.string(),
  notes: z.string(),
  service: z.object({
    serviceId: z.string(),
    name: z.string(),
    durationMinutes: z.number().int(),
    priceMinor: MinorSchema,
    depositMinor: MinorSchema,
    balanceMinor: MinorSchema,
    currency: z.string(),
    mode: z.string(),
    providerName: z.string(),
    memberName: z.string(),
    addOns: z.array(z.string())
  }),
  policy: ServicePolicySchema,
  payment: z.object({ state: z.string(), depositCapturedMinor: MinorSchema, refundedMinor: MinorSchema, balanceState: z.string() }),
  canCancel: z.boolean(),
  canReschedule: z.boolean(),
  canReview: z.boolean(),
  awaitingPayment: z.boolean()
});
export type CustomerBooking = z.infer<typeof CustomerBookingSchema>;

export const BookingRequestSchema = z.object({
  serviceId: z.string().min(1),
  memberId: z.string().optional(),
  startAt: z.string().min(1),
  mode: ServiceModeSchema,
  addOnIds: z.array(z.string()).max(20).optional(),
  location: z.string().max(300).optional(),
  notes: z.string().max(1000).optional()
});
export type BookingRequest = z.infer<typeof BookingRequestSchema>;

export const BookingCreatedSchema = z.object({
  booking: CustomerBookingSchema,
  payment: z.object({ clientSecret: z.string(), publishableKey: z.string() }).nullable()
});
export type BookingCreated = z.infer<typeof BookingCreatedSchema>;

export const BookingCancelledSchema = z.object({ booking: CustomerBookingSchema, applied: CancellationQuoteSchema });
export type BookingCancelled = z.infer<typeof BookingCancelledSchema>;

export const BookingReviewSchema = z.object({ bookingId: z.string(), rating: z.number().int(), text: z.string() });
export type BookingReview = z.infer<typeof BookingReviewSchema>;

/* ── Lola ──────────────────────────────────────────────────────── */

export const LolaChatRequestSchema = z.object({
  sessionId: z.string().regex(/^[A-Za-z0-9_-]{8,64}$/).optional(),
  message: z.string().trim().min(1).max(4000),
  zip: z.string().regex(/^\d{5}$/).optional(),
  language: z.string().max(16).optional()
});
export type LolaChatRequest = z.infer<typeof LolaChatRequestSchema>;

export const LolaTurnSchema = z.object({ role: z.string(), content: z.string(), at: z.string() });
export type LolaTurn = z.infer<typeof LolaTurnSchema>;

export const LolaProductCardSchema = z.looseObject({
  atin: z.string(),
  title: z.string(),
  brand: z.string().optional(),
  sellerName: z.string().optional(),
  priceMinor: z.number(),
  currency: z.string().optional(),
  inStock: z.boolean().optional(),
  lowStock: z.boolean().optional(),
  deliveryDays: z.number().optional(),
  rating: z.number().optional(),
  imageUrl: z.string().nullable().optional()
});
export type LolaProductCard = z.infer<typeof LolaProductCardSchema>;

export const LolaProposalSchema = z.object({
  id: z.string(),
  type: z.enum(['ADD_TO_CART', 'CHECKOUT', 'BOOK_SERVICE', 'START_RETURN']),
  label: z.string(),
  payload: z.record(z.string(), z.unknown()),
  requiresConfirmation: z.boolean().optional()
});
export type LolaProposal = z.infer<typeof LolaProposalSchema>;

/** One server-sent event of a Lola turn (ai-gateway, relayed by the BFF). */
export const LolaEventSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('token'), data: z.object({ text: z.string() }) }),
  z.object({ type: z.literal('tool_call'), data: z.looseObject({ id: z.string().optional(), tool: z.string(), label: z.string() }) }),
  z.object({ type: z.literal('tool_result'), data: z.looseObject({ id: z.string().optional(), tool: z.string(), kind: z.string(), data: z.unknown() }) }),
  z.object({ type: z.literal('action_proposal'), data: z.object({ proposal: LolaProposalSchema }) }),
  z.object({ type: z.literal('handoff'), data: z.looseObject({ handoffId: z.string().optional(), reason: z.string().optional(), summary: z.string().optional() }) }),
  z.object({
    type: z.literal('done'),
    data: z.looseObject({
      sessionId: z.string(),
      message: z.string(),
      products: z.array(LolaProductCardSchema).default([]),
      proposals: z.array(LolaProposalSchema).default([]),
      suggestions: z.array(z.string()).default([])
    })
  }),
  z.object({ type: z.literal('error'), data: z.looseObject({ code: z.string(), detail: z.string().optional() }) })
]);
export type LolaEvent = z.infer<typeof LolaEventSchema>;
