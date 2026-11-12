package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.identity.application.SaveTourProgressCommand;
import com.afrotransact.identity.application.TourProgressService;
import com.afrotransact.identity.application.TourProgressSnapshot;
import com.afrotransact.identity.application.TourProgressView;
import com.afrotransact.identity.domain.TourProgress.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's guided-tour progress (Seller Center, Provider Hub, staff console). Always the caller's own. */
@Tag(name = "Me", description = "The signed-in user: profile, roles, permissions and role-based landing")
@RestController
class TourProgressController {

    private final TourProgressService tours;

    TourProgressController(TourProgressService tours) {
        this.tours = tours;
    }

    @Schema(name = "TourProgress")
    record ProgressResponse(String tourId, int contentVersion, Status status, String lastStep, Instant updatedAt) {

        static ProgressResponse of(TourProgressView v) {
            return new ProgressResponse(v.tourId(), v.contentVersion(), v.status(), v.lastStep(), v.updatedAt());
        }
    }

    @Schema(name = "TourProgressList")
    record ListResponse(List<ProgressResponse> items, boolean autoStartEnabled) {

        static ListResponse of(TourProgressSnapshot s) {
            return new ListResponse(s.items().stream().map(ProgressResponse::of).toList(), s.autoStartEnabled());
        }
    }

    @Schema(name = "TourProgressRequest")
    record ProgressRequest(@NotNull Integer contentVersion, @NotNull Status status,
                           @Size(max = 64) @Schema(examples = "approve") String lastStep) {
    }

    @Schema(name = "TourProgressMergeItem")
    record MergeItem(@NotNull @Size(max = 64) @Schema(examples = "sc.orders") String tourId,
                     @NotNull Integer contentVersion, @NotNull Status status, @Size(max = 64) String lastStep) {
    }

    @Schema(name = "TourProgressMergeRequest")
    record MergeRequest(@NotNull @Size(max = 100) List<@Valid MergeItem> items) {
    }

    @Operation(summary = "Guided-tour progress of the signed-in user, and whether tours may start by themselves",
            description = "Tour ids are namespaced by app (sc.orders, st.settings). A tour with no entry has not been "
                    + "started. autoStartEnabled is the platform flag at.identity.tours.auto-start.")
    @GetMapping("/v1/me/tour-progress")
    ListResponse tourProgress() {
        return ListResponse.of(tours.snapshot());
    }

    @Operation(summary = "Save one tour's progress (idempotent upsert)",
            description = "A newer contentVersion replaces an older one; an older one is ignored; for the same version "
                    + "IN_PROGRESS never undoes COMPLETED or DISMISSED. Returns what is stored. 422 "
                    + "identity.invalid-tour-progress for a bad id, version or status.")
    @PutMapping("/v1/me/tour-progress/{tourId}")
    ProgressResponse saveTourProgress(@PathVariable String tourId, @Valid @RequestBody ProgressRequest body) {
        return ProgressResponse.of(tours.save(
                new SaveTourProgressCommand(tourId, body.contentVersion(), body.status(), body.lastStep())));
    }

    @Operation(summary = "Merge several tours' progress in one call (first sync of what the browser kept)",
            description = "Fills gaps and moves to newer versions only (what the server already holds for a version "
                    + "stands), in one transaction; at most 100 items. Returns the progress afterwards.")
    @PutMapping("/v1/me/tour-progress")
    ListResponse mergeTourProgress(@Valid @RequestBody MergeRequest body) {
        return ListResponse.of(tours.merge(body.items().stream().map(i -> new SaveTourProgressCommand(i.tourId(),
                i.contentVersion(), i.status(), i.lastStep())).toList()));
    }

    @Operation(summary = "Forget one tour's progress (idempotent)")
    @DeleteMapping("/v1/me/tour-progress/{tourId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetTourProgress(@PathVariable String tourId) {
        tours.reset(tourId);
    }

    @Operation(summary = "Reset all of the signed-in user's tour progress (idempotent)")
    @DeleteMapping("/v1/me/tour-progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetAllTourProgress() {
        tours.resetAll();
    }
}
