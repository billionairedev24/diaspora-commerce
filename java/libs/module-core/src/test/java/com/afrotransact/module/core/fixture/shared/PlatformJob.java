package com.afrotransact.module.core.fixture.shared;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;

/** Fixture: a job of platform code (no module). */
public class PlatformJob {

    public final List<String> threads = new CopyOnWriteArrayList<>();

    @Scheduled(fixedDelay = 20)
    void tick() {
        threads.add(Thread.currentThread().getName());
    }
}
