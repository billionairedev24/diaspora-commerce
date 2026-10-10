package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S110: what Owner, Manager, Practitioner and Finance can and can't do — enforced server-side. */
class TeamRolesIT extends BookingsIntegrationTest {

    @Test
    void practitionersEditOnlyTheirOwnHoursAndCalendar() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        UUID kemi = member(s, "user-kemi", "Kemi", "PRACTITIONER");
        UUID tolu = member(s, "user-tolu", "Tolu", "PRACTITIONER");

        TestSupport.as(TestSupport.practitioner(s.org(), "user-kemi"));
        Map<String, Object> hours = Map.of("days", Map.of("MONDAY", List.of(range("10:00", "14:00"),
                range("14:30", "18:00"))));
        JsonNode own = ok(send("PUT", "/v1/providers/" + s.org() + "/team/" + kemi + "/hours", hours, null));
        assertThat(own.path("hours").path("days").path("MONDAY").size()).isEqualTo(2);
        problem(send("PUT", "/v1/providers/" + s.org() + "/team/" + tolu + "/hours", hours, null), 403,
                "bookings.own-calendar-only");
        problem(send("PUT", "/v1/providers/" + s.org() + "/team/" + tolu + "/overrides",
                Map.of("date", day().toString(), "closed", true), null), 403, "bookings.own-calendar-only");
        ok(send("PUT", "/v1/providers/" + s.org() + "/team/" + kemi + "/overrides",
                Map.of("date", day().toString(), "closed", true, "note", "Exam"), null));
        problem(send("PUT", "/v1/providers/" + s.org() + "/settings", Map.of("displayName", "x",
                "timeZone", "America/Chicago"), null), 403, "bookings.own-calendar-only");
        problem(send("POST", "/v1/providers/" + s.org() + "/services", Map.of("name", "Fulani braids",
                "durationMinutes", 240, "priceMinor", 16_000, "depositMinor", 4_000, "modes", List.of("IN_PERSON")),
                null), 403, "auth.missing-permission");

        JsonNode calendar = ok(send("GET", "/v1/providers/" + s.org() + "/calendar?from=" + day() + "&to="
                + day().plusDays(6), null, null));
        assertThat(calendar.path("days").valueStream().map(d -> d.path("memberId").asString()).distinct().toList())
                .containsExactly(kemi.toString());
        problem(send("GET", "/v1/providers/" + s.org() + "/calendar?from=" + day() + "&to=" + day()
                + "&memberId=" + tolu, null, null), 403, "bookings.own-calendar-only");
    }

    @Test
    void practitionersActOnlyOnTheirOwnBookings() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        member(s, "user-ada", "Ada", "PRACTITIONER");
        JsonNode b = book("cust-roles", s.serviceId(), firstSlot(s.serviceId()));
        assertThat(b.path("memberId").asString()).isEqualTo(s.ownerId().toString());

        TestSupport.as(TestSupport.practitioner(s.org(), "user-ada"));
        problem(send("POST", "/v1/bookings/" + b.path("id").asString() + "/cancel", null, "p-cancel"), 403,
                "bookings.own-calendar-only");
        JsonNode list = ok(send("GET", "/v1/providers/" + s.org() + "/bookings?from=2020-01-01T00:00:00Z"
                + "&to=2100-01-01T00:00:00Z", null, null));
        assertThat(list.size()).isZero();
    }

    @Test
    void financeIsViewOnlyAndOtherOrganisationsAreRefused() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.finance(s.org()));
        assertThat(ok(send("GET", "/v1/providers/" + s.org() + "/services", null, null)).size()).isEqualTo(1);
        problem(send("PUT", "/v1/providers/" + s.org() + "/team/" + s.ownerId() + "/hours",
                Map.of("days", Map.of()), null), 403, "auth.missing-permission");
        problem(send("PUT", "/v1/providers/" + s.org() + "/cancellation-policy", Map.of("kind", "STRICT"), null),
                403, "auth.missing-permission");
        problem(send("POST", "/v1/providers/" + s.org() + "/team", Map.of("displayName", "New", "role", "MANAGER"),
                null), 403, "auth.missing-permission");

        TestSupport.as(TestSupport.owner("org_someone_else"));
        problem(send("GET", "/v1/providers/" + s.org() + "/services", null, null), 403, "auth.wrong-org");
    }

    @Test
    void ownersAssignWhoPerformsAServiceAndSlotsFollow() {
        Setup s = provider(false);
        TestSupport.as(TestSupport.owner(s.org()));
        UUID kemi = member(s, "user-kemi2", "Kemi", "PRACTITIONER");
        JsonNode assigned = ok(send("PUT", "/v1/providers/" + s.org() + "/services/" + s.serviceId() + "/members",
                Map.of("memberIds", List.of(kemi)), null));
        assertThat(assigned.path("memberIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(kemi.toString());
        JsonNode slots = ok(send("GET", "/v1/public/services/" + s.serviceId() + "/slots?date=" + day(), null, null));
        assertThat(slots.size()).isGreaterThan(0);
        assertThat(slots.get(0).path("memberIds").valueStream().map(JsonNode::asString).toList())
                .containsExactly(kemi.toString());

        UUID finance = member(s, null, "Bola", "FINANCE");
        problem(send("PUT", "/v1/providers/" + s.org() + "/services/" + s.serviceId() + "/members",
                Map.of("memberIds", List.of(finance)), null), 422, "bookings.member-not-bookable");
    }

    private UUID member(Setup s, String userId, String name, String role) {
        HashMap<String, Object> body = new HashMap<>();
        if (userId != null) {
            body.put("userId", userId);
        }
        body.put("displayName", name);
        body.put("role", role);
        body.put("hours", s.hours());
        return UUID.fromString(ok(send("POST", "/v1/providers/" + s.org() + "/team", body, null), 201)
                .path("id").asString());
    }
}
