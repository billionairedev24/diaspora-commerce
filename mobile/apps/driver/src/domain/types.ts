/* Driver-app domain types. They mirror contracts/openapi/fulfilment.yaml (tag "Driver app") with the enums spelled out
   from java/services/fulfilment/README.md — the generated contract types (src/api/generated/fulfilment.ts) leave
   them as plain strings. */

export type CheckKey = 'TYRES' | 'BRAKES' | 'LIGHTS' | 'MIRRORS' | 'HORN_WIPERS' | 'NO_LEAKS' | 'CARGO_AREA' | 'COLD_BOX' | 'SAFETY_KIT' | 'PHONE_MOUNT';
export type CheckResult = 'PASS' | 'FAIL';
export type PhotoSide = 'FRONT' | 'BACK' | 'LEFT' | 'RIGHT';
export type ScanMethod = 'CAMERA' | 'MANUAL';
export type HandoverMethod = 'CUSTOMER' | 'SOMEONE_ELSE' | 'SAFE_PLACE';
export type ContactKind = 'CALL' | 'TEXT' | 'WAIT_STARTED';
export type DeliveryProblemType = 'CUSTOMER_UNAVAILABLE' | 'ACCESS_ISSUE' | 'DAMAGED' | 'REFUSED' | 'MISSING' | 'UNSAFE';
export type DeliveryResolution = 'REATTEMPT' | 'PICKUP_POINT' | 'RETURN_TO_FC' | 'ACCEPTED_WITH_DAMAGE' | 'REPORT_MISSING';
export type PickupProblemType = 'NOT_READY' | 'CLOSED' | 'WRONG_COUNT' | 'DAMAGED';
export type PickupResolution = 'WAIT' | 'SKIP' | 'PARTIAL';
export type SosKind = 'BREAKDOWN' | 'ACCIDENT' | 'UNSAFE' | 'MEDICAL';

/** Photo capture metadata sent with events (the photo itself is uploaded to media-service first). */
export interface PhotoCapture {
  /** media-service asset id; filled by the sync engine after upload. */
  assetId?: string;
  /** Local file of the stamped photo until it is uploaded. */
  localUri?: string;
  source: 'LIVE_CAMERA';
  capturedAt: string;
  lat: number | null;
  lon: number | null;
  accuracyM: number | null;
  stampRouteId: string;
  sha256: string;
}

export interface InspectionPayload {
  vehicleId: string;
  checks: Record<CheckKey, CheckResult>;
  odometerMiles: number;
  fuelPercent: number;
  coldBoxF?: number;
  photos: Record<PhotoSide, PhotoCapture>;
  defectNote?: string;
  certified: boolean;
  signatureAssetId?: string;
  /** Local signature image until uploaded. */
  signatureLocalUri?: string;
}

export interface DeliveryPayload {
  handoverMethod: HandoverMethod;
  handoverCode?: string;
  recipientName?: string;
  relationship?: string;
  signatureAssetId?: string;
  signatureLocalUri?: string;
  safePlace?: string;
  photo: PhotoCapture;
}

export type DriverEventBody =
  | { type: 'session.signed_in' }
  | { type: 'inspection.submitted'; inspection: InspectionPayload }
  | { type: 'loadout.scanned'; scan: { label: string; method: ScanMethod } }
  | { type: 'loadout.completed' }
  | { type: 'route.started' }
  | { type: 'stop.arrived'; stopId: string }
  | { type: 'stop.scanned'; stopId: string; scan: { label: string; method: ScanMethod } }
  | { type: 'pickup.confirmed'; stopId: string; pickup: { handedOverBy: string; missingReason?: string } }
  | { type: 'pickup.problem'; stopId: string; pickupProblem: { type: PickupProblemType; resolution: PickupResolution; note: string; photo?: PhotoCapture } }
  | { type: 'customer.contacted'; stopId: string; contact: ContactKind }
  | { type: 'delivery.completed'; stopId: string; delivery: DeliveryPayload }
  | { type: 'delivery.problem'; stopId: string; problem: { type: DeliveryProblemType; resolution: DeliveryResolution; note: string; photo?: PhotoCapture } }
  | { type: 'return.scanned'; scan: { label: string; method: ScanMethod } }
  | { type: 'shift.ended'; odometer: number }
  | { type: 'sos.raised'; sos: SosKind; note?: string };

export type DriverEventType = DriverEventBody['type'];

export type SyncOutcome = 'APPLIED' | 'REJECTED' | 'CONFLICT' | 'DEFERRED';

/** An event in the on-device outbox. */
export type QueuedEvent = DriverEventBody & {
  eventId: string;
  sequence: number;
  routeId: string;
  capturedAt: string;
  lat: number | null;
  lon: number | null;
  note?: string;
};

/* ── Route (cached for offline work) ─────────────────────────────────────── */

export type StopKind = 'PICKUP' | 'DROPOFF';
export type StopStatus = 'PENDING' | 'ARRIVED' | 'DONE' | 'FAILED' | 'RETRY' | 'SKIPPED' | 'REASSIGNED' | 'CANCELLED';
export type PackageStatus = 'EXPECTED' | 'LOADED' | 'PICKED_UP' | 'NOT_PICKED_UP' | 'DELIVERED' | 'FAILED' | 'TO_PICKUP_POINT' | 'MISSING' | 'RETURNED' | 'REASSIGNED' | 'CANCELLED';

export interface RouteStop {
  id: string;
  sequence: number;
  kind: StopKind;
  name: string;
  addressLine: string;
  city: string;
  postalCode: string;
  lat: number | null;
  lon: number | null;
  window: string;
  handoverRequired: boolean;
  safePlaceAllowed: boolean;
  status: StopStatus;
  reattempts: number;
  /** Contact attempts for the current delivery attempt (call, text, 5-minute wait). */
  contacts: Array<{ kind: ContactKind; at: string }>;
  orderNumber?: string;
  note?: string;
  /** From orders-service delivery brief (cached): instructions and contact preference. */
  brief?: { preference: 'hand' | 'door' | 'desk'; place?: string; instructions?: string; contact: 'text' | 'call' | 'none'; phoneMasked?: string };
  closedNote?: string;
}

export interface RoutePackage {
  id: string;
  label: string;
  orderNumber: string;
  description: string;
  origin: 'FC' | 'SELLER';
  pickupStopId?: string;
  dropoffStopId?: string;
  perishable: boolean;
  status: PackageStatus;
  loadScanned: boolean;
  pickupScanned: boolean;
  dropScanned: boolean;
  returnScanned: boolean;
  manualEntry: boolean;
}

export interface Route {
  id: string;
  name: string;
  zone: string;
  window: string;
  depotName: string;
  vehicleId: string;
  vehicleLabel: string;
  status: 'PLANNED' | 'ASSIGNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  inspection: 'NONE' | 'PASSED' | 'BLOCKED';
  blocked: boolean;
  perishable: boolean;
  lastOdometer: number;
  startOdometer: number | null;
  loadOutCompleted: boolean;
  ended: boolean;
  stops: RouteStop[];
  packages: RoutePackage[];
}
