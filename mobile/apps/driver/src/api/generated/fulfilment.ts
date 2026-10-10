// GENERATED from contracts/openapi/fulfilment.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter <app> generate
/* eslint-disable */

export interface paths {
    "/v1/centres": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List fulfilment centres (fulfilment:operate or delivery:dispatch) */
        get: operations["listCentres"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/centres/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get a fulfilment centre */
        get: operations["getCentre"];
        /**
         * Create or update a fulfilment centre (fulfilment:operate)
         * @description Use the inventory-service node id (e.g. HOU-FC1) so stock, tasks and routes line up.
         */
        put: operations["saveCentre"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/defects": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Open vehicle defects */
        get: operations["listDefects"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/defects/{defectId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Resolve a defect and unblock the route (the driver re-inspects before starting) */
        post: operations["resolveDefect"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/drivers/{driverId}/reinstate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Lift a dispatch suspension */
        post: operations["reinstateDriver"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/drivers/{driverId}/suspend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Suspend a driver now
         * @description The app is told to sign out (driver stream session.revoked, next sync signOut=true); events captured before the suspension still sync.
         */
        post: operations["suspendDriver"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Driver events as received (newest first) */
        get: operations["listDriverEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/forward-failures": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Calls to orders-service that could not be applied */
        get: operations["listForwardFailures"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/pickups/{id}/assign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Override: put a seller pickup on a route
         * @description From the pool, or from another route while it is not collected yet and its drop-off carries only this shipment (409 fulfilment.pickup-not-movable otherwise). The reason is logged (pickup assignments) and fulfilment.pickup.dispatch_assigned is published. delivery:dispatch.
         */
        post: operations["pickupAssign"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/pickups/{id}/assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Assignments of a seller pickup (auto and overrides) */
        get: operations["pickupAssignments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/pickups/{id}/driver-suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Nearest available drivers for a seller pickup
         * @description Top 5: fresh position (KNN <-> on PostGIS), access active, an ASSIGNED or IN_PROGRESS route today in the pickup's delivery zone, not blocked, vehicle inspection passed, room left; ranked by geo road distance (straight line when geo is down). delivery:dispatch.
         */
        get: operations["pickupDriverSuggestions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List routes (fulfilment:operate or delivery:dispatch) */
        get: operations["listRoutes"];
        put?: never;
        /** Create a route (optionally with shipments from the pool) */
        post: operations["createRoute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/build": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Route builder: plan the depot's waiting shipments into routes per delivery zone
         * @description Zones come from geo-service (ZIP centroid → active DELIVERY zone covering it); without a zone the ZIP is the group. One drop-off per order, seller pickups first, at most maxDropoffs drop-offs per route (default 25).
         */
        post: operations["buildRoutes"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Route with stops and packages */
        get: operations["getRoute"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/assign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Assign a driver (and vehicle)
         * @description A different vehicle needs a new inspection.
         */
        post: operations["assignRoute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a route that has not started (shipments go back to the pool) */
        post: operations["cancelRoute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/return-pickups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Add waiting return pickups to a route as RETURN_PICKUP stops (dispatch) */
        post: operations["addReturnPickupsToRoute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/shipments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Add pool shipments to a route (drop-offs; seller pickups added automatically) */
        post: operations["addShipmentsToRoute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/stops/{stopId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a stop before the route starts (its shipments go back to the pool) */
        delete: operations["removeStop"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/routes/{routeId}/stops/{stopId}/reassign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reassign a failed, retry, skipped or pending drop-off to another open route
         * @description Packages still on the old van are marked for return to the FC; the new route loads them at the FC (or picks them up at the seller when they never left). Returns the target route.
         */
        post: operations["reassignStop"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/shipments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Shipments waiting for a route */
        get: operations["listAwaitingShipments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/sos": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Open SOS alerts */
        get: operations["listSos"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/sos/{alertId}/acknowledge": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Acknowledge an SOS alert */
        post: operations["acknowledgeSos"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/dispatch/stream": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Live stream of driver events (server-sent events)
         * @description Event names: driver.event (every synced event with its outcome), driver.sos (HIGH), vehicle.defect, route.updated, stop.reassigned, shipment.cancelled, orders.forward_failed, session.revoked, driver.sos_acknowledged.
         */
        get: operations["dispatchStream"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/location": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Report my current position
         * @description Stored as the driver's last known position (PostGIS geography + time) for the nearest-driver preference of seller pickups; an older fix never overwrites a newer one. Synced events with lat/lon update it too. Positions older than DRIVER_POSITION_STALE_AFTER (10 min) are ignored. 403 like /v1/driver/me without delivery:operate.
         */
        post: operations["driverLocation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Access check after sign-in
         * @description 200 with the driver's open routes when the token grants delivery:operate. 403 fulfilment.no-driver-access (show “No driver access” with grantHint) or fulfilment.driver-access-revoked (signOut=true: sign out, keep the outbox and sync it).
         */
        get: operations["driverMe"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/routes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My open routes with stops, packages and manifest (download for offline work) */
        get: operations["driverRoutes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/routes/{routeId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One of my routes */
        get: operations["driverRoute"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/sos": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Raise an SOS now (also keep it in the outbox with the same eventId)
         * @description Dispatch is alerted immediately: fulfilment.driver.sos_raised (priority HIGH) and driver.sos on the dispatch stream. Idempotent per eventId.
         */
        post: operations["driverSos"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/stream": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My live stream (server-sent events)
         * @description session.revoked (sign out now), route.assigned, route.updated, route.unassigned, route.cancelled, route.unblocked, vehicle.defect, driver.sos.
         */
        get: operations["driverStream"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/driver/sync": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sync queued events (offline-first)
         * @description Events are applied strictly in per-device sequence order, each at most once (eventId) and in its own transaction. Per event: APPLIED, REJECTED (a rule failed; fix and queue a new event), CONFLICT (state moved on / sequence reused) or DEFERRED (an earlier sequence is missing; resend from expectedSequence). Replays return the stored outcome with replayed=true. SOS events are raised first even behind a gap. After revocation signOut=true and only events captured before revokedAt are accepted.
         */
        post: operations["driverSync"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Pick/pack queue (fulfilment:operate)
         * @description Tasks are created from orders.order.placed for FBA_AFROTRANSACT and RETAIL_1P shipments.
         */
        get: operations["listTasks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get a task */
        get: operations["getTask"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}/node": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Assign the fulfilment centre (when inventory had no reservation node) */
        post: operations["assignTaskNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}/pack": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Pack the parcel
         * @description Moves the shipment to packed in orders-service. Carrier shipments attach the carrier label (bought from carrier-gateway POST /v1/labels) for tracking.
         */
        post: operations["packTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}/pick": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Record picked units
         * @description All units ⇒ PICKED; fewer with shortReason ⇒ PICK_EXCEPTION (restart the pick to retry).
         */
        post: operations["recordPick"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}/pick/start": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start picking
         * @description Moves the shipment to confirmed in orders-service.
         */
        post: operations["startPick"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/fc/tasks/{id}/ready": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Stage at the outbound dock
         * @description Moves the shipment to ready in orders-service.
         */
        post: operations["stageTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/return-pickups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Return pickups by status and depot (dispatch) */
        get: operations["listReturnPickups"];
        put?: never;
        /**
         * Schedule a return pickup (SERVICE with delivery:returns, i.e. tax-returns; or dispatch)
         * @description One pickup per RMA: a retry returns the existing pickup with 200. The pickup waits in the pool (REQUESTED) until dispatch adds it to a route as a RETURN_PICKUP stop; the driver checks the RMA's handover code with tax-returns (POST /v1/returns/{id}/pickup) and syncs return_pickup.collected or return_pickup.failed.
         */
        post: operations["scheduleReturnPickup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/return-pickups/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One return pickup (tax-returns or dispatch) */
        get: operations["getReturnPickup"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/return-pickups/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a return pickup (tax-returns when the RMA is cancelled, or dispatch)
         * @description Cancels its RETURN_PICKUP stop unless the driver is already there (409). Idempotent.
         */
        post: operations["cancelReturnPickup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @description Create or update a fulfilment centre (id from the path) */
        CentreRequest: {
            name: string;
            /** @description FULFILMENT_CENTRE | ORIGIN_HUB | WAREHOUSE */
            kind?: string;
            /** @example America/Chicago */
            timezone?: string;
            addressLine?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            country?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            coldChain?: boolean;
            active?: boolean;
        };
        Centre: {
            id?: string;
            name?: string;
            kind?: string;
            timezone?: string;
            addressLine?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            country?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            coldChain?: boolean;
            active?: boolean;
        };
        /** @description The customer's address (from the order) */
        ReturnPickupAddress: {
            name?: string;
            phone?: string;
            line1: string;
            line2?: string;
            city?: string;
            state?: string;
            postalCode: string;
            country?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
        };
        /** @description A DRIVER_PICKUP return to collect (one per RMA) */
        ScheduleReturnPickupRequest: {
            rmaId: string;
            rmaNumber: string;
            orderId?: string;
            orderNumber?: string;
            shipmentId?: string;
            customerId?: string;
            sellerOrgId?: string;
            address: components["schemas"]["ReturnPickupAddress"];
            /** @description FC the parcel goes back to (return centre); default node otherwise */
            depotNodeId?: string;
            /**
             * Format: date
             * @description First day the customer is ready; default today
             */
            readyFrom?: string;
            /**
             * Format: date
             * @description Last day of the pickup window
             */
            collectBy?: string;
            /** Format: int32 */
            units?: number;
        };
        ReturnPickup: {
            id?: string;
            rmaId?: string;
            rmaNumber?: string;
            orderId?: string;
            orderNumber?: string;
            shipmentId?: string;
            customerId?: string;
            sellerOrgId?: string;
            name?: string;
            addressLine?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            country?: string;
            depotNodeId?: string;
            /** Format: date */
            readyFrom?: string;
            /** Format: date */
            collectBy?: string;
            /** Format: int32 */
            units?: number;
            /** @description REQUESTED | ROUTED | COLLECTED | FAILED | CANCELLED */
            status?: string;
            routeId?: string;
            stopId?: string;
            note?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        CancelReturnPickupRequest: {
            reason?: string;
        };
        /** @description Pick and pack of one FBA / Retail 1P shipment */
        FulfilmentTask: {
            id?: string;
            shipmentId?: string;
            orderId?: string;
            orderNumber?: string;
            fulfilmentType?: string;
            deliveryMethod?: string;
            nodeId?: string;
            /** @description PICK_PENDING | PICKING | PICK_EXCEPTION | PICKED | PACKED | READY | CANCELLED */
            status?: string;
            lines?: components["schemas"]["TaskLine"][];
            /** @description AfroTransact package label (ATS + 9), scanned at driver load-out */
            packageLabel?: string;
            /** Format: double */
            weightLb?: number;
            carrier?: string;
            trackingNumber?: string;
            exceptionReason?: string;
            operator?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int32 */
            version?: number;
        };
        TaskLine: {
            lineId?: string;
            variantAtin?: string;
            title?: string;
            /** Format: int32 */
            quantity?: number;
            /** Format: int32 */
            picked?: number;
            lotIds?: string;
        };
        /** @description Units picked per variant ATIN; fewer than ordered needs shortReason */
        PickRequest: {
            picked?: {
                [key: string]: number;
            };
            /** @description Lot ids picked from per variant ATIN (comma separated) */
            lots?: {
                [key: string]: string;
            };
            shortReason?: string;
        };
        /** @description Carrier shipments need the label bought from carrier-gateway */
        PackRequest: {
            /** Format: double */
            weightLb?: number;
            carrier?: string;
            trackingNumber?: string;
        };
        AssignNodeRequest: {
            nodeId: string;
        };
        /** @description Proof of delivery */
        DeliveryPayload: {
            /** @description CUSTOMER | SOMEONE_ELSE | SAFE_PLACE */
            handoverMethod?: string;
            /** @description Customer's 4-digit code (checked by orders-service, never stored) */
            handoverCode?: string;
            recipientName?: string;
            /** @description Family member, Neighbour, Roommate, Front desk / concierge, Colleague */
            relationship?: string;
            signatureAssetId?: string;
            safePlace?: string;
            /** @description Stamped with time, GPS and route id; within 150 m of the address */
            photo?: components["schemas"]["PhotoCapture"];
        };
        /** @description One queued driver action. Exactly the payload object matching the type is read: inspection.submitted → inspection; loadout.scanned / stop.scanned / return.scanned → scan; pickup.confirmed → pickup; pickup.problem → pickupProblem; customer.contacted → contact; delivery.completed → delivery; delivery.problem → problem; shift.ended → odometer; sos.raised → sos */
        DriverEvent: {
            /** @description Client-generated unique id (idempotency key), e.g. a UUID */
            eventId?: string;
            /**
             * Format: int64
             * @description Per-device sequence number starting at 1; applied strictly in order
             */
            sequence?: number;
            /** @description session.signed_in | inspection.submitted | loadout.scanned | loadout.completed | route.started | stop.arrived | stop.scanned | pickup.confirmed | pickup.problem | customer.contacted | delivery.completed | delivery.problem | return.scanned | shift.ended | sos.raised */
            type?: string;
            routeId?: string;
            stopId?: string;
            /**
             * Format: date-time
             * @description Device time of the action
             */
            capturedAt?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            note?: string;
            inspection?: components["schemas"]["InspectionPayload"];
            scan?: components["schemas"]["ScanPayload"];
            pickup?: components["schemas"]["PickupPayload"];
            pickupProblem?: components["schemas"]["PickupProblemPayload"];
            /** @description CALL | TEXT | WAIT_STARTED */
            contact?: string;
            delivery?: components["schemas"]["DeliveryPayload"];
            problem?: components["schemas"]["ProblemPayload"];
            /**
             * Format: int32
             * @description End-of-shift odometer (mi)
             */
            odometer?: number;
            /** @description BREAKDOWN | ACCIDENT | UNSAFE | MEDICAL */
            sos?: string;
        };
        /** @description Pre-trip inspection (DVIR) */
        InspectionPayload: {
            vehicleId?: string;
            /** @description PASS | FAIL per item: TYRES, BRAKES, LIGHTS (critical), MIRRORS, HORN_WIPERS, NO_LEAKS, CARGO_AREA, COLD_BOX, SAFETY_KIT, PHONE_MOUNT */
            checks?: {
                [key: string]: string;
            };
            /** Format: int32 */
            odometerMiles?: number;
            /** Format: int32 */
            fuelPercent?: number;
            /** Format: double */
            coldBoxF?: number;
            /** @description FRONT, BACK, LEFT, RIGHT */
            photos?: {
                [key: string]: components["schemas"]["PhotoCapture"];
            };
            defectNote?: string;
            certified?: boolean;
            signatureAssetId?: string;
        };
        /** @description A photo from the live in-app camera, uploaded to media-service */
        PhotoCapture: {
            /** @description media-service asset id */
            assetId?: string;
            /** @description LIVE_CAMERA (only accepted value) | GALLERY */
            source?: string;
            /** Format: date-time */
            capturedAt?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            /** Format: double */
            accuracyM?: number;
            /** @description Route id burnt into the stamp */
            stampRouteId?: string;
            /** @description SHA-256 of the image bytes, computed on the device */
            sha256?: string;
        };
        /** @description Seller pickup confirmation */
        PickupPayload: {
            /** @description Seller staff who handed over */
            handedOverBy?: string;
            /** @description Required when not every expected label was scanned */
            missingReason?: string;
        };
        PickupProblemPayload: {
            /** @description NOT_READY | CLOSED | WRONG_COUNT | DAMAGED */
            type?: string;
            /** @description WAIT | SKIP | PARTIAL */
            resolution?: string;
            note?: string;
            photo?: components["schemas"]["PhotoCapture"];
        };
        /** @description Delivery problem */
        ProblemPayload: {
            /** @description CUSTOMER_UNAVAILABLE | ACCESS_ISSUE | DAMAGED | REFUSED | MISSING | UNSAFE */
            type?: string;
            /** @description REATTEMPT | PICKUP_POINT | RETURN_TO_FC | ACCEPTED_WITH_DAMAGE | REPORT_MISSING */
            resolution?: string;
            note?: string;
            photo?: components["schemas"]["PhotoCapture"];
        };
        ScanPayload: {
            /** @description ATS + 9 characters */
            label?: string;
            /** @description CAMERA | MANUAL (typed in; flagged) */
            method?: string;
        };
        /** @description A batch of queued driver events from one device */
        SyncRequest: {
            deviceId: string;
            events: components["schemas"]["DriverEvent"][];
        };
        EventResult: {
            eventId?: string;
            /** Format: int64 */
            sequence?: number;
            outcome?: string;
            code?: string;
            message?: string;
            replayed?: boolean;
            forwardPending?: boolean;
        };
        SyncResult: {
            deviceId?: string;
            /** Format: int64 */
            lastSequence?: number;
            /** Format: int64 */
            expectedSequence?: number;
            signOut?: boolean;
            /** Format: date-time */
            revokedAt?: string;
            results?: components["schemas"]["EventResult"][];
        };
        /** @description Immediate SOS (also queue it with the same eventId) */
        SosRequest: {
            deviceId: string;
            event: components["schemas"]["DriverEvent"];
        };
        /** @description The device's current GPS fix */
        DriverLocationPing: {
            deviceId?: string;
            /** Format: double */
            lat: number;
            /** Format: double */
            lon: number;
            /** Format: double */
            accuracyM?: number;
            /**
             * Format: date-time
             * @description Device time of the fix; default now
             */
            capturedAt?: string;
        };
        /** @description The driver's last known position */
        DriverPosition: {
            driverId?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            /** Format: double */
            accuracyM?: number;
            /** Format: date-time */
            recordedAt?: string;
            /** @enum {string} */
            source?: "SYNC" | "PING";
        };
        CreateRouteRequest: {
            name?: string;
            zone?: string;
            /** Format: date */
            serviceDate: string;
            /** @example 1:00–5:00 pm */
            window?: string;
            depotNodeId: string;
            shipmentIds?: string[];
        };
        ContactAttempt: {
            /** @enum {string} */
            kind?: "CALL" | "TEXT" | "WAIT_STARTED";
            /** Format: date-time */
            at?: string;
        };
        /** @description A delivery route with stops and packages (the driver's offline manifest) */
        Route: {
            id?: string;
            name?: string;
            zone?: string;
            /** Format: date */
            serviceDate?: string;
            window?: string;
            depotNodeId?: string;
            driverId?: string;
            vehicleId?: string;
            /** @description PLANNED | ASSIGNED | IN_PROGRESS | COMPLETED | CANCELLED */
            status?: string;
            /** @description PASSED | PASSED_WITH_DEFECTS | FAILED_CRITICAL */
            inspection?: string;
            blocked?: boolean;
            blockedReason?: string;
            /** @description Perishables on board: cold box 33–40 °F, no safe place */
            perishable?: boolean;
            /** Format: int32 */
            startOdometer?: number;
            /** Format: int32 */
            endOdometer?: number;
            /** Format: date-time */
            loadOutCompletedAt?: string;
            /** Format: date-time */
            startedAt?: string;
            /** Format: date-time */
            endedAt?: string;
            /** Format: int32 */
            version?: number;
            stops?: components["schemas"]["RouteStop"][];
            packages?: components["schemas"]["RoutePackage"][];
        };
        RoutePackage: {
            id?: string;
            label?: string;
            shipmentId?: string;
            orderNumber?: string;
            /** @description FC (load-out) | SELLER (pickup stop) */
            origin?: string;
            pickupStopId?: string;
            dropoffStopId?: string;
            perishable?: boolean;
            /** @description EXPECTED | LOADED | PICKED_UP | NOT_PICKED_UP | DELIVERED | FAILED | TO_PICKUP_POINT | MISSING | RETURNED | REASSIGNED | CANCELLED */
            status?: string;
            pickupScanned?: boolean;
            dropScanned?: boolean;
            /** @description A scan was typed in by hand (flagged) */
            manualEntry?: boolean;
            /** Format: date-time */
            updatedAt?: string;
        };
        RouteStop: {
            id?: string;
            /** Format: int32 */
            sequence?: number;
            /** @description PICKUP | DROPOFF */
            kind?: string;
            name?: string;
            sellerOrgId?: string;
            orderNumber?: string;
            addressLine?: string;
            city?: string;
            postalCode?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            window?: string;
            handoverRequired?: boolean;
            safePlaceAllowed?: boolean;
            /** @description PENDING | ARRIVED | DONE | FAILED | RETRY | SKIPPED | REASSIGNED | CANCELLED */
            status?: string;
            /** Format: int32 */
            reattempts?: number;
            contacts?: components["schemas"]["ContactAttempt"][];
            partialAccepted?: boolean;
            handedOverBy?: string;
            note?: string;
            handoverMethod?: string;
            /** Format: double */
            podDistanceMeters?: number;
            reassignedTo?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        /** @description Move the stop to another open route */
        ReassignRequest: {
            targetRouteId: string;
        };
        AddShipmentsRequest: {
            shipmentIds: string[];
        };
        /** @description Return pickups to add to a route as RETURN_PICKUP stops */
        AddReturnPickupsRequest: {
            returnPickupIds: string[];
        };
        ReasonRequest: {
            reason?: string;
        };
        AssignRequest: {
            driverId: string;
            vehicleId?: string;
        };
        /** @description Route builder: group the depot's waiting shipments by delivery zone (geo-service zones, ZIP fallback) */
        BuildRoutesRequest: {
            /** Format: date */
            serviceDate: string;
            depotNodeId: string;
            /** Format: int32 */
            maxDropoffs?: number;
            window?: string;
        };
        /** @description Dispatch override of the nearest-driver assignment */
        PickupAssignRequest: {
            routeId: string;
            reason: string;
        };
        DriverAccess: {
            driverId?: string;
            status?: string;
            /** Format: date-time */
            changedAt?: string;
            reason?: string;
            source?: string;
        };
        ResolveDefectRequest: {
            routeId: string;
            note?: string;
        };
        SseEmitter: {
            /** Format: int64 */
            timeout?: number;
        };
        /** @description Access check after sign-in */
        DriverSession: {
            driverId?: string;
            access?: string;
            permissions?: string[];
            routeIds?: string[];
            /** Format: date-time */
            checkedAt?: string;
        };
        SosAlert: {
            alertId?: string;
            driverId?: string;
            routeId?: string;
            kind?: string;
            /** Format: double */
            lat?: number;
            /** Format: double */
            lon?: number;
            note?: string;
            /** Format: date-time */
            raisedAt?: string;
            /** Format: date-time */
            receivedAt?: string;
        };
        /** @description AfroTransact Delivery shipment in the dispatch pool */
        DeliveryShipment: {
            shipmentId?: string;
            orderId?: string;
            orderNumber?: string;
            sellerOrgId?: string;
            sellerName?: string;
            fulfilmentType?: string;
            origin?: string;
            nodeId?: string;
            label?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            perishable?: boolean;
            handoverRequired?: boolean;
            /** Format: int32 */
            units?: number;
            status?: string;
            routeId?: string;
            /** Format: date */
            promisedBy?: string;
            /** Format: date-time */
            createdAt?: string;
        };
        /** @description An available driver for a seller pickup, nearest first */
        DriverSuggestion: {
            driverId?: string;
            routeId?: string;
            routeName?: string;
            zone?: string;
            routeStatus?: string;
            /**
             * Format: double
             * @description PostGIS geodesic miles from the driver's last position
             */
            straightLineMiles?: number;
            /**
             * Format: double
             * @description Road miles from geo's routing provider (null when geo is down)
             */
            roadMiles?: number;
            /** Format: double */
            durationMinutes?: number;
            /** @description ROAD_GOOGLE, ROAD_OSRM, GEODESIC_DETOUR or STRAIGHT_LINE */
            distanceMethod?: string;
            /** Format: date-time */
            positionAt?: string;
            /** Format: int32 */
            packagesOnRoute?: number;
            /** Format: int32 */
            capacityLeft?: number;
        };
        DriverSuggestions: {
            shipmentId?: string;
            drivers?: components["schemas"]["DriverSuggestion"][];
        };
        /** @description One assignment of a seller pickup */
        PickupAssignment: {
            /** Format: uuid */
            id?: string;
            shipmentId?: string;
            routeId?: string;
            driverId?: string;
            /** @enum {string} */
            mode?: "AUTO_NEAREST" | "DISPATCH_OVERRIDE";
            reason?: string;
            previousRouteId?: string;
            /** Format: double */
            straightMiles?: number;
            /** Format: double */
            roadMiles?: number;
            distanceMethod?: string;
            actor?: string;
            /** Format: date-time */
            createdAt?: string;
        };
        /** @description A call to orders-service that could not be applied */
        ForwardFailure: {
            /** Format: int64 */
            id?: number;
            shipmentId?: string;
            kind?: string;
            target?: string;
            /** Format: int32 */
            attempts?: number;
            lastError?: string;
        };
        /** @description A driver event as received, with its outcome */
        DriverEventLogEntry: {
            eventId?: string;
            deviceId?: string;
            /** Format: int64 */
            sequence?: number;
            driverId?: string;
            type?: string;
            routeId?: string;
            stopId?: string;
            /** Format: date-time */
            capturedAt?: string;
            /** Format: date-time */
            receivedAt?: string;
            outcome?: string;
            code?: string;
            message?: string;
        };
        VehicleDefect: {
            id?: string;
            routeId?: string;
            vehicleId?: string;
            driverId?: string;
            critical?: boolean;
            items?: string[];
            note?: string;
            /** Format: date-time */
            reportedAt?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listCentres: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Centre"][];
                };
            };
        };
    };
    getCentre: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Centre"];
                };
            };
        };
    };
    saveCentre: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CentreRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Centre"];
                };
            };
        };
    };
    listDefects: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VehicleDefect"][];
                };
            };
        };
    };
    resolveDefect: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                defectId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveDefectRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    reinstateDriver: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                driverId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverAccess"];
                };
            };
        };
    };
    suspendDriver: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                driverId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverAccess"];
                };
            };
        };
    };
    listDriverEvents: {
        parameters: {
            query?: {
                routeId?: string;
                driverId?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverEventLogEntry"][];
                };
            };
        };
    };
    listForwardFailures: {
        parameters: {
            query?: {
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ForwardFailure"][];
                };
            };
        };
    };
    pickupAssign: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PickupAssignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    pickupAssignments: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PickupAssignment"][];
                };
            };
        };
    };
    pickupDriverSuggestions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverSuggestions"];
                };
            };
        };
    };
    listRoutes: {
        parameters: {
            query?: {
                date?: string;
                status?: string;
                driverId?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"][];
                };
            };
        };
    };
    createRoute: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRouteRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    buildRoutes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BuildRoutesRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"][];
                };
            };
        };
    };
    getRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    assignRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    cancelRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    addReturnPickupsToRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddReturnPickupsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    addShipmentsToRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddShipmentsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    removeStop: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
                stopId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    reassignStop: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
                stopId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReassignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    listAwaitingShipments: {
        parameters: {
            query?: {
                nodeId?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DeliveryShipment"][];
                };
            };
        };
    };
    listSos: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SosAlert"][];
                };
            };
        };
    };
    acknowledgeSos: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                alertId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    dispatchStream: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": components["schemas"]["SseEmitter"];
                };
            };
        };
    };
    driverLocation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DriverLocationPing"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverPosition"];
                };
            };
        };
    };
    driverMe: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DriverSession"];
                };
            };
        };
    };
    driverRoutes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"][];
                };
            };
        };
    };
    driverRoute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                routeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Route"];
                };
            };
        };
    };
    driverSos: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SosRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["EventResult"];
                };
            };
        };
    };
    driverStream: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": components["schemas"]["SseEmitter"];
                };
            };
        };
    };
    driverSync: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SyncRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SyncResult"];
                };
            };
        };
    };
    listTasks: {
        parameters: {
            query?: {
                nodeId?: string;
                status?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"][];
                };
            };
        };
    };
    getTask: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    assignTaskNode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssignNodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    packTask: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PackRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    recordPick: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PickRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    startPick: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    stageTask: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FulfilmentTask"];
                };
            };
        };
    };
    listReturnPickups: {
        parameters: {
            query?: {
                status?: "REQUESTED" | "ROUTED" | "COLLECTED" | "FAILED" | "CANCELLED";
                nodeId?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReturnPickup"][];
                };
            };
        };
    };
    scheduleReturnPickup: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ScheduleReturnPickupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReturnPickup"];
                };
            };
        };
    };
    getReturnPickup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReturnPickup"];
                };
            };
        };
    };
    cancelReturnPickup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["CancelReturnPickupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReturnPickup"];
                };
            };
        };
    };
}
