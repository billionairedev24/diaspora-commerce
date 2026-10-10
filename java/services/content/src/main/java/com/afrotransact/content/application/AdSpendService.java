package com.afrotransact.content.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.port.AdStatsRepository.OrgDaySpend;

/**
 * Charged sponsored-products spend per seller organisation and UTC day, read by seller-service's metered billing
 * (S206; service scope {@code ads:spend.read}) and by ads staff ({@code content:ads.view} / {@code content:ads.edit}).
 * The figures are final once the day is over: clicks are charged in the day they are recorded. AfroTransact Retail's
 * internal campaigns are never listed (see {@link AdsSettings}).
 */
public interface AdSpendService {

    /** Maximum number of days one request may cover. */
    int MAX_DAYS = 93;

    List<OrgDaySpend> spend(LocalDate from, LocalDate to);
}
