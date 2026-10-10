package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.flag.FeatureFlag;

/** Feature flags. */
public interface FlagRepository {

    List<FeatureFlag> findAll();

    Optional<FeatureFlag> find(String key);

    void insert(FeatureFlag flag);

    /** Saves when the stored version equals {@code flag.version()} and bumps it; false on a concurrent change. */
    boolean update(FeatureFlag flag);
}
