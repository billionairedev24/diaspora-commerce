package com.afrotransact.retail.adapter.in.web;

import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.ItemDtos.ItemRequest;
import com.afrotransact.retail.adapter.in.web.ItemDtos.ItemResponse;
import com.afrotransact.retail.adapter.in.web.ItemDtos.LinkAtinRequest;
import com.afrotransact.retail.adapter.in.web.ItemDtos.ListingRequest;
import com.afrotransact.retail.application.ItemService;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product master", description = "1P items linked to catalog ATINs: GTIN, customs, pack sizes, supplier "
        + "SKUs, cost, price and listing readiness (S068)")
@RestController
@RequestMapping("/v1/items")
class ItemController {

    private final ItemService items;

    ItemController(ItemService items) {
        this.items = items;
    }

    @Operation(operationId = "createItem", summary = "Create a 1P item (retail:items:write); SKU and GTIN are issued, listing starts as DRAFT")
    @PostMapping
    ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest body) {
        RetailItem i = items.create(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + i.version() + "\"").body(ItemResponse.of(i));
    }

    @Operation(operationId = "listItems", summary = "Search items by name, SKU, GTIN or ATIN (retail:read)")
    @GetMapping
    PageResponse<ItemResponse> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) UUID supplierId,
                                    @RequestParam(required = false) ListingStatus listing,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(items.list(q, supplierId, listing, page, size), ItemResponse::of);
    }

    @Operation(operationId = "getItem", summary = "An item (retail:read)")
    @GetMapping("/{id}")
    ResponseEntity<ItemResponse> get(@PathVariable UUID id) {
        RetailItem i = items.get(id);
        return Versions.tagged(ItemResponse.of(i), i.version());
    }

    @Operation(operationId = "updateItem", summary = "Update commercial and logistics data (retail:items:write; If-Match)")
    @PutMapping("/{id}")
    ResponseEntity<ItemResponse> update(@PathVariable UUID id, @Valid @RequestBody ItemRequest body,
                                        @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        RetailItem i = items.update(id, body.toCommand(), Versions.expected(ifMatch));
        return Versions.tagged(ItemResponse.of(i), i.version());
    }

    @Operation(operationId = "linkItemAtin", summary = "Link the catalog variant ATIN (validated against catalog's public API; must be a live "
            + "platform listing)")
    @PutMapping("/{id}/atin")
    ResponseEntity<ItemResponse> linkAtin(@PathVariable UUID id, @Valid @RequestBody LinkAtinRequest body,
                                          @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        RetailItem i = items.linkAtin(id, body.atin(), Versions.expected(ifMatch));
        return Versions.tagged(ItemResponse.of(i), i.version());
    }

    @Operation(operationId = "changeItemListing", summary = "Change listing readiness: DRAFT → LIVE ↔ PAUSED (LIVE needs an ATIN and a price)")
    @PostMapping("/{id}/listing")
    ResponseEntity<ItemResponse> listing(@PathVariable UUID id, @Valid @RequestBody ListingRequest body,
                                         @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        RetailItem i = items.changeListing(id, body.status(), Versions.expected(ifMatch));
        return Versions.tagged(ItemResponse.of(i), i.version());
    }
}
