package com.afrotransact.catalog.adapter.in.web;

import java.util.List;

import com.afrotransact.catalog.application.ImageQualityService;
import com.afrotransact.catalog.application.ImageQualityService.HideResult;
import com.afrotransact.catalog.application.ImageQualityService.Report;
import com.afrotransact.catalog.application.ImageQualityService.Task;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Image quality", description = "Existing listings against the enforced main-image rules: the Improve your "
        + "image task for sellers, the staff report and the hide step (S392)")
@RestController
class ImageQualityController {

    private final ImageQualityService quality;

    ImageQualityController(ImageQualityService quality) {
        this.quality = quality;
    }

    @Schema(name = "ImageTaskList")
    record ImageTaskList(List<Task> items) {
    }

    @Schema(name = "ImageRescoreResult", description = "How many listings the batch scored")
    record RescoreResult(int scored) {
    }

    @Operation(operationId = "listImageTasks", summary = "My Improve your image tasks",
            description = "The organisation's live listings whose main image passes with a warning (IMPROVE) or breaks a rule "
                    + "(FAIL), failing ones first, with the reasons in plain words and, for failing ones, the end of the grace "
                    + "period. Live listings stay live; after the grace period they are limited only when the owner has "
                    + "switched the hide step on. Nothing is deleted and the listing can still be edited; a new main image "
                    + "must pass the rules.")
    @GetMapping("/v1/products/image-tasks")
    ImageTaskList tasks() {
        return new ImageTaskList(quality.myTasks());
    }

    @Operation(operationId = "imageQualityReport", summary = "Image quality report (staff, catalog:moderate)",
            description = "Listings per state, how many are not scored yet, the reasons, the failing listings and how many "
                    + "the hide step would limit now (a dry run: read it on the seeded catalog before the hide flag is "
                    + "enabled).")
    @GetMapping("/v1/image-quality/report")
    Report report() {
        return quality.report();
    }

    @Operation(operationId = "rescoreImagesNow", summary = "Score the next batch of listings now (staff, catalog:moderate)",
            description = "The back-fill job runs by itself (a batch per interval, resumable); this runs one batch at once.")
    @PostMapping("/v1/image-quality/rescore")
    RescoreResult rescore() {
        return new RescoreResult(quality.rescoreNow());
    }

    @Operation(operationId = "hideFailingImages", summary = "The hide step (staff, catalog:moderate)",
            description = "With dryRun=true (the default) only counts the failing listings past the grace period. Without it "
                    + "the step runs only when at.catalog.images.hide-failing is on (a separate flag, off until the owner "
                    + "switches it on); it never deletes anything.")
    @PostMapping("/v1/image-quality/hide")
    HideResult hide(@RequestParam(defaultValue = "true") boolean dryRun) {
        return quality.hideFailing(dryRun);
    }
}
