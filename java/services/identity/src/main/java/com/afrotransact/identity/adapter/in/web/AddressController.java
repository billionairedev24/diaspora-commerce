package com.afrotransact.identity.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.application.AddressBookService;
import com.afrotransact.identity.application.AddressBookService.Coordinates;
import com.afrotransact.identity.domain.AddressBookPolicy.AddressDraft;
import com.afrotransact.identity.domain.PostalAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Address book", description = "The signed-in customer's saved delivery addresses")
@RestController
class AddressController {

    private final AddressBookService book;

    AddressController(AddressBookService book) {
        this.book = book;
    }

    @Schema(name = "AddressRequest", description = "Fill from address autocomplete (geo service); verified = geo validation passed")
    record AddressRequest(@Size(max = 30) String label, @NotBlank @Size(max = 100) String fullName,
                          @NotBlank String phone, String phoneRegion, @NotBlank @Size(max = 120) String line1,
                          @Size(max = 60) String line2, @NotBlank String city, String state,
                          @NotBlank String postalCode, String country, Double latitude, Double longitude,
                          @Size(max = 300) String instructions, Boolean verified, Boolean makeDefault) {

        AddressDraft draft() {
            return new AddressDraft(label, fullName, phone, phoneRegion, line1, line2, city, state, postalCode, country,
                    instructions);
        }

        Coordinates coordinates() {
            return new Coordinates(latitude, longitude);
        }

        boolean isVerified() {
            return Boolean.TRUE.equals(verified);
        }

        boolean wantsDefault() {
            return Boolean.TRUE.equals(makeDefault);
        }
    }

    @Schema(name = "Address")
    record AddressResponse(UUID id, String label, String fullName, String phone, String line1, String line2,
                           String city, String state, String postalCode, String country, Double latitude,
                           Double longitude, String instructions, boolean verified, boolean isDefault) {

        static AddressResponse of(PostalAddress a) {
            return new AddressResponse(a.id(), a.label(), a.fullName(), a.phone(), a.line1(), a.line2(), a.city(),
                    a.state(), a.postalCode(), a.country(), a.latitude(), a.longitude(), a.instructions(), a.verified(),
                    a.isDefault());
        }
    }

    @Operation(summary = "List saved addresses (default first)")
    @GetMapping("/v1/me/addresses")
    List<AddressResponse> list() {
        return book.list().stream().map(AddressResponse::of).toList();
    }

    @Operation(summary = "A customer's saved addresses, default first (staff late adds and support; users:read)")
    @GetMapping("/v1/admin/users/{userId}/addresses")
    List<AddressResponse> listOf(@PathVariable UUID userId) {
        return book.listOf(userId).stream().map(AddressResponse::of).toList();
    }

    @Operation(summary = "Add an address; the first one becomes the default")
    @PostMapping("/v1/me/addresses")
    ResponseEntity<AddressResponse> add(@Valid @RequestBody AddressRequest body) {
        PostalAddress a = book.add(body.draft(), body.coordinates(), body.isVerified(), body.wantsDefault());
        return ResponseEntity.created(URI.create("/v1/me/addresses/" + a.id())).body(AddressResponse.of(a));
    }

    @Operation(summary = "Edit an address")
    @PatchMapping("/v1/me/addresses/{id}")
    AddressResponse update(@PathVariable UUID id, @Valid @RequestBody AddressRequest body) {
        return AddressResponse.of(book.update(id, body.draft(), body.coordinates(), body.isVerified()));
    }

    @Operation(summary = "Remove an address (the next most recent becomes default if needed)")
    @DeleteMapping("/v1/me/addresses/{id}")
    ResponseEntity<Void> remove(@PathVariable UUID id) {
        book.remove(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Make an address the default")
    @PostMapping("/v1/me/addresses/{id}/default")
    AddressResponse makeDefault(@PathVariable UUID id) {
        return AddressResponse.of(book.makeDefault(id));
    }
}
