package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;
import com.afrotransact.content.domain.careers.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Careers bodies (public shapes follow the BFF's Job / JobApplication / ApplicationReceipt). */
final class CareersDtos {

    private CareersDtos() {
    }

    @Schema(name = "Job")
    record JobDto(String id, String title, String team, String location, String type, String pay, String summary,
                  List<String> responsibilities, List<String> requirements, JobStatus status, Instant postedAt) {

        static JobDto of(Job j) {
            return new JobDto(j.id(), j.title(), j.team(), j.location(), j.type(), j.pay(), j.summary(),
                    j.responsibilities(), j.requirements(), j.status(), j.postedAt());
        }
    }

    @Schema(name = "Jobs")
    record JobsResponse(List<JobDto> jobs, List<String> teams, List<String> locations) {
    }

    @Schema(name = "JobApplication")
    record ApplicationRequest(String name, String email,
                              @Schema(description = "Empty or https://www.linkedin.com/in/…") String linkedin,
                              @Schema(allowableValues = {"Yes", "Yes, with sponsorship", "No"}) String workAuthorization,
                              @Schema(description = "media-service asset id of the uploaded résumé") String resumeAssetId,
                              @Schema(example = "resume.pdf") String resumeFileName, String coverNote,
                              Boolean consent) {
    }

    @Schema(name = "ApplicationReceipt")
    record ReceiptDto(@Schema(example = "CAR-100001") String reference, String jobId, Instant submittedAt) {
    }

    @Schema(name = "JobRequest")
    record JobRequest(@NotBlank String title, @NotBlank String team, @NotBlank String location, @NotBlank String type,
                      @NotBlank String pay, @NotBlank String summary, List<String> responsibilities,
                      List<String> requirements, @NotNull JobStatus status, Integer sortOrder) {

        Job toDomain(String id) {
            return new Job(id, title, team, location, type, pay, summary, responsibilities, requirements, status,
                    sortOrder == null ? 0 : sortOrder, null, null);
        }
    }

    @Schema(name = "CreateJobRequest")
    record CreateJobRequest(@NotBlank @Schema(example = "data-engineer-remote-us") String id, @NotNull JobRequest job) {
    }

    @Schema(name = "JobApplicationRecord")
    record ApplicationDto(String reference, String jobId, String name, String email, String linkedinUrl,
                          String workAuthorization, String resumeAssetId, String resumeFileName, String coverNote,
                          Instant submittedAt) {

        static ApplicationDto of(JobApplication a) {
            return new ApplicationDto(a.reference(), a.jobId(), a.name(), a.email().value(), a.linkedinUrl(),
                    a.workAuthorization().label(), a.resumeAssetId(), a.resumeFileName(), a.coverNote(),
                    a.submittedAt());
        }
    }
}
