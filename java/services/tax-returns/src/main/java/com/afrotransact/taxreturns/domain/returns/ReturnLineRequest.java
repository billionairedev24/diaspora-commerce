package com.afrotransact.taxreturns.domain.returns;

import java.util.List;

/** One line of a return request: quantity, reason, optional comment and evidence photos (media asset ids). */
public record ReturnLineRequest(String lineId, int quantity, ReturnReason reason, String comment,
                                List<String> evidenceAssetIds) {

    public ReturnLineRequest {
        evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds);
    }
}
