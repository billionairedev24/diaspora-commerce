package com.afrotransact.module.core;

import com.afrotransact.platform.events.retention.EventRetentionService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** The daily purge of one module's inbox and published outbox rows, on that module's scheduler pool. */
final class ModuleRetentionJob implements ModuleOwned {

    private static final Log LOG = LogFactory.getLog(ModuleRetentionJob.class);

    private final String module;
    private final EventRetentionService retention;

    ModuleRetentionJob(String module, EventRetentionService retention) {
        this.module = module;
        this.retention = retention;
    }

    @Scheduled(cron = "${at.events.retention.cron:0 30 3 * * *}")
    void run() {
        try {
            retention.purge();
        } catch (RuntimeException ex) {
            LOG.error("Module '" + module + "': event retention failed; it runs again at the next scheduled time", ex);
        }
    }

    @Override
    public String module() {
        return module;
    }
}
