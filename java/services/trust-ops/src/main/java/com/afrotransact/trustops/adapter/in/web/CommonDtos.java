package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import com.afrotransact.trustops.application.SlaView;
import com.afrotransact.trustops.domain.SlaState;
import com.afrotransact.trustops.port.Page;
import io.swagger.v3.oas.annotations.media.Schema;

/** DTOs shared by every queue. */
final class CommonDtos {

    private CommonDtos() {
    }

    @Schema(description = "SLA timer: due time, ON_TRACK / AT_RISK (last quarter) / BREACHED, seconds left or overdue")
    record SlaResponse(Instant startedAt, Instant dueAt, SlaState state, long remainingSeconds, long overdueSeconds) {

        static SlaResponse of(SlaView v) {
            return v == null ? null : new SlaResponse(v.startedAt(), v.dueAt(), v.state(), v.remainingSeconds(),
                    v.overdueSeconds());
        }
    }

    @Schema(description = "A page of results")
    record PageResponse<T>(List<T> items, long total, int page, int size) {

        static <S, T> PageResponse<T> of(Page<S> p, Function<S, T> f) {
            return new PageResponse<>(p.items().stream().map(f).toList(), p.total(), p.page(), p.size());
        }
    }

    static int size(Integer size) {
        return size == null ? 50 : Math.max(1, Math.min(200, size));
    }

    static int page(Integer page) {
        return page == null ? 0 : Math.max(0, page);
    }
}
