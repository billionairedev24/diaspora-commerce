package com.afrotransact.ai.domain.guardrail;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.ai.domain.lola.ProductCard;

/**
 * Everything tools returned during the current Lola turn: the products (by ATIN) and every money amount a tool
 * produced (unit prices, offer prices, cart totals). The grounding verifier accepts nothing else.
 */
public final class GroundingFacts {

    private final Map<String, ProductCard> products = new LinkedHashMap<>();
    private final Set<Long> amountsMinor = new HashSet<>();

    public void addProduct(ProductCard card) {
        products.putIfAbsent(card.atin(), card);
        amountsMinor.add(card.priceMinor());
    }

    public void addProducts(Collection<ProductCard> cards) {
        cards.forEach(this::addProduct);
    }

    /** A money amount returned by a tool (for example an offer price or a cart total), in minor units. */
    public void addAmount(long minor) {
        amountsMinor.add(minor);
    }

    public boolean knows(String atin) {
        return products.containsKey(atin);
    }

    public Optional<ProductCard> product(String atin) {
        return Optional.ofNullable(products.get(atin));
    }

    public boolean allowsAmount(long minor) {
        return amountsMinor.contains(minor);
    }

    public List<ProductCard> products() {
        return List.copyOf(products.values());
    }
}
