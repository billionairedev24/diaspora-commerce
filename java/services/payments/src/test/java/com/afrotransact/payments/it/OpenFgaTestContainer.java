package com.afrotransact.payments.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * OpenFGA (in-memory datastore) for relationship ITs, from the local image {@code openfga/openfga:v1.10.0}. Creates a
 * store and loads {@code contracts/openfga/model.json}, the model authz-sync deploys.
 */
final class OpenFgaTestContainer {

    static final DockerImageName IMAGE = DockerImageName.parse("openfga/openfga:v1.10.0");

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private OpenFgaTestContainer() {
    }

    static GenericContainer<?> create() {
        return new GenericContainer<>(IMAGE).withCommand("run").withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080));
    }

    static String apiUrl(GenericContainer<?> fga) {
        return "http://" + fga.getHost() + ":" + fga.getMappedPort(8080);
    }

    /** Creates a store with the repository's authorisation model and returns the store id. */
    static String createStoreWithModel(GenericContainer<?> fga) {
        String store = post(apiUrl(fga) + "/stores", "{\"name\":\"afrotransact\"}");
        Matcher m = ID.matcher(store);
        if (!m.find()) {
            throw new IllegalStateException("No store id in " + store);
        }
        String storeId = m.group(1);
        post(apiUrl(fga) + "/stores/" + storeId + "/authorization-models", read(modelJson()));
        return storeId;
    }

    private static Path modelJson() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts/openfga/model.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts/openfga/model.json not found above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String post(String url, String json) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(url + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
