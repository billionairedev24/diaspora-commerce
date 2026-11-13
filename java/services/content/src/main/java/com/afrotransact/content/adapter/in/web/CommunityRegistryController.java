package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.application.CommunityRegistryService;
import com.afrotransact.content.domain.home.CommunityEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Community registry", description = "Canonical community keys, labels, aliases and editorial images")
@RestController
@RequestMapping("/v1/community-registry")
class CommunityRegistryController {

    private final CommunityRegistryService registry;

    CommunityRegistryController(CommunityRegistryService registry) {
        this.registry = registry;
    }

    @Operation(summary = "Registered communities (public read)")
    @GetMapping
    List<CommunityEntry> list() {
        return registry.list();
    }

    @Operation(summary = "Create or replace an entry (content:home.edit); reason mandatory, audit-logged")
    @PutMapping("/{key}")
    CommunityEntry put(@PathVariable String key, @Valid @RequestBody HomeSettingsDtos.PutCommunityRequest body) {
        return registry.put(key, body.toDomain(key), body.reason());
    }

    @Operation(summary = "Remove an entry (content:home.edit); the community then uses its raw value")
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key, @RequestParam String reason) {
        registry.delete(key, reason);
    }
}
