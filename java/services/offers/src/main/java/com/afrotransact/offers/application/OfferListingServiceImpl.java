package com.afrotransact.offers.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxEvaluator.Evaluated;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferListingServiceImpl implements OfferListingService {

    private final OfferRepository offers;
    private final BuyBoxEvaluator evaluator;

    OfferListingServiceImpl(OfferRepository offers, BuyBoxEvaluator evaluator) {
        this.offers = offers;
        this.evaluator = evaluator;
    }

    @Override
    @Transactional(readOnly = true)
    public BuyBoxView offers(String atin, String currency) {
        return buyBoxes(List.of(atin), currency).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BuyBoxView> buyBoxes(List<String> atins, String currency) {
        Set<String> wanted = new LinkedHashSet<>();
        atins.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.trim().toUpperCase(Locale.ROOT))
                .forEach(wanted::add);
        if (wanted.isEmpty() || wanted.size() > MAX_BATCH) {
            throw new OfferRuleViolation("Ask for 1 to " + MAX_BATCH + " ATINs");
        }
        String cur = normalise(currency);
        List<OfferView> views = evaluator.views(offers.findOpenByAtins(wanted));
        List<BuyBoxView> out = new ArrayList<>();
        for (String atin : wanted) {
            out.add(evaluator.buyBoxView(evaluator.decide(atin, cur, views)));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BuyBoxView> productBuyBoxes(String productAtin, String currency) {
        String cur = normalise(currency);
        List<OfferView> views = evaluator.views(offers.findOpenByProductAtin(productAtin.trim()
                .toUpperCase(Locale.ROOT)));
        Set<String> atins = new TreeSet<>();
        views.forEach(v -> atins.add(v.offer().atin()));
        List<BuyBoxView> out = new ArrayList<>();
        for (String atin : atins) {
            out.add(evaluator.buyBoxView(evaluator.decide(atin, cur, views)));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public ListedOffer offer(UUID offerId) {
        Offer offer = offers.findById(offerId).filter(o -> o.status() != OfferStatus.WITHDRAWN)
                .orElseThrow(() -> new NotFoundException("offers.offer-not-found", "Offer " + offerId + " not found"));
        List<OfferView> views = evaluator.views(offers.findOpenByAtins(List.of(offer.atin())));
        Evaluated e = evaluator.decide(offer.atin(), offer.currency(), views);
        return evaluator.listedOffer(e, offerId);
    }

    private static String normalise(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
    }
}
