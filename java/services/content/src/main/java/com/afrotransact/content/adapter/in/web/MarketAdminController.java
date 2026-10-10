package com.afrotransact.content.adapter.in.web;

import java.util.List;

import com.afrotransact.content.adapter.in.web.MarketDtos.CapabilityChangeRequest;
import com.afrotransact.content.adapter.in.web.MarketDtos.ChangesDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.CountryDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.CountrySummaryDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.PublicationDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.PublishRequest;
import com.afrotransact.content.adapter.in.web.MarketDtos.PublishResponse;
import com.afrotransact.content.adapter.in.web.MarketDtos.UpdateCountryRequest;
import com.afrotransact.content.application.MarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Markets (back office)", description = "Draft changes to countries and state capability switches, then Publish")
@RestController
@RequestMapping("/v1/markets")
class MarketAdminController {

    private final MarketService markets;

    MarketAdminController(MarketService markets) {
        this.markets = markets;
    }

    @Operation(summary = "Draft countries (content:markets.view)")
    @GetMapping("/draft/countries")
    List<CountrySummaryDto> countries() {
        return markets.draftCountries().stream().map(CountrySummaryDto::of).toList();
    }

    @Operation(summary = "Draft country with its states / provinces (content:markets.view)")
    @GetMapping("/draft/countries/{code}")
    CountryDto country(@PathVariable String code) {
        return CountryDto.of(markets.draftCountry(code));
    }

    @Operation(summary = "Change a country's status or launch note (content:markets.edit)")
    @PatchMapping("/draft/countries/{code}")
    CountryDto updateCountry(@PathVariable String code, @Valid @RequestBody UpdateCountryRequest body) {
        return CountryDto.of(markets.updateCountry(code, body.status(), body.launchNote()));
    }

    @Operation(summary = "Change a state's capability switches (content:markets.edit)")
    @PatchMapping("/draft/countries/{code}/regions/{region}")
    CountryDto setCapabilities(@PathVariable String code, @PathVariable String region,
                               @RequestBody CapabilityChangeRequest body) {
        return CountryDto.of(markets.setCapabilities(code, region, body.changes()));
    }

    @Operation(summary = "Activate all capabilities in every state of a country (content:markets.edit)")
    @PostMapping("/draft/countries/{code}/activate-all")
    CountryDto activateAll(@PathVariable String code) {
        return CountryDto.of(markets.activateAll(code));
    }

    @Operation(summary = "Deactivate all capabilities in every state of a country (content:markets.edit)")
    @PostMapping("/draft/countries/{code}/deactivate-all")
    CountryDto deactivateAll(@PathVariable String code) {
        return CountryDto.of(markets.deactivateAll(code));
    }

    @Operation(summary = "Unpublished changes (content:markets.view)")
    @GetMapping("/draft/changes")
    ChangesDto changes() {
        return ChangesDto.of(markets.pendingChanges());
    }

    @Operation(summary = "Publish the draft as a new version (content:markets.edit)")
    @PostMapping("/publications")
    @ResponseStatus(HttpStatus.CREATED)
    PublishResponse publish(@Valid @RequestBody(required = false) PublishRequest body) {
        return PublishResponse.of(markets.publish(body == null ? null : body.note()));
    }

    @Operation(summary = "Published versions (content:markets.view)")
    @GetMapping("/publications")
    List<PublicationDto> publications() {
        return markets.publications().stream().map(PublicationDto::of).toList();
    }
}
