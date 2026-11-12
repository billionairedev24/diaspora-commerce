package com.afrotransact.catalog.adapter.out.metrics;

import java.util.EnumMap;
import java.util.Map;

import com.afrotransact.catalog.domain.images.ImageQuality;
import com.afrotransact.catalog.port.ImageQualityRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code catalog_image_quality{state}} (gauge: live and paused listings whose main image is OK, IMPROVE or
 * FAIL): the progress of the back-fill and the size of the "Improve your image" backlog. The counts are read from the
 * database at most once every 30 seconds; when the read fails the gauges report NaN (no data).
 */
@Component
class MicrometerImageQualityMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerImageQualityMetrics.class);
    private static final long CACHE_MILLIS = 30_000;

    private final ImageQualityRepository scores;
    private final Map<ImageQuality, Double> cached = new EnumMap<>(ImageQuality.class);
    private long readAt;

    MicrometerImageQualityMetrics(MeterRegistry registry, ImageQualityRepository scores) {
        this.scores = scores;
        for (ImageQuality state : ImageQuality.values()) {
            Gauge.builder("catalog.image.quality", this, m -> m.value(state)).tag("state", state.name())
                    .description("Live and paused listings by how their main image stands against the rules")
                    .register(registry);
        }
    }

    private synchronized double value(ImageQuality state) {
        long now = System.currentTimeMillis();
        if (readAt == 0 || now - readAt >= CACHE_MILLIS) {
            cached.clear();
            try {
                scores.counts().forEach((q, n) -> cached.put(q, n.doubleValue()));
            } catch (RuntimeException ex) {
                LOG.warn("Could not count listings by image quality", ex);
            }
            readAt = now;
        }
        return cached.getOrDefault(state, Double.NaN);
    }
}
