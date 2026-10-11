package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.AddressBookPolicy;
import com.afrotransact.identity.domain.AddressBookPolicy.AddressDraft;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.PhoneCheck;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AddressBookServiceImpl implements AddressBookService {

    private final CurrentUser current;
    private final AddressRepository addresses;
    private final AddressBookPolicy policy;
    private final PhoneNumberPolicy phones;
    private final Clock clock;

    AddressBookServiceImpl(CurrentUser current, AddressRepository addresses, AddressBookPolicy policy,
                           PhoneNumberPolicy phones, Clock clock) {
        this.current = current;
        this.addresses = addresses;
        this.policy = policy;
        this.phones = phones;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostalAddress> list() {
        return sorted(addresses.findByUser(current.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostalAddress> listOf(UUID userId) {
        Actor actor = current.actor();
        if (!actor.can(IdentityPermissions.USERS_READ) && !actor.can(IdentityPermissions.ACCESS_READ)
                && !actor.can(IdentityPermissions.ACCESS_ADMIN)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + IdentityPermissions.USERS_READ);
        }
        return sorted(addresses.findByUser(userId));
    }

    @Override
    @Transactional
    public PostalAddress add(AddressDraft draft, Coordinates coordinates, boolean verified, boolean makeDefault) {
        UUID user = current.id();
        if (addresses.count(user) >= AddressBookPolicy.MAX_ADDRESSES) {
            throw new IdentityRuleViolation("identity.address-book-full",
                    "You can save up to " + AddressBookPolicy.MAX_ADDRESSES + " addresses");
        }
        String phone = validate(draft);
        Instant now = Instant.now(clock);
        boolean first = addresses.count(user) == 0;
        PostalAddress a = build(UUID.randomUUID(), user, draft, phone, coordinates, verified, false, now, now);
        addresses.insert(a);
        if (first || makeDefault) {
            addresses.setDefault(user, a.id());
            a = a.asDefault(true);
        }
        return a;
    }

    @Override
    @Transactional
    public PostalAddress update(UUID addressId, AddressDraft draft, Coordinates coordinates, boolean verified) {
        UUID user = current.id();
        PostalAddress existing = find(user, addressId);
        String phone = validate(draft);
        PostalAddress a = build(addressId, user, draft, phone, coordinates, verified, existing.isDefault(),
                existing.createdAt(), Instant.now(clock));
        addresses.update(a);
        return a;
    }

    @Override
    @Transactional
    public void remove(UUID addressId) {
        UUID user = current.id();
        PostalAddress existing = find(user, addressId);
        addresses.delete(user, addressId);
        if (existing.isDefault()) {
            addresses.findByUser(user).stream()
                    .max(Comparator.comparing(PostalAddress::updatedAt))
                    .ifPresent(next -> addresses.setDefault(user, next.id()));
        }
    }

    @Override
    @Transactional
    public PostalAddress makeDefault(UUID addressId) {
        UUID user = current.id();
        PostalAddress a = find(user, addressId);
        addresses.setDefault(user, addressId);
        return a.asDefault(true);
    }

    private PostalAddress find(UUID user, UUID addressId) {
        return addresses.findById(user, addressId)
                .orElseThrow(() -> new NotFoundException("identity.address-not-found", "Address not found"));
    }

    private String validate(AddressDraft d) {
        Map<String, String> errors = new LinkedHashMap<>(policy.check(d));
        String phone = null;
        if (d.phone() != null && !d.phone().isBlank()) {
            PhoneCheck check = phones.check(d.phone(), d.phoneRegion() == null ? "US" : d.phoneRegion());
            if (check.valid()) {
                phone = check.e164();
            } else {
                errors.put("phone", check.error());
            }
        } else {
            errors.put("phone", "A phone number helps the driver reach you");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-address", "Check the highlighted fields", errors);
        }
        return phone;
    }

    private static PostalAddress build(UUID id, UUID user, AddressDraft d, String phone, Coordinates c,
                                       boolean verified, boolean isDefault, Instant created, Instant updated) {
        String country = d.country() == null || d.country().isBlank() ? "US" : d.country().trim().toUpperCase(Locale.ROOT);
        return new PostalAddress(id, user, trim(d.label()), d.fullName().trim(), phone, d.line1().trim(),
                trim(d.line2()), d.city().trim(), d.state() == null ? null : d.state().trim().toUpperCase(Locale.ROOT),
                d.postalCode().trim(), country, c == null ? null : c.latitude(), c == null ? null : c.longitude(),
                trim(d.instructions()), verified, isDefault, created, updated);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<PostalAddress> sorted(List<PostalAddress> list) {
        return list.stream()
                .sorted(Comparator.comparing(PostalAddress::isDefault).reversed()
                        .thenComparing(PostalAddress::updatedAt, Comparator.reverseOrder()))
                .toList();
    }
}
