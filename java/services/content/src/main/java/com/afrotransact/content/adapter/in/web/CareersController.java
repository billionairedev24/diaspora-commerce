package com.afrotransact.content.adapter.in.web;

import java.time.Duration;
import java.util.List;

import com.afrotransact.content.adapter.in.web.CareersDtos.ApplicationDto;
import com.afrotransact.content.adapter.in.web.CareersDtos.ApplicationRequest;
import com.afrotransact.content.adapter.in.web.CareersDtos.CreateJobRequest;
import com.afrotransact.content.adapter.in.web.CareersDtos.JobDto;
import com.afrotransact.content.adapter.in.web.CareersDtos.JobRequest;
import com.afrotransact.content.adapter.in.web.CareersDtos.JobsResponse;
import com.afrotransact.content.adapter.in.web.CareersDtos.ReceiptDto;
import com.afrotransact.content.application.CareersService;
import com.afrotransact.content.application.CareersService.ApplicationReceipt;
import com.afrotransact.content.application.CareersService.JobListing;
import com.afrotransact.content.domain.careers.ApplicationDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Careers", description = "Open roles, applications and role management")
@RestController
@RequestMapping("/v1/careers")
class CareersController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final CareersService careers;

    CareersController(CareersService careers) {
        this.careers = careers;
    }

    @Operation(summary = "Open roles, filterable by team and location")
    @SecurityRequirements
    @GetMapping("/jobs")
    ResponseEntity<JobsResponse> jobs(@RequestParam(required = false) String team,
                                      @RequestParam(required = false) String location) {
        JobListing l = careers.openJobs(team, location);
        return ResponseEntity.ok().cacheControl(SHORT)
                .body(new JobsResponse(l.jobs().stream().map(JobDto::of).toList(), l.teams(), l.locations()));
    }

    @Operation(summary = "An open role")
    @SecurityRequirements
    @GetMapping("/jobs/{id}")
    ResponseEntity<JobDto> job(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(SHORT).body(JobDto.of(careers.openJob(id)));
    }

    @Operation(summary = "Apply (résumé uploaded beforehand through media-service); returns a CAR-###### reference")
    @SecurityRequirements
    @PostMapping("/jobs/{id}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    ReceiptDto apply(@PathVariable String id, @RequestBody ApplicationRequest body) {
        ApplicationReceipt r = careers.apply(id, new ApplicationDraft(body.name(), body.email(), body.linkedin(),
                body.workAuthorization(), body.resumeAssetId(), body.resumeFileName(), body.coverNote(), body.consent()));
        return new ReceiptDto(r.reference(), r.jobId(), r.submittedAt());
    }

    @Operation(summary = "Every role including drafts and closed ones (content:careers.view)")
    @GetMapping("/admin/jobs")
    List<JobDto> allJobs() {
        return careers.allJobs().stream().map(JobDto::of).toList();
    }

    @Operation(summary = "Create a role (content:careers.edit)")
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    JobDto create(@Valid @RequestBody CreateJobRequest body) {
        return JobDto.of(careers.createJob(body.job().toDomain(body.id())));
    }

    @Operation(summary = "Change, open or close a role (content:careers.edit)")
    @PutMapping("/jobs/{id}")
    JobDto update(@PathVariable String id, @Valid @RequestBody JobRequest body) {
        return JobDto.of(careers.updateJob(id, body.toDomain(id)));
    }

    @Operation(summary = "Applications, newest first (content:careers.view)")
    @GetMapping("/applications")
    List<ApplicationDto> applications(@RequestParam(required = false) String jobId) {
        return careers.applications(jobId).stream().map(ApplicationDto::of).toList();
    }
}
