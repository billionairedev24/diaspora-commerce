package com.afrotransact.ai.application;

import java.util.List;

import com.afrotransact.ai.port.ToolSpec;

/** Names, descriptions and JSON schemas of Lola's tools (what the model sees). */
final class LolaToolSpecs {

    static final String SEARCH_PRODUCTS = "search_products";
    static final String COMPARE_OFFERS = "compare_offers";
    static final String CHECK_DELIVERY = "check_delivery";
    static final String ADD_TO_CART = "add_to_cart";
    static final String BUILD_CART_FROM_RECIPE = "build_cart_from_recipe";
    static final String TRACK_ORDER = "track_order";
    static final String START_RETURN = "start_return";
    static final String FIND_SERVICE = "find_service";
    static final String BOOK_SERVICE = "book_service";
    static final String PROCEED_TO_CHECKOUT = "proceed_to_checkout";
    static final String HANDOFF_TO_SUPPORT = "handoff_to_support";
    static final String MY_ORDERS = "my_orders";
    static final String VIEW_CART = "view_cart";
    static final String MY_BOOKINGS = "my_bookings";

    static final List<ToolSpec> ALL = List.of(
            new ToolSpec(SEARCH_PRODUCTS, """
                    Search the AfroTransact catalogue (groceries, spices, beauty, fashion, gifts). Returns products \
                    with ATIN, title, price, stock and delivery days. Use it before mentioning any product.""", """
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"What to look for, e.g. 'jollof seasoning' or 'egusi'"},
                      "maxPriceMinor":{"type":"integer","minimum":0,"description":"Price ceiling in cents"},
                      "inStockOnly":{"type":"boolean","default":true},
                      "community":{"type":"string","description":"Cuisine community, e.g. Nigerian, Ghanaian, Indian"}
                    },"required":["query"],"additionalProperties":false}"""),
            new ToolSpec(COMPARE_OFFERS, """
                    Compare the sellers offering one product (price, delivery days, fulfilment). Use an ATIN \
                    returned by search_products.""", """
                    {"type":"object","properties":{
                      "atin":{"type":"string","pattern":"^AT[0-9A-Z]{8}$"}
                    },"required":["atin"],"additionalProperties":false}"""),
            new ToolSpec(CHECK_DELIVERY, """
                    Delivery promise for a product to a US ZIP code (e.g. 'Tomorrow, Oct 10'). Uses the shopper's \
                    deliver-to ZIP when zip is omitted.""", """
                    {"type":"object","properties":{
                      "atin":{"type":"string","pattern":"^AT[0-9A-Z]{8}$"},
                      "zip":{"type":"string","pattern":"^[0-9]{5}$"}
                    },"required":["atin"],"additionalProperties":false}"""),
            new ToolSpec(ADD_TO_CART, """
                    Prepare an 'Add to cart' button for one product returned by a tool. Nothing is added until the \
                    shopper taps it.""", """
                    {"type":"object","properties":{
                      "atin":{"type":"string","pattern":"^AT[0-9A-Z]{8}$"},
                      "quantity":{"type":"integer","minimum":1,"maximum":99,"default":1}
                    },"required":["atin"],"additionalProperties":false}"""),
            new ToolSpec(BUILD_CART_FROM_RECIPE, """
                    Turn a dish or event menu into a cart: give every ingredient with the number of packs needed for \
                    the servings. Each ingredient is matched to a real in-stock product; the shopper gets one \
                    'Add all to cart' button. Ingredients with no match are reported, never invented.""", """
                    {"type":"object","properties":{
                      "dish":{"type":"string","description":"e.g. 'Jollof rice' or 'Diwali dinner'"},
                      "servings":{"type":"integer","minimum":1,"maximum":500},
                      "ingredients":{"type":"array","minItems":1,"maxItems":40,"items":{"type":"object","properties":{
                        "name":{"type":"string","description":"Ingredient as a shopper would search it"},
                        "quantity":{"type":"integer","minimum":1,"maximum":99,"description":"Packs to buy"}
                      },"required":["name"],"additionalProperties":false}}
                    },"required":["dish","ingredients"],"additionalProperties":false}"""),
            new ToolSpec(TRACK_ORDER, """
                    Status and shipments of one of the shopper's orders.""", """
                    {"type":"object","properties":{
                      "orderId":{"type":"string","description":"Order number or id"}
                    },"required":["orderId"],"additionalProperties":false}"""),
            new ToolSpec(MY_ORDERS, """
                    The shopper's recent orders, newest first, with order number, status, date and total. Use it for \
                    'what did I order', 'where are my orders' or to find an order number; then track_order for details.""",
                    """
                    {"type":"object","properties":{
                      "limit":{"type":"integer","minimum":1,"maximum":10,"default":5}
                    },"additionalProperties":false}"""),
            new ToolSpec(VIEW_CART, """
                    What is in the shopper's cart now: items, quantities, prices, anything unavailable or with a \
                    changed price, and the subtotal.""", """
                    {"type":"object","properties":{},"additionalProperties":false}"""),
            new ToolSpec(MY_BOOKINGS, """
                    The shopper's service bookings (braiders, caterers, tailors ...): reference, service, status, \
                    start time, price and deposit.""", """
                    {"type":"object","properties":{},"additionalProperties":false}"""),
            new ToolSpec(START_RETURN, """
                    Prepare a 'Start a return' button for an order. The return is only created when the shopper taps \
                    it.""", """
                    {"type":"object","properties":{
                      "orderId":{"type":"string"},
                      "reason":{"type":"string","enum":["DAMAGED","WRONG_ITEM","NOT_AS_DESCRIBED","EXPIRED","NO_LONGER_NEEDED","OTHER"]},
                      "items":{"type":"array","items":{"type":"string","description":"ATINs to return; empty = whole order"}}
                    },"required":["orderId","reason"],"additionalProperties":false}"""),
            new ToolSpec(FIND_SERVICE, """
                    Find bookable services and professionals (braiders, caterers, tailors, makeup artists, \
                    photographers) near a ZIP.""", """
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"e.g. 'knotless braids' or 'Nigerian caterer'"},
                      "zip":{"type":"string","pattern":"^[0-9]{5}$"}
                    },"required":["query"],"additionalProperties":false}"""),
            new ToolSpec(BOOK_SERVICE, """
                    Prepare a booking button for a service returned by find_service. The booking (and any deposit) \
                    only happens when the shopper taps it.""", """
                    {"type":"object","properties":{
                      "serviceId":{"type":"string"},
                      "preferredTime":{"type":"string","description":"ISO date-time or a phrase like 'Saturday morning'"},
                      "notes":{"type":"string","maxLength":500}
                    },"required":["serviceId"],"additionalProperties":false}"""),
            new ToolSpec(PROCEED_TO_CHECKOUT, """
                    Prepare a 'Go to checkout' button. Payment always happens in checkout after the shopper's tap.""",
                    """
                    {"type":"object","properties":{},"additionalProperties":false}"""),
            new ToolSpec(HANDOFF_TO_SUPPORT, """
                    Hand the conversation to a human support agent when the shopper asks for a person, is upset, or \
                    you cannot help (payments, account access, disputes).""", """
                    {"type":"object","properties":{
                      "reason":{"type":"string","description":"Short reason"},
                      "summary":{"type":"string","description":"2-3 sentences for the agent: what the shopper needs"}
                    },"required":["reason","summary"],"additionalProperties":false}"""));

    private LolaToolSpecs() {
    }
}
