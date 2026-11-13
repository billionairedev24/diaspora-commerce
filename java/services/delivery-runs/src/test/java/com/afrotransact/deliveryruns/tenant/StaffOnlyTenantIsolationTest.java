package com.afrotransact.deliveryruns.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * Every delivery-runs use case that is not on the explicit exemption list (public, token link, self-service,
 * org-scoped pick lists) is staff or service territory: a seller member (with the permissions seller roles really
 * carry), a customer and a driver are refused with 403 before anything is read. Drives the real application
 * services, with mocked ports, through their public interfaces.
 */
class StaffOnlyTenantIsolationTest {

    /** Interface method names that are open by design; each is covered by another test or is not tenant data. */
    private static final Set<String> EXEMPT = Set.of(
            // public, no tenant data
            "PublicRunService.*", "PreorderPublicService.resolve", "PreorderPublicService.open",
            "AdvertorialServing.serve", "AdvertorialServing.record", "AttributionService.visit",
            // public email sign-up and token links (SubscriptionTenantIsolationTest)
            "SubscriptionService.subscribeEmail", "SubscriptionService.confirm", "SubscriptionService.unsubscribe",
            "PreorderAudienceService.joinWaitlist", "PreorderAudienceService.leaveWaitlist",
            // signed-in shopper's own subscriptions (SubscriptionTenantIsolationTest)
            "SubscriptionService.mine", "SubscriptionService.subscribeMine", "SubscriptionService.unsubscribeMine",
            // seller pick lists (RunPickupTenantIsolationTest)
            "RunPickListService.forSeller", "RunPickListService.forRun",
            // internal: schedulers, event consumers, projections (no HTTP route, no caller identity)
            "RunSchedulerService.*", "PreorderSchedulerService.*", "AttributionService.signupRequested",
            "AttributionService.signupActivated", "AttributionService.orderPlaced",
            "PreorderProjectionService.*", "RunProgressService.onShipmentMoved", "RunProgressService.onShipmentDelivered",
            "RunProgressService.onShipmentCancelled", "RunService.fromWindow", "PreorderSettingsService.seedDemoAreas",
            "DeliveryFeeService.publicRules", "AdvertorialLifecycle.*",
            "RunMoneyService.*", "PreorderDeliveryDayService.*", "PreorderHandover.*");

    private final TenantFixture tenants = new TenantFixture();

    @Test
    void sellersCustomersAndDriversAreRefusedEverywhereExceptTheDeclaredOpenUseCases() {
        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (Class<?> impl : services()) {
            Object service = instantiate(impl);
            for (Class<?> iface : impl.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    String id = iface.getSimpleName() + "." + m.getName();
                    if (m.isDefault() || Modifier.isStatic(m.getModifiers()) || exempt(iface, m)) {
                        continue;
                    }
                    for (Actor actor : actors()) {
                        tenants.as(actor);
                        checked++;
                        String outcome = outcome(service, m);
                        if (!"403".equals(outcome)) {
                            leaks.add(id + " as " + actor.type() + " -> " + outcome);
                        }
                    }
                }
            }
        }
        assertThat(checked).as("use cases exercised").isGreaterThan(100);
        assertThat(leaks).as("use cases reachable by a non-staff principal").isEmpty();
    }

    private static boolean exempt(Class<?> iface, Method m) {
        return EXEMPT.contains(iface.getSimpleName() + "." + m.getName())
                || EXEMPT.contains(iface.getSimpleName() + ".*");
    }

    private static List<Actor> actors() {
        return List.of(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read", "orders:update-status"),
                TenantFixture.providerOf(TenantFixture.ORG_A, "orders:read"),
                TenantFixture.customer(TenantFixture.CUSTOMER_1),
                TenantFixture.driver("drv-1", "delivery:operate"));
    }

    private String outcome(Object service, Method m) {
        try {
            m.invoke(service, defaults(m.getParameterTypes()));
            return "returned";
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof ForbiddenActionException ? "403" : e.getCause().toString();
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
    }

    private static Object[] defaults(Class<?>[] types) {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = defaultOf(types[i]);
        }
        return out;
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0d;
        }
        if (t == Optional.class) {
            return Optional.empty();
        }
        if (t == List.class) {
            return List.of();
        }
        if (t == Set.class) {
            return Set.of();
        }
        return null;
    }

    private Object instantiate(Class<?> impl) {
        Constructor<?> c = impl.getDeclaredConstructors()[0];
        c.setAccessible(true);
        Class<?>[] types = c.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == PermissionChecker.class) {
                args[i] = tenants.permissions();
            } else if (t == ActorResolver.class) {
                args[i] = tenants.resolver();
            } else if (t == ResourceAccessChecker.class) {
                args[i] = tenants.access();
            } else if (t == Duration.class) {
                args[i] = Duration.ZERO;
            } else if (t == String.class) {
                args[i] = "";
            } else if (t.isPrimitive()) {
                args[i] = defaultOf(t);
            } else {
                args[i] = mock(t);
            }
        }
        try {
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build " + impl, e);
        }
    }

    private static Set<Class<?>> services() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        Set<Class<?>> out = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        for (BeanDefinition def : scanner.findCandidateComponents("com.afrotransact.deliveryruns.application")) {
            try {
                out.add(ClassUtils.forName(def.getBeanClassName(),
                        StaffOnlyTenantIsolationTest.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }
}
