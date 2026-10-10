package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.FlagDtos.CreateFlagRequest;
import com.afrotransact.content.adapter.in.web.FlagDtos.FlagDto;
import com.afrotransact.content.adapter.in.web.FlagDtos.KillSwitchRequest;
import com.afrotransact.content.adapter.in.web.FlagDtos.UpdateFlagRequest;
import com.afrotransact.content.application.FeatureFlagService;
import com.afrotransact.content.application.FeatureFlagService.FlagChange;
import com.afrotransact.content.domain.flag.FlagTargeting;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feature flags", description = "Rollout percentages, targeting and kill switches (back office)")
@RestController
@RequestMapping("/v1/flags")
class FlagAdminController {

    private final FeatureFlagService flags;

    FlagAdminController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @Operation(summary = "List flags (content:flags.view)")
    @GetMapping
    List<FlagDto> list() {
        return flags.list().stream().map(FlagDto::of).toList();
    }

    @Operation(summary = "Get a flag (content:flags.view)")
    @GetMapping("/{key}")
    FlagDto get(@PathVariable String key) {
        return FlagDto.of(flags.get(key));
    }

    @Operation(summary = "Create a flag (content:flags.edit)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FlagDto create(@Valid @RequestBody CreateFlagRequest body) {
        return FlagDto.of(flags.create(body.key(), body.description(), Boolean.TRUE.equals(body.enabled()),
                body.rolloutPercentage() == null ? 0 : body.rolloutPercentage(),
                body.targeting() == null ? FlagTargeting.none() : body.targeting().toDomain()));
    }

    @Operation(summary = "Change a flag (content:flags.edit); takes effect everywhere within 30 s")
    @PutMapping("/{key}")
    FlagDto update(@PathVariable String key, @Valid @RequestBody UpdateFlagRequest body) {
        return FlagDto.of(flags.update(key, new FlagChange(body.description(), body.enabled(), body.rolloutPercentage(),
                Boolean.TRUE.equals(body.killSwitch()), body.targeting() == null ? FlagTargeting.none() : body.targeting().toDomain()),
                body.expectedVersion()));
    }

    @Operation(summary = "Kill switch on/off (content:flags.edit); forces the flag off within 30 s")
    @PostMapping("/{key}/kill-switch")
    FlagDto killSwitch(@PathVariable String key, @Valid @RequestBody KillSwitchRequest body) {
        return FlagDto.of(flags.killSwitch(key, body.on()));
    }
}
