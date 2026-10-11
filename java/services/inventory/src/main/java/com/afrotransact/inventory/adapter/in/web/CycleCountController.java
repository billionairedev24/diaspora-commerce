package com.afrotransact.inventory.adapter.in.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.application.CycleCountService;
import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountLine;
import com.afrotransact.inventory.domain.CycleCountReason;
import com.afrotransact.inventory.domain.CycleCountStatus;
import com.afrotransact.inventory.port.CycleCountRepository;
import com.afrotransact.inventory.port.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
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

@Tag(name = "Cycle counts", description = "Count the lots of a fulfilment centre, review variances and post them as "
        + "audited ADJUST movements with reason codes (S281). inventory:read to view, inventory:adjust to change.")
@RestController
@RequestMapping("/v1/cycle-counts")
class CycleCountController {

    private final CycleCountService counts;

    CycleCountController(CycleCountService counts) {
        this.counts = counts;
    }

    @Operation(operationId = "startCycleCount", summary = "Start a cycle count of a node's lots (inventory:adjust)",
            description = "Snapshots every lot on hand at the node (narrowed to a SKU, a bin prefix and / or an "
                    + "owner) as the expected quantities. 422 inventory.cycle-count-empty when nothing is on hand, "
                    + "inventory.cycle-count-too-large over 500 lots.")
    @PostMapping
    ResponseEntity<CountResponse> start(@Valid @RequestBody StartRequest body) {
        CycleCount c = counts.start(new CycleCountService.StartCount(body.nodeId(), body.sku(), body.binPrefix(),
                body.ownerOrg(), Boolean.TRUE.equals(body.blind()), body.note()));
        return ResponseEntity.created(URI.create("/v1/cycle-counts/" + c.id())).eTag(etag(c)).body(CountResponse.of(c));
    }

