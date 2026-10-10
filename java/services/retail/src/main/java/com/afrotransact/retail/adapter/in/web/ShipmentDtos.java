package com.afrotransact.retail.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.AsnLineInput;
import com.afrotransact.retail.application.BookShipmentCommand;
import com.afrotransact.retail.domain.AsnLine;
import com.afrotransact.retail.domain.Carton;
import com.afrotransact.retail.domain.DocumentStatus;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.Milestone;
import com.afrotransact.retail.domain.ShipmentDocument;
import com.afrotransact.retail.domain.TransportMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request and response bodies for inbound shipments and ASNs. */
final class ShipmentDtos {

    private ShipmentDtos() {
    }

    @Schema(name = "AsnLineRequest", description = "Units of a PO line with the supplier's batch (lot, made, expiry)")
    record AsnLineRequest(@Positive int poLineNo, @Positive int qty, @Size(max = 20) String lotNo, LocalDate mfgDate,
                          LocalDate expDate) {

        AsnLineInput toInput() {
            return new AsnLineInput(poLineNo, qty, lotNo, mfgDate, expDate);
        }
    }

    @Schema(name = "BookShipmentRequest", description = "Without lines the ASN ships every open PO quantity")
    record BookShipmentRequest(@NotNull UUID poId, @NotNull TransportMode mode, @Size(max = 120) String carrier,
                               @Schema(example = "MSCU 482913 7") @Size(max = 120) String trackingRef,
                               @Size(max = 120) String billOfLading, @Size(max = 200) String originPort,
                               @Size(max = 200) String destinationPort, LocalDate etd, LocalDate eta,
                               @Size(max = 200) List<@Valid AsnLineRequest> lines,
                               @Size(max = 20) List<@Valid ChargeDto> charges) {

        BookShipmentCommand toCommand() {
            return new BookShipmentCommand(poId, mode, carrier, trackingRef, billOfLading, originPort, destinationPort,
                    etd, eta, lines == null ? List.of() : lines.stream().map(AsnLineRequest::toInput).toList(),
                    charges == null ? List.of() : charges.stream().map(ChargeDto::toDomain).toList());
        }
    }

    @Schema(name = "CartonDto", description = "GS1-128 case: SSCC (18 digits, check digit) and units of one ASN line")
    record CartonDto(@NotBlank @Size(min = 18, max = 18) String sscc, @Positive int asnLineNo, @Positive int qty) {

        Carton toDomain() {
            return new Carton(sscc, asnLineNo, qty);
        }
    }

    @Schema(name = "AsnRequest", description = "Supplier packing list; editable until departure")
    record AsnRequest(@NotEmpty @Size(max = 200) List<@Valid AsnLineRequest> lines,
                      @Size(max = 5000) List<@Valid CartonDto> cartons) {

        List<AsnLineInput> toInputs() {
            return lines.stream().map(AsnLineRequest::toInput).toList();
        }

        List<Carton> toCartons() {
            return cartons == null ? List.of() : cartons.stream().map(CartonDto::toDomain).toList();
        }
    }

    @Schema(name = "DocumentRequest", description = "MISSING blocks departure; HOLD blocks customs clearance")
    record DocumentRequest(@NotBlank @Size(max = 80) String name, @NotNull DocumentStatus status,
                           @Size(max = 120) String reference, @Size(max = 500) String note,
                           @Schema(description = "Files of the document as media asset ids (finalised uploads, e.g. "
                                   + "the supplier's ASN / packing list). Omit to keep the files attached; [] removes "
                                   + "them.")
                           @Size(max = 10) List<UUID> assetIds) {
    }

    @Schema(name = "ChargesRequest")
    record ChargesRequest(@Size(max = 20) List<@Valid ChargeDto> charges) {
    }

    @Schema(name = "InboundShipment")
    record ShipmentResponse(UUID id, String reference, UUID poId, String poNumber, UUID supplierId, String mode,
                            String carrier, String trackingRef, String billOfLading, String originPort,
                            String destinationPort, String destinationNodeId, LocalDate etd, LocalDate eta,
                            String stage, String stageLabel, boolean asnLocked, List<AsnLine> lines,
                            List<Carton> cartons, List<ShipmentDocument> documents, List<ChargeDto> charges,
                            List<Milestone> milestones, String note, Instant createdAt, Instant updatedAt,
                            long version) {

        static ShipmentResponse of(InboundShipment s) {
            return new ShipmentResponse(s.id(), s.reference(), s.poId(), s.poNumber(), s.supplierId(),
                    s.mode().name(), s.carrier(), s.trackingRef(), s.billOfLading(), s.originPort(),
                    s.destinationPort(), s.destinationNodeId(), s.etd(), s.eta(), s.stage().name(),
                    s.stage().label(), s.asnLocked(), s.lines(), s.cartons(), s.documents(),
                    s.charges().stream().map(ChargeDto::of).toList(), s.milestones(), s.note(), s.createdAt(),
                    s.updatedAt(), s.version());
        }
    }
}
