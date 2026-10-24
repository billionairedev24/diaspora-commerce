package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.TaxMethodResolver;
import com.afrotransact.taxreturns.application.TaxSettingsService;
import com.afrotransact.taxreturns.application.TaxSettingsService.TaxSettings;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Who calculates sales tax, against PostgreSQL: the default, the switch, the audit history and who may change it. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.taxreturns.tax.stripe.secret-key=sk_test_integration"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class TaxSettingsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private final TaxSettingsService settings;
    private final TaxMethodResolver resolver;
    private final TestActors actors;

    @Autowired
    TaxSettingsIT(TaxSettingsService settings, TaxMethodResolver resolver, TestActors actors) {
        this.settings = settings;
        this.resolver = resolver;
        this.actors = actors;
    }

    @BeforeEach
    void asStaff() {
        actors.staff();
    }

    @Test
    void theSwitchIsRecordedAndTakesEffectAtOnceAndComesBack() {
        assertThat(settings.current().method()).as("our own rules until staff choose").isEqualTo(TaxMethod.RULES);
        assertThat(settings.current().stripeAvailable()).isTrue();

        assertThatThrownBy(() -> settings.change(TaxMethod.STRIPE_TAX, "short"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("at least");

        TaxSettings on = settings.change(TaxMethod.STRIPE_TAX, "Registrations are done in Stripe");
        assertThat(on.method()).isEqualTo(TaxMethod.STRIPE_TAX);
        assertThat(resolver.method()).isEqualTo(TaxMethod.STRIPE_TAX);
        assertThat(on.changedBy()).isEqualTo("staff_1");
        assertThat(on.history()).first().satisfies(h -> {
            assertThat(h.from()).isNull();
            assertThat(h.to()).isEqualTo(TaxMethod.STRIPE_TAX);
            assertThat(h.reason()).isEqualTo("Registrations are done in Stripe");
        });

        assertThat(settings.change(TaxMethod.STRIPE_TAX, "Choosing the same thing again").history())
                .as("no change, no history row").hasSize(1);

        TaxSettings off = settings.change(TaxMethod.RULES, "Falling back to our own rules");
        assertThat(off.method()).isEqualTo(TaxMethod.RULES);
        assertThat(off.history()).extracting(h -> h.to()).containsExactly(TaxMethod.RULES, TaxMethod.STRIPE_TAX);
        assertThat(off.history().getFirst().from()).isEqualTo(TaxMethod.STRIPE_TAX);
    }

    @Test
    void onlyStaffWithTheTaxPermissionsMayReadOrChange() {
        actors.seller("org_a", "payouts:read");
        assertThatThrownBy(settings::current).isInstanceOf(ForbiddenActionException.class);
        actors.service("tax:read");
        assertThat(settings.current()).isNotNull();
        assertThatThrownBy(() -> settings.change(TaxMethod.RULES, "Not allowed with read only"))
                .isInstanceOf(ForbiddenActionException.class);
    }
}
