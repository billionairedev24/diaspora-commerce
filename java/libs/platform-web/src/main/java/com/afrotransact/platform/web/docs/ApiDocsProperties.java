package com.afrotransact.platform.web.docs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.api-docs.*} settings shared by Swagger UI, Scalar and Redoc.
 *
 * @param enabled        serve the reference pages (off in prod by default; contracts are published on the portal)
 * @param title          human-readable service name
 * @param version        API version shown in the document
 * @param description    one-line service description
 * @param specUrl        URL of the OpenAPI document the pages load
 * @param scalarVersion  pinned @scalar/api-reference version
 * @param redocVersion   pinned redoc version
 * @param contactUrl     contact link in the document's info block (default: the public developer portal, which is
 *                       the same in every environment and documents the problem types)
 */
@ConfigurationProperties(prefix = "at.api-docs")
public record ApiDocsProperties(
        Boolean enabled,
        String title,
        String version,
        String description,
        String specUrl,
        String scalarVersion,
        String redocVersion,
        String contactUrl) {

    /** The public developer portal: one canonical site for every environment (problem types live under it). */
    public static final String DEVELOPER_PORTAL_URL = "https://developers.afrotransact.com";

    public ApiDocsProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        title = title == null ? "AfroTransact API" : title;
        version = version == null ? "v1" : version;
        description = description == null ? "" : description;
        specUrl = specUrl == null ? "/v3/api-docs" : specUrl;
        scalarVersion = scalarVersion == null ? "1.73.1" : scalarVersion;
        redocVersion = redocVersion == null ? "2.5.4" : redocVersion;
        contactUrl = contactUrl == null || contactUrl.isBlank() ? DEVELOPER_PORTAL_URL : contactUrl;
    }
}
