package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.DeviceService;
import com.afrotransact.identity.application.RegisterDeviceCommand;
import com.afrotransact.identity.domain.DevicePlatform;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.PushDevice;
import com.afrotransact.identity.domain.PushProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Devices", description = "Push notification devices of the signed-in shopper (customer app, web) or "
        + "driver (driver app)")
@RestController
class DeviceController {

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @Schema(name = "RegisterDeviceRequest")
    record RegisterDeviceRequest(
            @NotBlank @Schema(allowableValues = {"ios", "android", "web"}) String platform,
            @NotBlank @Size(max = 4096) @Schema(description = "FCM registration token or APNs device token")
            String token,
            @NotBlank @Schema(allowableValues = {"customer", "driver"}) String app,
            @Size(max = 120) @Schema(examples = "Ada's iPhone") String deviceName,
            @Schema(allowableValues = {"fcm", "apns"}, description = "Push transport; default apns for ios, fcm for "
                    + "android and web") String provider) {
    }

    @Schema(name = "UnregisterDeviceRequest")
    record UnregisterDeviceRequest(@NotBlank @Size(max = 4096) String token) {
    }

    @Schema(name = "Device")
    record DeviceResponse(UUID id, @Schema(allowableValues = {"customer", "driver"}) String app,
                          @Schema(allowableValues = {"ios", "android", "web"}) String platform,
                          @Schema(allowableValues = {"fcm", "apns"}) String provider,
                          @Schema(description = "Last 6 characters of the token") String tokenSuffix,
                          String deviceName, Instant createdAt, Instant lastSeenAt) {

        static DeviceResponse of(PushDevice d) {
            return new DeviceResponse(d.id(), lower(d.app().name()), lower(d.platform().name()), d.provider().wire(),
                    d.tokenSuffix(), d.deviceName(), d.createdAt(), d.lastSeenAt());
        }
    }

    @Operation(summary = "Devices registered for push notifications")
    @GetMapping("/v1/me/devices")
    List<DeviceResponse> list() {
        return devices.list().stream().map(DeviceResponse::of).toList();
    }

    @Operation(summary = "Register or refresh a push token (call on every app start and token refresh)")
    @PostMapping("/v1/me/devices")
    @ResponseStatus(HttpStatus.CREATED)
    DeviceResponse register(@Valid @RequestBody RegisterDeviceRequest body) {
        PushProvider provider = body.provider() == null || body.provider().isBlank() ? null
                : PushProvider.valueOf(upper(body.provider()));
        return DeviceResponse.of(devices.register(new RegisterDeviceCommand(PushApp.valueOf(upper(body.app())),
                DevicePlatform.valueOf(upper(body.platform())), provider, body.token(), body.deviceName())));
    }

    @Operation(summary = "Unregister a push token (sign-out); succeeds when it is not registered")
    @DeleteMapping("/v1/me/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@Valid @RequestBody UnregisterDeviceRequest body) {
        devices.unregister(body.token());
    }

    @Operation(summary = "Remove a registered device")
    @DeleteMapping("/v1/me/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID deviceId) {
        devices.remove(deviceId);
    }

    private static String upper(String s) {
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