    @Operation(operationId = "listCycleCounts", summary = "List cycle counts, newest first (inventory:read)")
    @GetMapping
    CountPage list(@RequestParam(required = false) String nodeId,
                   @RequestParam(required = false) CycleCountStatus status,
                   @RequestParam(required = false) String sku,
                   @RequestParam(required = false) String ownerOrg,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "25") int size) {
        Page<CycleCount> p = counts.list(new CycleCountRepository.CountQuery(nodeId, status, sku, ownerOrg, page, size));
        return new CountPage(p.items().stream().map(CountResponse::of).toList(), p.total(), p.page(), p.size());
    }

    @Operation(operationId = "getCycleCount", summary = "One cycle count with its lines and variances")
    @GetMapping("/{id}")
    ResponseEntity<CountResponse> get(@PathVariable UUID id) {
        CycleCount c = counts.get(id);
        return ResponseEntity.ok().eTag(etag(c)).body(CountResponse.of(c));
    }

    @Operation(operationId = "recordCycleCounts", summary = "Record counted quantities (inventory:adjust)",
            description = "A line can be counted again until the count is posted. Every line counted moves the "
                    + "count to COUNTED. If-Match with the ETag: 409 inventory.version-mismatch.")
    @PutMapping("/{id}/counts")
    ResponseEntity<CountResponse> record(@PathVariable UUID id, @Valid @RequestBody CountsRequest body,
                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                         String ifMatch) {
        CycleCount c = counts.record(id, body.lines().stream().map(l -> new CycleCountService.CountedLine(l.lineNo(),
                l.countedQty(), l.reasonCode(), l.note())).toList(), Versions.parse(ifMatch));
        return ResponseEntity.ok().eTag(etag(c)).body(CountResponse.of(c));
    }

    @Operation(operationId = "postCycleCount", summary = "Post the variances as ADJUST movements (inventory:adjust)",
            description = "Every line must be counted and every variance needs a reason code (422 "
                    + "inventory.cycle-count-incomplete). Each lot moves by counted − expected; a lot can never go "
                    + "below its reserved units (422 inventory.cycle-count-below-reserved).")
    @PostMapping("/{id}/post")
    ResponseEntity<CountResponse> post(@PathVariable UUID id,
                                       @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        CycleCount c = counts.post(id, Versions.parse(ifMatch));
        return ResponseEntity.ok().eTag(etag(c)).body(CountResponse.of(c));
    }

    @Operation(operationId = "cancelCycleCount", summary = "Cancel an open cycle count (inventory:adjust)")
    @PostMapping("/{id}/cancel")
    ResponseEntity<CountResponse> cancel(@PathVariable UUID id,
                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                         String ifMatch) {
        CycleCount c = counts.cancel(id, Versions.parse(ifMatch));
        return ResponseEntity.ok().eTag(etag(c)).body(CountResponse.of(c));
    }

    private static String etag(CycleCount c) {
        return "\"" + c.version() + "\"";
    }

    // ---- bodies ----

    @Schema(name = "StartCycleCountRequest", description = "Which lots to count: a node, optionally narrowed")
    record StartRequest(@NotBlank @Size(max = 20) String nodeId, @Size(max = 64) String sku,
                        @Schema(description = "Count only bins starting with this, e.g. A-01") @Size(max = 30)
                        String binPrefix,
                        @Schema(description = "Count only one owner's stock (a seller org or the retail org)")
                        @Size(max = 80) String ownerOrg,
                        @Schema(description = "Hide system quantities from the counter") Boolean blind,
                        @Size(max = 500) String note) {
    }

    @Schema(name = "CycleCountEntry")
    record CountEntry(@Positive int lineNo, @Min(0) @Max(1_000_000) int countedQty,
                      @Schema(description = "Required to post a line whose count differs from the system")
                      CycleCountReason reasonCode, @Size(max = 500) String note) {
    }

    @Schema(name = "RecordCycleCountsRequest")
    record CountsRequest(@NotEmpty @Size(max = 500) List<@Valid CountEntry> lines) {
    }

    @Schema(name = "CycleCountLine")
    record LineResponse(int lineNo, UUID lotId, String sku, String lotNo, String bin, LocalDate expDate,
                        @Schema(description = "System quantity when the count started") int expectedQty,
                        Integer countedQty, @Schema(description = "counted − expected (0 until counted)") int variance,
                        CycleCountReason reasonCode, String note, String countedBy, Instant countedAt,
                        @Schema(description = "Units adjusted when posted") Integer postedDelta) {

        static LineResponse of(CycleCountLine l) {
            return new LineResponse(l.lineNo(), l.lotId(), l.sku(), l.lotNo(), l.bin(), l.expDate(), l.expectedQty(),
                    l.countedQty(), l.variance(), l.reasonCode(), l.note(), l.countedBy(), l.countedAt(),
                    l.postedDelta());
        }
    }

    @Schema(name = "CycleCount", description = "A count of one node's lots with expected and counted quantities")
    record CountResponse(UUID id, String number, String nodeId, String sku, String binPrefix, String ownerOrg,
                         boolean blind,
                         @Schema(description = "OPEN | COUNTED | POSTED | CANCELLED") CycleCountStatus status,
                         String note, int lineCount, long countedLines, long varianceLines,
                         @Schema(description = "Σ counted − expected over counted lines") int netVariance,
                         List<LineResponse> lines, String createdBy, Instant createdAt, Instant countedAt,
                         String postedBy, Instant postedAt, Instant updatedAt, long version) {

        static CountResponse of(CycleCount c) {
            return new CountResponse(c.id(), c.number(), c.nodeId(), c.sku(), c.binPrefix(), c.ownerOrg(), c.blind(),
                    c.status(), c.note(), c.lines().size(), c.countedLines(), c.varianceLines(), c.netVariance(),
                    c.lines().stream().map(LineResponse::of).toList(), c.createdBy(), c.createdAt(), c.countedAt(),
                    c.postedBy(), c.postedAt(), c.updatedAt(), c.version());
        }
    }

    @Schema(name = "CycleCountPage")
    record CountPage(List<CountResponse> items, long total, int page, int size) {
    }
}
