package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.home.CommunityEntry;
import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.port.HomeSettingsRepository.Stored;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Bodies of the home settings and community registry endpoints. */
final class HomeSettingsDtos {

    private HomeSettingsDtos() {
    }

    @Schema(name = "SaveHomeSettingsRequest")
    record SaveRequest(@NotNull @Min(0) Integer expectedVersion, @NotNull HomeSettings settings, String reason) {
    }

    @Schema(name = "RevertHomeSettingsRequest")
    record RevertRequest(@NotNull @Min(1) Integer toVersion, String reason) {
    }

    @Schema(name = "HomeSettingsVersion")
    record VersionDto(int version, String reason, String changedBy, Instant changedAt, Integer revertOf,
                      List<Map<String, Object>> changes) {

        static VersionDto of(Stored s) {
            return new VersionDto(s.settings().version(), s.reason(), s.changedBy(), s.changedAt(), s.revertOf(), s.changes());
        }
    }

    @Schema(name = "PutCommunityRequest")
    record PutCommunityRequest(@NotNull Map<String, String> labels, List<String> aliases, String imageUrl, String reason) {

        CommunityEntry toDomain(String key) {
            return new CommunityEntry(key, labels, aliases, imageUrl, null);
        }
    }
}
