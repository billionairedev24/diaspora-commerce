package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.Test;

/** Every meter carries module and service, with the module's short service name where it can be attributed. */
class ModuleMeterFilterTest {

    private final ModuleFilterFixture fixture = new ModuleFilterFixture();

    @Test
    void aConnectionPoolIsAttributedByItsName() {
        Meter.Id id = fixture.filter.map(fixture.id("hikaricp.connections.max", Tags.of("pool", "tax-returns")));
        assertThat(id.getTag("module")).isEqualTo("tax-returns");
        assertThat(id.getTag("service")).isEqualTo("tax-returns");
    }

    @Test
    void aModulesOwnMeterIsAttributedByItsPrefix() {
        assertThat(fixture.filter.map(fixture.id("catalog.tax.gate.closed", Tags.empty())).getTag("module"))
                .isEqualTo("catalog");
        assertThat(fixture.filter.map(fixture.id("tax.quote.lines", Tags.empty())).getTag("module"))
                .isEqualTo("tax-returns");
    }

    @Test
    void aKafkaClientIsAttributedByItsIdPrefix() {
        Meter.Id id = fixture.filter.map(fixture.id("kafka.consumer.fetch.manager.records.lag",
                Tags.of("client.id", "taxreturns-orders-0")));
        assertThat(id.getTag("module")).isEqualTo("tax-returns");
    }

    @Test
    void anAlreadyTaggedMeterKeepsItsModule() {
        Meter.Id id = fixture.filter.map(fixture.id("http.server.requests", Tags.of("module", "catalog")));
        assertThat(id.getTag("service")).isEqualTo("catalog");
    }

    @Test
    void processLevelMetersGetTheCoreApplicationAndEveryMeterHasBothTags() {
        Meter.Id id = fixture.filter.map(fixture.id("jvm.memory.used", Tags.of("area", "heap")));
        assertThat(id.getTag("module")).isEqualTo("core");
        assertThat(id.getTag("service")).isEqualTo("afrotransact-core");
        assertThat(id.getTag("area")).isEqualTo("heap");
    }

    /** Registry with the two spike modules. */
    static final class ModuleFilterFixture {

        final ModuleFilterRegistry registry = new ModuleFilterRegistry();
        final ModuleMeterFilter filter = new ModuleMeterFilter(registry.modules);

        Meter.Id id(String name, Tags tags) {
            return new Meter.Id(name, tags, null, null, Meter.Type.COUNTER);
        }
    }

    static final class ModuleFilterRegistry {

        final ModuleRegistry modules = new ModuleRegistry("afrotransact-core");

        ModuleFilterRegistry() {
            ModuleProperties settings = new ModuleProperties(true, new ModuleProperties.Database("u", "u", "p", 4, 0,
                    Duration.ofSeconds(1), Duration.ofSeconds(1)), new ModuleProperties.Migration(true, null),
                    new ModuleProperties.Scheduler(1), new ModuleProperties.Events(true, 1, true), null, null);
            modules.add(new ModuleDefinition("catalog", "catalog", "com.afrotransact.catalog", "x.C",
                    List.of("catalog.", "catalog-")), settings);
            modules.add(new ModuleDefinition("tax-returns", "tax-returns", "com.afrotransact.taxreturns", "x.T",
                    List.of("tax.", "taxreturns-")), settings);
        }
    }
}
