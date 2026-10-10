package com.afrotransact.bookings.config;

import com.afrotransact.bookings.application.BookingsSettings;
import com.afrotransact.bookings.domain.BalanceChargePolicy;
import com.afrotransact.bookings.domain.DefaultSlotGenerator;
import com.afrotransact.bookings.domain.DefaultTakeHomeCalculator;
import com.afrotransact.bookings.domain.FeeSchedule;
import com.afrotransact.bookings.domain.SlotGenerator;
import com.afrotransact.bookings.domain.TakeHomeCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and business settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingsProperties.class)
class BookingsConfiguration {

    @Bean
    SlotGenerator slotGenerator() {
        return new DefaultSlotGenerator();
    }

    @Bean
    TakeHomeCalculator takeHomeCalculator() {
        return new DefaultTakeHomeCalculator();
    }

    @Bean
    BookingsSettings bookingsSettings(BookingsProperties props) {
        BookingsProperties.Fees f = props.fees();
        BookingsProperties.Balance b = props.balance();
        return new BookingsSettings(props.holdTtl(), props.requestSla(), props.autoCompleteAfter(),
                props.lateProviderFeeMinor(), props.manageUrlBase(),
                new BalanceChargePolicy(b.authorisationWindow(), b.chargeBefore(), b.maxAttempts(), b.retryDelay()),
                new FeeSchedule(f.commissionBps(), f.cardPctBps(), f.cardFixedMinor()), f.cacheTtl(),
                props.retailOrgId());
    }
}
