package com.afrotransact.content.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.careers.ApplicationDraft;
import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;

/** Careers page: open roles, applications with a CAR-###### reference, and role management. */
public interface CareersService {

    /** Open roles, optionally filtered by team and location (case-insensitive). */
    JobListing openJobs(String team, String location);

    Job openJob(String id);

    ApplicationReceipt apply(String jobId, ApplicationDraft draft);

    List<Job> allJobs();

    Job createJob(Job job);

    Job updateJob(String id, Job job);

    List<JobApplication> applications(String jobId);

    /**
     * Open roles with the filter values available.
     *
     * @param jobs      matching roles
     * @param teams     every team with an open role
     * @param locations every location with an open role
     */
    record JobListing(List<Job> jobs, List<String> teams, List<String> locations) {
    }

    /**
     * Shown to the candidate.
     *
     * @param reference   CAR-######
     * @param jobId       role
     * @param submittedAt when
     */
    record ApplicationReceipt(String reference, String jobId, Instant submittedAt) {
    }
}
