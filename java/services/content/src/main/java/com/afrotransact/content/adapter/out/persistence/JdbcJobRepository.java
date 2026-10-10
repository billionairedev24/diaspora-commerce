package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.careers.Job;
import com.afrotransact.content.domain.careers.JobApplication;
import com.afrotransact.content.domain.careers.JobStatus;
import com.afrotransact.content.domain.careers.WorkAuthorization;
import com.afrotransact.content.port.JobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcJobRepository implements JobRepository {

    private static final String SELECT = """
            select id, title, team, location, employment_type, pay, summary, responsibilities::text as responsibilities,
                   requirements::text as requirements, status, sort_order, posted_at, updated_at
            from job
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcJobRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Job> findAll() {
        return jdbc.sql(SELECT + " order by sort_order, title").query(this::map).list();
    }

    @Override
    public Optional<Job> find(String id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public void insert(Job j) {
        jdbc.sql("""
                insert into job (id, title, team, location, employment_type, pay, summary, responsibilities,
                    requirements, status, sort_order, posted_at, updated_at)
                values (:id, :title, :team, :location, :type, :pay, :summary, cast(:resp as jsonb), cast(:req as jsonb),
                    :status, :sort, :posted, :updated)
                """)
                .param("id", j.id()).param("title", j.title()).param("team", j.team()).param("location", j.location())
                .param("type", j.type()).param("pay", j.pay()).param("summary", j.summary())
                .param("resp", json.write(j.responsibilities())).param("req", json.write(j.requirements()))
                .param("status", j.status().name()).param("sort", j.sortOrder())
                .param("posted", Timestamps.of(j.postedAt())).param("updated", Timestamps.of(j.updatedAt()))
                .update();
    }

    @Override
    public void update(Job j) {
        jdbc.sql("""
                update job set title = :title, team = :team, location = :location, employment_type = :type, pay = :pay,
                    summary = :summary, responsibilities = cast(:resp as jsonb), requirements = cast(:req as jsonb),
                    status = :status, sort_order = :sort, posted_at = :posted, updated_at = :updated
                where id = :id
                """)
                .param("id", j.id()).param("title", j.title()).param("team", j.team()).param("location", j.location())
                .param("type", j.type()).param("pay", j.pay()).param("summary", j.summary())
                .param("resp", json.write(j.responsibilities())).param("req", json.write(j.requirements()))
                .param("status", j.status().name()).param("sort", j.sortOrder())
                .param("posted", Timestamps.of(j.postedAt())).param("updated", Timestamps.of(j.updatedAt()))
                .update();
    }

    @Override
    public long nextApplicationSequence() {
        return jdbc.sql("select nextval('career_reference_seq')").query(Long.class).single();
    }

    @Override
    public void insertApplication(JobApplication a) {
        jdbc.sql("""
                insert into job_application (id, reference, job_id, name, email, linkedin_url, work_authorization,
                    resume_asset_id, resume_file_name, cover_note, consent_at, submitted_at)
                values (:id, :ref, :job, :name, :email, :linkedin, :auth, :asset, :file, :note, :at, :at)
                """)
                .param("id", a.id()).param("ref", a.reference()).param("job", a.jobId()).param("name", a.name())
                .param("email", a.email().value()).param("linkedin", a.linkedinUrl())
                .param("auth", a.workAuthorization().name()).param("asset", a.resumeAssetId())
                .param("file", a.resumeFileName()).param("note", a.coverNote())
                .param("at", Timestamps.of(a.submittedAt()))
                .update();
    }

    @Override
    public List<JobApplication> applications(String jobId, int limit) {
        return jdbc.sql("""
                select id, reference, job_id, name, email, linkedin_url, work_authorization, resume_asset_id,
                       resume_file_name, cover_note, submitted_at
                from job_application
                where (cast(:job as varchar) is null or job_id = :job)
                order by submitted_at desc limit :limit
                """)
                .param("job", jobId).param("limit", limit)
                .query((rs, n) -> new JobApplication(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("job_id"), rs.getString("name"), new EmailAddress(rs.getString("email")),
                        rs.getString("linkedin_url"), WorkAuthorization.valueOf(rs.getString("work_authorization")),
                        rs.getString("resume_asset_id"), rs.getString("resume_file_name"), rs.getString("cover_note"),
                        Timestamps.instant(rs, "submitted_at")))
                .list();
    }

    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getString("id"), rs.getString("title"), rs.getString("team"), rs.getString("location"),
                rs.getString("employment_type"), rs.getString("pay"), rs.getString("summary"),
                json.strings(rs.getString("responsibilities")), json.strings(rs.getString("requirements")),
                JobStatus.valueOf(rs.getString("status")), rs.getInt("sort_order"), Timestamps.instant(rs, "posted_at"),
                Timestamps.instant(rs, "updated_at"));
    }
}
