package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;

/** Roles, applications and application references. */
public interface JobRepository {

    List<Job> findAll();

    Optional<Job> find(String id);

    void insert(Job job);

    void update(Job job);

    /** Next number of the {@code CAR-######} sequence. */
    long nextApplicationSequence();

    void insertApplication(JobApplication application);

    /** Applications, newest first, optionally for one role. */
    List<JobApplication> applications(String jobId, int limit);
}
