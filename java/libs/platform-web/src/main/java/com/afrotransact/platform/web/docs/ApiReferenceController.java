package com.afrotransact.platform.web.docs;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves every registered {@link ApiReferencePage} next to springdoc's /v3/api-docs and Swagger UI. */
@Hidden
@RestController
public class ApiReferenceController {

    private final Map<String, ApiReferencePage> pages;
    private final ApiDocsProperties properties;

    public ApiReferenceController(List<ApiReferencePage> pages, ApiDocsProperties properties) {
        this.pages = pages.stream().collect(Collectors.toMap(ApiReferencePage::path, Function.identity()));
        this.properties = properties;
    }

    @GetMapping(path = {"/docs", "/redoc"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        ApiReferencePage page = pages.get(request.getRequestURI());
        if (page == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(page.render(properties.title(), properties.specUrl()));
    }
}
