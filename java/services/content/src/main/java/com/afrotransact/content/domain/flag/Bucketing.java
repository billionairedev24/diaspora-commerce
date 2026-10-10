package com.afrotransact.content.domain.flag;

/** Maps (flag, subject) to a stable bucket in [0, 10000). */
public interface Bucketing {

    int BUCKETS = 10_000;

    int bucket(String flagKey, String subject);
}
