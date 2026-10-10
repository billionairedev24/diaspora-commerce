package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;
import com.afrotransact.content.domain.newsletter.NewsletterSubscription;
import com.afrotransact.content.domain.newsletter.SubscriptionStatus;
import com.afrotransact.content.port.NewsletterRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNewsletterRepository implements NewsletterRepository {

    private static final String SELECT = """
            select id, email, status, locale, source, created_at, confirmed_at, unsubscribed_at, updated_at
            from newsletter_subscription
            """;

    private final JdbcClient jdbc;

    JdbcNewsletterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NewsletterSubscription> findByEmail(EmailAddress email) {
        return jdbc.sql(SELECT + " where email = :email").param("email", email.value())
                .query(JdbcNewsletterRepository::map).optional();
    }

    @Override
    public Optional<NewsletterSubscription> findById(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(JdbcNewsletterRepository::map).optional();
    }

    @Override
    public void insert(NewsletterSubscription s) {
        jdbc.sql("""
                insert into newsletter_subscription (id, email, status, locale, source, created_at, confirmed_at,
                    unsubscribed_at, updated_at)
                values (:id, :email, :status, :locale, :source, :created, :confirmed, :unsubscribed, :updated)
                """)
                .param("id", s.id()).param("email", s.email().value()).param("status", s.status().name())
                .param("locale", s.locale()).param("source", s.source()).param("created", Timestamps.of(s.createdAt()))
                .param("confirmed", Timestamps.of(s.confirmedAt()))
                .param("unsubscribed", Timestamps.of(s.unsubscribedAt()))
                .param("updated", Timestamps.of(s.updatedAt()))
                .update();
    }

    @Override
    public void update(NewsletterSubscription s) {
        jdbc.sql("""
                update newsletter_subscription set status = :status, locale = :locale, source = :source,
                    confirmed_at = :confirmed, unsubscribed_at = :unsubscribed, updated_at = :updated
                where id = :id
                """)
                .param("id", s.id()).param("status", s.status().name()).param("locale", s.locale())
                .param("source", s.source()).param("confirmed", Timestamps.of(s.confirmedAt()))
                .param("unsubscribed", Timestamps.of(s.unsubscribedAt()))
                .param("updated", Timestamps.of(s.updatedAt()))
                .update();
    }

    private static NewsletterSubscription map(ResultSet rs, int row) throws SQLException {
        return new NewsletterSubscription(rs.getObject("id", UUID.class), new EmailAddress(rs.getString("email")),
                SubscriptionStatus.valueOf(rs.getString("status")), rs.getString("locale"), rs.getString("source"),
                Timestamps.instant(rs, "created_at"), Timestamps.instant(rs, "confirmed_at"),
                Timestamps.instant(rs, "unsubscribed_at"), Timestamps.instant(rs, "updated_at"));
    }
}
