package com.afrotransact.catalog.adapter.in.web;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.catalog.adapter.in.web.CategoryDtos.CreateCategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.web.error.ModuleFailures;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the catalog endpoints the MCP tools read, for the in-process {@code CatalogUserApi}: each method calls the controller
 * handler of the HTTP endpoint (same use case, same response record, so the same JSON as the HTTP answer) and turns the response into a
 * JSON tree. It lives next to the controllers because their handlers and response records are package private; nothing here decides who
 * may do what (the use cases and the provider's entry do). The request-body validation of {@code @Valid} is applied by hand where an
 * endpoint takes a body.
 */
@Component
public class CatalogEndpointViews {

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F-]{36}");

    private final PublicProductController publicProducts;
    private final ProductController products;
    private final CategoryController categories;
    private final Validator validator;
    private final JsonMapper json;

    CatalogEndpointViews(PublicProductController publicProducts, ProductController products, CategoryController categories,
                         Validator validator, JsonMapper json) {
        this.publicProducts = publicProducts;
        this.products = products;
        this.categories = categories;
        this.validator = validator;
        this.json = json;
    }

    public Object publicProduct(String atin) {
        return tree(publicProducts.product(atin).getBody());
    }

    public Object publicVariant(String atin) {
        return tree(publicProducts.variant(atin).getBody());
    }

    public Object products(String orgId, String status, String query, int page, int size) {
        ProductStatus parsed = null;
        if (status != null) {
            try {
                parsed = ProductStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ModuleApiException(400, "invalid-input", "Unknown product status " + status);
            }
        }
        return tree(products.list(orgId, parsed, query, false, page, size));
    }

    public Object product(String idOrAtin) {
        if (UUID_TEXT.matcher(idOrAtin).matches()) {
            try {
                return tree(products.get(UUID.fromString(idOrAtin)));
            } catch (IllegalArgumentException e) {
                throw new ModuleApiException(400, "invalid-input", "Not a product id: " + idOrAtin);
            }
        }
        return tree(products.byAtin(idOrAtin));
    }

    public Object requestCategory(Map<String, Object> request) {
        CreateCategoryRequest body = new CreateCategoryRequest(text(request, "parentPath"), text(request, "name"),
                text(request, "exampleProduct"), text(request, "detailsNeeded"));
        ModuleFailures.validate(validator, body);
        return tree(categories.request(body));
    }

    public Object categoryRequests(String status) {
        CategoryRequestStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = CategoryRequestStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ModuleApiException(400, "invalid-input", "Unknown category request status " + status);
            }
        }
        return tree(categories.queue(parsed));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Object tree(Object response) {
        return json.convertValue(response, Object.class);
    }
}
