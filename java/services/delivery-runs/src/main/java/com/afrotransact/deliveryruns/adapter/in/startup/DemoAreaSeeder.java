package com.afrotransact.deliveryruns.adapter.in.startup;

import com.afrotransact.deliveryruns.application.PreorderSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local profile only: seeds the design's demo pre-order coverage areas (Lubbock, Amarillo, El Paso, Midland, Odessa,
 * Toronto, Mississauga, Brampton on; Laredo off) when missing. Never runs in dev, uat or prod: migrations carry no
 * demo data.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "at.delivery-runs.preorders", name = "seed-demo-areas", havingValue = "true")
class DemoAreaSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoAreaSeeder.class);

    private final PreorderSettingsService settings;

    DemoAreaSeeder(PreorderSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        int added = settings.seedDemoAreas();
        if (added > 0) {
            LOG.info("Seeded {} demo pre-order coverage areas (local profile)", added);
        }
    }
}
