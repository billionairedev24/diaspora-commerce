package com.afrotransact.catalog.adapter.in.web;

import java.util.List;

import com.afrotransact.catalog.application.ImageLibraryService;
import com.afrotransact.catalog.application.ImageLibraryService.AttachResult;
import com.afrotransact.catalog.application.ImageLibraryService.Usage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Image library", description = "Library images (media) matched to listings by SKU file names: attach "
        + "matching images, image usage (S310)")
@RestController
class ImageLibraryController {

    private final ImageLibraryService library;

    ImageLibraryController(ImageLibraryService library) {
        this.library = library;
    }

    @Schema(name = "AttachLibraryImagesRequest")
    record AttachRequest(@Size(max = 5000) List<String> skus) {
    }

    @Schema(name = "ImageUsageRequest")
    record UsageRequest(@Size(max = 500) List<String> urls, @Size(max = 500) List<String> skus) {
    }

    @Operation(operationId = "attachLibraryImages",
            summary = "Attach matching library images to the organisation's listings by SKU")
    @PostMapping("/v1/image-library/attach")
    AttachResult attach(@RequestBody(required = false) AttachRequest body) {
        return library.attach(body == null || body.skus() == null ? List.of() : body.skus());
    }

    @Operation(operationId = "imageUsage",
            summary = "Which of the organisation's SKUs use these image URLs, and which of these SKUs exist")
    @PostMapping("/v1/image-library/usage")
    Usage usage(@RequestBody UsageRequest body) {
        return library.usage(body.urls(), body.skus());
    }
}
