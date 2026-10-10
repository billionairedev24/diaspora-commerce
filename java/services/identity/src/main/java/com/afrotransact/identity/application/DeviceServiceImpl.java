package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.DeviceChanged;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.PushDevice;
import com.afrotransact.identity.domain.PushProvider;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.port.PushDeviceRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceServiceImpl implements DeviceService {

    private final CurrentUser current;
    private final PushDeviceRepository devices;
    private final DomainEventPublisher events;
    private final Clock clock;

    DeviceServiceImpl(CurrentUser current, PushDeviceRepository devices, DomainEventPublisher events, Clock clock) {
        this.current = current;
        this.devices = devices;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PushDevice> list() {
        return devices.findByUser(current.id());
    }

    @Override
    @Transactional
    public PushDevice register(RegisterDeviceCommand c) {
        UUID user = current.id();
        if (c.app() == PushApp.DRIVER && !current.actor().can(IdentityPermissions.DELIVERY_OPERATE)) {
            throw new ForbiddenActionException("identity.driver-app-not-allowed",
                    "Only drivers can register the driver app for notifications");
        }
        String token = validToken(c.token());
        String name = c.deviceName() == null || c.deviceName().isBlank() ? null : c.deviceName().trim();
        if (name != null && name.length() > PushDevice.MAX_NAME_LENGTH) {
            name = name.substring(0, PushDevice.MAX_NAME_LENGTH);
        }
        String hash = SecretHashing.sha256Hex(token);
        Instant now = Instant.now(clock);
        Optional<PushDevice> existing = devices.findByTokenHash(hash);
        boolean same = existing.filter(d -> d.userId().equals(user) && d.app() == c.app()).isPresent();
        UUID id = existing.map(PushDevice::id).orElseGet(UUID::randomUUID);
        PushProvider provider = c.provider() != null ? c.provider() : c.platform().defaultProvider();
        PushDevice saved = devices.upsert(new PushDevice(id, user, c.app(), c.platform(), provider, token, hash,
                name, now, now));
        devices.trim(user, c.app(), PushDevice.MAX_PER_APP);
        if (!same) {
            existing.filter(d -> !d.userId().equals(user)).ifPresent(previous -> publish(
                    IdentityEvents.DEVICE_UNREGISTERED, previous, now));
            publish(IdentityEvents.DEVICE_REGISTERED, saved, now);
        }
        return saved;
    }

    @Override
    @Transactional
    public void unregister(String token) {
        UUID user = current.id();
        String hash = SecretHashing.sha256Hex(validToken(token));
        Optional<PushDevice> existing = devices.findByTokenHash(hash).filter(d -> d.userId().equals(user));
        if (existing.isPresent() && devices.deleteByTokenHash(user, hash)) {
            publish(IdentityEvents.DEVICE_UNREGISTERED, existing.get(), Instant.now(clock));
        }
    }

    @Override
    @Transactional
    public void remove(UUID deviceId) {
        UUID user = current.id();
        PushDevice device = devices.findByUser(user).stream().filter(d -> d.id().equals(deviceId)).findFirst()
                .orElseThrow(() -> new NotFoundException("identity.device-not-found", "Device not found"));
        devices.delete(user, deviceId);
        publish(IdentityEvents.DEVICE_UNREGISTERED, device, Instant.now(clock));
    }

    private void publish(String type, PushDevice d, Instant now) {
        events.publish(type, IdentityEvents.AGGREGATE, d.userId().toString(), new DeviceChanged(d.userId().toString(),
                d.id().toString(), lower(d.app().name()), lower(d.platform().name()), d.provider().wire(), now));
    }

    private static String validToken(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty() || t.length() > PushDevice.MAX_TOKEN_LENGTH || t.chars().anyMatch(Character::isWhitespace)) {
            throw new IdentityRuleViolation("identity.invalid-push-token", "Send the device's push token",
                    Map.of("token", "Send the device's push token"));
        }
        return t;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
