package com.afrotransact.orders.config;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * LOCAL-ONLY fault injection into the checkout saga, to verify by hand that a failing step gives everything back (stock, coupon, delivery
 * run slot, PaymentIntent) and that the shopper gets the failure code. It exists only in the {@code local} Spring profile and only when
 * {@code at.orders.fault-injection.enabled=true} (default false; env {@code AT_ORDERS_FAULT_INJECTION_ENABLED}); under {@code dev},
 * {@code uat} and {@code prod} the bean is never created, whatever the properties say. It touches no production class: it is a Temporal
 * worker interceptor that fails the activities of {@code CheckoutWorkflow} by name.
 *
 * <ul>
 *   <li>{@code at.orders.fault-injection.fail-before}: activity names failed before they run
 *       ({@code Reserve, ReserveRunSlot, RedeemCoupon, CreatePayment, PersistPending, Place}), comma separated;</li>
 *   <li>{@code at.orders.fault-injection.fail-after}: names failed AFTER they ran (the work is done, the answer is lost: a retry must
 *       find it, a compensation must undo it), for example {@code CreatePayment};</li>
 *   <li>{@code at.orders.fault-injection.kind}: {@code UNAVAILABLE} (default: retryable, the saga retries then fails with
 *       {@code orders.upstream-unavailable}) or {@code REJECTED} (not retryable, {@code orders.upstream-rejected});</li>
 *   <li>{@code at.orders.fault-injection.times}: how many calls fail (default: all of them).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "at.orders.fault-injection.enabled", havingValue = "true")
public class LocalCheckoutFaults {

    private static final Logger LOG = LoggerFactory.getLogger(LocalCheckoutFaults.class);

    @Bean
    WorkerInterceptor localCheckoutFaultInterceptor(
            @Value("${at.orders.fault-injection.fail-before:}") String before,
            @Value("${at.orders.fault-injection.fail-after:}") String after,
            @Value("${at.orders.fault-injection.kind:UNAVAILABLE}") String kind,
            @Value("${at.orders.fault-injection.times:2147483647}") int times) {
        LOG.warn("CHECKOUT FAULT INJECTION IS ON (local profile): failing {} before and {} after, kind {}, {} time(s)", before, after,
                kind, times);
        return new Injector(names(before), names(after), "REJECTED".equalsIgnoreCase(kind), times);
    }

    private static Set<String> names(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Public so the test can build one without Spring. */
    public static WorkerInterceptor injector(Set<String> before, Set<String> after, boolean rejected, int times) {
        return new Injector(before, after, rejected, times);
    }

    private static final class Injector extends WorkerInterceptorBase {

        private final Set<String> before;
        private final Set<String> after;
        private final boolean rejected;
        private final AtomicInteger left;

        Injector(Set<String> before, Set<String> after, boolean rejected, int times) {
            this.before = before;
            this.after = after;
            this.rejected = rejected;
            this.left = new AtomicInteger(times);
        }

        @Override
        public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
            return new ActivityInboundCallsInterceptorBase(next) {
                private ActivityInfo info;

                @Override
                public void init(ActivityExecutionContext context) {
                    info = context.getInfo();
                    super.init(context);
                }

                @Override
                public ActivityOutput execute(ActivityInput input) {
                    String name = info.getActivityType();
                    boolean checkout = "CheckoutWorkflow".equals(info.getWorkflowType());
                    if (checkout && before.contains(name) && left.getAndDecrement() > 0) {
                        LOG.warn("Fault injection: failing {} BEFORE it runs", name);
                        throw failure(name);
                    }
                    ActivityOutput out = super.execute(input);
                    if (checkout && after.contains(name) && left.getAndDecrement() > 0) {
                        LOG.warn("Fault injection: failing {} AFTER it ran (the answer is lost)", name);
                        throw failure(name);
                    }
                    return out;
                }
            };
        }

        private ApplicationFailure failure(String name) {
            String message = "Injected failure of " + name + " (local fault injection)";
            return rejected ? ApplicationFailure.newNonRetryableFailure(message, "orders.upstream-rejected")
                    : ApplicationFailure.newFailure(message, "orders.upstream-unavailable");
        }
    }
}
