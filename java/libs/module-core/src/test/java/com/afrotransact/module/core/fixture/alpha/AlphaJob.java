package com.afrotransact.module.core.fixture.alpha;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;

/** Fixture: a module's scheduled job records the thread it ran on. */
public class AlphaJob {

    public final List<String> threads = new CopyOnWriteArrayList<>();

    @Scheduled(fixedDelay = 20)
    void tick() {
        threads.add(Thread.currentThread().getName());
    }
}
