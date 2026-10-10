package com.afrotransact.platform.security.relationship.openfga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.platform.security.relationship.RelationshipCheckUnavailableException;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class OpenFgaRelationshipCheckerTest {

    private static final RelationshipTuple OWNER = new RelationshipTuple("organization:org_a", "org", "offer:o1");

    private final AtomicLong now = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://fga")
            .defaultHeader("Authorization", "Bearer key");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void looksUpTheStoreByNameOnceAndSendsContextualTuples() {
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo("http://fga/stores?name=afrotransact"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess("{\"stores\":[{\"id\":\"S1\",\"name\":\"afrotransact\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(MockRestRequestMatchers.requestTo("http://fga/stores/S1/check"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer key"))
                .andExpect(MockRestRequestMatchers.content().json("""
                        {"tuple_key":{"user":"user:anne","relation":"can_edit","object":"offer:o1"},
                         "contextual_tuples":{"tuple_keys":[
                           {"user":"organization:org_a","relation":"org","object":"offer:o1"}]}}"""))
                .andRespond(MockRestResponseCreators.withSuccess("{\"allowed\":true,\"resolution\":\"\"}", MediaType.APPLICATION_JSON));
        server.expect(MockRestRequestMatchers.requestTo("http://fga/stores/S1/check"))
                .andRespond(MockRestResponseCreators.withSuccess("{\"allowed\":false}", MediaType.APPLICATION_JSON));

        OpenFgaRelationshipChecker checker = checker(props(null, Duration.ZERO));

        assertThat(checker.check("user:anne", "can_edit", "offer:o1", List.of(OWNER))).isTrue();
        assertThat(checker.check("user:bob", "can_edit", "offer:o1", List.of(OWNER))).isFalse();
        server.verify();
    }

    @Test
    void cachesResultsForTheTtlPerUserObjectAndContext() {
        server.expect(ExpectedCount.times(2), MockRestRequestMatchers.requestTo("http://fga/stores/S1/check"))
                .andRespond(MockRestResponseCreators.withSuccess("{\"allowed\":true}", MediaType.APPLICATION_JSON));
        OpenFgaRelationshipChecker checker = checker(props("S1", Duration.ofSeconds(5)));

        assertThat(checker.check("user:anne", "can_edit", "offer:o1", List.of(OWNER))).isTrue();
        assertThat(checker.check("user:anne", "can_edit", "offer:o1", List.of(OWNER))).isTrue();
        now.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThat(checker.check("user:anne", "can_edit", "offer:o1", List.of(OWNER))).isTrue();
        server.verify();
    }

    @Test
    void writesAreIdempotentAndClearTheCache() {
        server.expect(MockRestRequestMatchers.requestTo("http://fga/stores/S1/write"))
                .andExpect(MockRestRequestMatchers.content().json("""
                        {"writes":{"tuple_keys":[{"user":"user:anne","relation":"owner","object":"organization:a"}],
                                   "on_duplicate":"ignore"},
                         "deletes":{"tuple_keys":[{"user":"user:zed","relation":"owner","object":"organization:a"}],
                                   "on_missing":"ignore"},
                         "authorization_model_id":"M1"}"""))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));
        OpenFgaProperties props = new OpenFgaProperties(true, true, URI.create("http://fga"), "S1", null, "M1", null,
                null, Duration.ofSeconds(5), null);
        RelationshipCheckCache cache = new RelationshipCheckCache(props.cacheTtl(), 10, now::get);
        cache.put("k", true);

        new OpenFgaRelationshipWriter(new OpenFgaClient(builder.build(), props), cache).write(
                List.of(new RelationshipTuple("user:anne", "owner", "organization:a")),
                List.of(new RelationshipTuple("user:zed", "owner", "organization:a")));

        assertThat(cache.size()).isZero();
        server.verify();
    }

    @Test
    void serverErrorsAreUnavailableAndClientErrorsAreBugs() {
        server.expect(MockRestRequestMatchers.requestTo("http://fga/stores/S1/check")).andRespond(MockRestResponseCreators.withServerError());
        server.expect(MockRestRequestMatchers.requestTo("http://fga/stores/S1/check"))
                .andRespond(MockRestResponseCreators.withBadRequest().body("{\"code\":\"validation_error\"}"));
        OpenFgaRelationshipChecker checker = checker(props("S1", Duration.ZERO));

        assertThatThrownBy(() -> checker.check("user:a", "can_edit", "offer:o1"))
                .isInstanceOf(RelationshipCheckUnavailableException.class);
        assertThatThrownBy(() -> checker.check("user:a", "bogus", "offer:o1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("validation_error");
    }

    @Test
    void theCacheIsBounded() {
        RelationshipCheckCache cache = new RelationshipCheckCache(Duration.ofSeconds(5), 2, now::get);
        cache.put("a", true);
        cache.put("b", false);
        cache.put("c", true);
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("c")).isTrue();
        assertThat(cache.get("a")).isNull();
    }

    private OpenFgaRelationshipChecker checker(OpenFgaProperties props) {
        return new OpenFgaRelationshipChecker(new OpenFgaClient(builder.build(), props),
                new RelationshipCheckCache(props.cacheTtl(), props.cacheMaxEntries(), now::get));
    }

    private static OpenFgaProperties props(String storeId, Duration ttl) {
        return new OpenFgaProperties(true, true, URI.create("http://fga"), storeId, null, null, null, null, ttl, null);
    }
}
