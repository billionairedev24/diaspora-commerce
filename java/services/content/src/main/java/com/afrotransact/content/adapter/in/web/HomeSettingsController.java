package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.application.HomeSettingsService;
import com.afrotransact.content.domain.home.HomeSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Home settings", description = "Thresholds, caps, mode and department labels of the dynamic home")
@RestController
@RequestMapping("/v1/home-settings")
class HomeSettingsController {

    private final HomeSettingsService settings;

    HomeSettingsController(HomeSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "Effective home module settings (defaults merged with the stored version)")
    @GetMapping
    ResponseEntity<HomeSettings> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(settings.current());
    }

    @Operation(summary = "Save a new version (content:home.edit); a reason is mandatory and the change is audit-logged")
    @PutMapping
    HomeSettings save(@Valid @RequestBody HomeSettingsDtos.SaveRequest body) {
        return settings.save(body.expectedVersion(), body.settings(), body.reason());
    }

    @Operation(summary = "Version history with who, when, reason and the changed fields (content:home.view)")
    @GetMapping("/versions")
    List<HomeSettingsDtos.VersionDto> versions() {
        return settings.versions().stream().map(HomeSettingsDtos.VersionDto::of).toList();
    }

    @Operation(summary = "Make an earlier version current again as a new version (content:home.edit); reason mandatory")
    @PostMapping("/revert")
    HomeSettings revert(@Valid @RequestBody HomeSettingsDtos.RevertRequest body) {
        return settings.revert(body.toVersion(), body.reason());
    }
}
