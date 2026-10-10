package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.careers.ApplicationDraft;
import com.afrotransact.content.domain.careers.ApplicationPolicy;
import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;
import com.afrotransact.content.domain.careers.JobStatus;
import com.afrotransact.content.port.JobRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CareersServiceImpl implements CareersService {

    private final JobRepository jobs;
    private final ApplicationPolicy policy;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final DomainEventPublisher events;
    private final Clock clock;

    CareersServiceImpl(JobRepository jobs, ApplicationPolicy policy, ContentAccess access, AuditTrail audit,
                       DomainEventPublisher events, Clock clock) {
        this.jobs = jobs;
        this.policy = policy;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public JobListing openJobs(String team, String location) {
        List<Job> open = jobs.findAll().stream().filter(Job::open).toList();
        List<Job> matching = open.stream()
                .filter(j -> blank(team) || j.team().equalsIgnoreCase(team.trim()))
                .filter(j -> blank(location) || j.location().equalsIgnoreCase(location.trim()))
                .toList();
        return new JobListing(matching, open.stream().map(Job::team).distinct().sorted().toList(),
                open.stream().map(Job::location).distinct().sorted().toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Job openJob(String id) {
        return jobs.find(id).filter(Job::open).orElseThrow(() -> notFound(id));
    }

    @Override
    @Transactional
    public ApplicationReceipt apply(String jobId, ApplicationDraft draft) {
        Job job = jobs.find(jobId).filter(j -> j.status() != JobStatus.DRAFT).orElseThrow(() -> notFound(jobId));
        JobApplication valid = policy.validate(job, draft, Instant.now(clock));
        JobApplication app = valid.issued(UUID.randomUUID(), JobApplication.reference(jobs.nextApplicationSequence()));
        jobs.insertApplication(app);
        events.publish(ContentEvents.CAREER_APPLICATION_RECEIVED, ContentEvents.AGGREGATE, app.reference(),
                new ContentEventPayloads.CareerApplicationReceived(app.reference(), job.id(), job.title(), job.team(),
                        job.location(), app.name(), app.email().value(), app.resumeAssetId(), app.resumeFileName(),
                        app.submittedAt()));
        return new ApplicationReceipt(app.reference(), job.id(), app.submittedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> allJobs() {
        access.requireAny(ContentPermissions.CAREERS_VIEW, ContentPermissions.CAREERS_EDIT);
        return jobs.findAll();
    }

    @Override
    @Transactional
    public Job createJob(Job job) {
        Actor actor = access.require(ContentPermissions.CAREERS_EDIT);
        Instant now = Instant.now(clock);
        Job stored = new Job(job.id(), job.title(), job.team(), job.location(), job.type(), job.pay(), job.summary(),
                job.responsibilities(), job.requirements(), job.status(), job.sortOrder(),
                job.status() == JobStatus.OPEN ? now : null, now);
        stored.validate();
        if (jobs.find(job.id()).isPresent()) {
            throw new ConflictException("content.job-exists", "A role with id " + job.id() + " already exists");
        }
        jobs.insert(stored);
        audit.record(actor.subject(), "job.created", "job", stored.id(), Map.of("status", stored.status().name()));
        return stored;
    }

    @Override
    @Transactional
    public Job updateJob(String id, Job job) {
        Actor actor = access.require(ContentPermissions.CAREERS_EDIT);
        Job current = jobs.find(id).orElseThrow(() -> notFound(id));
        Instant now = Instant.now(clock);
        Instant posted = current.postedAt() == null && job.status() == JobStatus.OPEN ? now : current.postedAt();
        Job next = new Job(id, job.title(), job.team(), job.location(), job.type(), job.pay(), job.summary(),
                job.responsibilities(), job.requirements(), job.status(), job.sortOrder(), posted, now);
        next.validate();
        jobs.update(next);
        audit.record(actor.subject(), "job.updated", "job", id,
                Map.of("from", current.status().name(), "to", next.status().name()));
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobApplication> applications(String jobId) {
        access.requireAny(ContentPermissions.CAREERS_VIEW, ContentPermissions.CAREERS_EDIT);
        return jobs.applications(blank(jobId) ? null : jobId, 500);
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("content.job-not-found", "No open role " + id);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
