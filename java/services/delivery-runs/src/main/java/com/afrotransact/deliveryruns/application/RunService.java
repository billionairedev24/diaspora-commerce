package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunEconomics;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;

/** Staff: schedule runs and move them through their lifecycle (S260), economics (S265). */
public interface RunService {

    /** A DRAFT run; the route estimate is captured from geo (null when geo is unavailable). */
    Run create(RunCommand command);

    Run update(UUID id, RunCommand command);

    Run get(UUID id);

    List<Run> list(UUID locationId, Set<RunStatus> statuses, int limit);

    /** DRAFT runs only. */
    void delete(UUID id);

    Run announce(UUID id);

    Run open(UUID id);

    Run extend(UUID id, ExtendCommand command);

    Run cancel(UUID id, String reason);

    /** Closes ordering if still open and confirms, whatever the minimum (staff override). */
    Run confirm(UUID id);

    Run startCollecting(UUID id);

    Run outForDelivery(UUID id);

    Run complete(UUID id);

    /** Cost, break-even and P&amp;L of a run being planned (route from geo). */
    RunEconomics preview(EconomicsPreviewCommand command);

    /** P&amp;L of an existing run at its reserved / confirmed orders (plus a reported commission share). */
    RunEconomics economics(UUID id, Long commissionShareMinor);

    List<RunOrder> orders(UUID id, RunOrderStatus status);
}
