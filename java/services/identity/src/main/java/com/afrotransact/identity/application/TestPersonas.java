package com.afrotransact.identity.application;

import java.util.List;

/**
 * The local test accounts (S276): one per persona, every staff role in the V2 role catalogue, all sharing one password
 * ({@code Test1234!Local} locally). Organisations are the ones {@code make seed} creates (tools/seed/seedlib/dataset.py)
 * plus AfroTransact Retail. Keep tools/seed/seedlib/dataset.py {@code TEST_USERS}, tools/dev/local.sh and
 * docs/engineering/local-development.md in step (make seed-test checks the emails match).
 */
public final class TestPersonas {

    public static final String STOREFRONT = "storefront";
    public static final String SELLER_CENTER = "Seller Center";
    public static final String STAFF_CONSOLE = "staff console";
    public static final String DRIVER_APP = "driver app";

    private static final String CUSTOMER = "account.customer";
    private static final String MAMA_PUT = "org_mama_put_pantry";

    public static final List<TestPersona> ALL = List.of(
            shopper("customer.houston", "Adaeze", "Okafor", "Customer, Houston 77042",
                    new TestPersona.Address("Home", "10200 Richmond Ave", "Houston", "TX", "77042", 29.7316,
                            -95.5603, "Ring the bell at the gate")),
            shopper("customer.lubbock", "Kwame", "Asare", "Customer, Lubbock 79401 (open pre-order)",
                    new TestPersona.Address("Home", "1500 Broadway", "Lubbock", "TX", "79401", 33.5846, -101.8461,
                            "Leave with the front desk")),
            member("seller.owner", "Ngozi", "Eze", "Owner, Mama Put Pantry", MAMA_PUT, "seller.owner"),
            member("seller.staff", "Tolu", "Bello", "Catalog manager, Mama Put Pantry", MAMA_PUT, "seller.catalog"),
            member("seller.bamako", "Awa", "Diarra", "Owner, Bamako Home", "org_bamako_home", "seller.owner"),
            member("provider.owner", "Aminata", "Diallo", "Owner, Aminata's Braids (Provider Hub)",
                    "org_aminatas_braids", "provider.owner"),
            member("retail.operator", "Efua", "Asante", "Operator, AfroTransact Retail (1P)",
                    "org_afrotransact_retail", "seller.admin"),
            staff("staff.admin", "Folake", "Adeyemi", "Super Admin", "platform.super_admin", "0101"),
            staff("staff.ops", "Chidi", "Nwosu", "Operations Manager", "platform.operations_manager", "0102"),
            staff("staff.trust", "Selam", "Tesfaye", "Trust & Safety", "platform.trust_safety", "0103"),
            staff("staff.finance", "Kofi", "Boateng", "Finance", "platform.finance", "0104"),
            staff("staff.support", "Amara", "Okonkwo", "Customer Support", "platform.customer_support", "0105"),
            staff("staff.marketing", "Zainab", "Musa", "Marketing (content, ads)", "platform.marketing", "0106"),
            staff("staff.catalog", "Yaw", "Owusu", "Catalog Manager", "platform.catalog_manager", "0107"),
            staff("staff.retail", "Efe", "Okoro", "Retail Buyer (1P)", "platform.retail_buyer", "0108"),
            staff("staff.fulfilment", "Musa", "Ibrahim", "Fulfilment Ops", "platform.fulfilment_ops", "0109"),
            staff("staff.inventory", "Abena", "Darko", "Inventory Manager", "platform.inventory_manager", "0110"),
            staff("staff.dispatch", "Segun", "Lawal", "Dispatcher (delivery runs)", "platform.dispatcher",
                    "0111"),
            staff("staff.product", "Wanjiru", "Kamau", "Product Owner", "platform.product_owner", "0112"),
            staff("staff.developer", "Obinna", "Uche", "Developer", "platform.developer", "0113"),
            staff("staff.auditor", "Hana", "Bekele", "Auditor (read-only)", "platform.auditor", "0114"),
            new TestPersona(email("driver"), "Emeka", "Obi", "+17135550199", "Driver", DRIVER_APP,
                    List.of("platform.driver"), null, null, null));

    private TestPersonas() {
    }

    private static String email(String local) {
        return local + "@afrotransact.test";
    }

    private static TestPersona shopper(String local, String first, String last, String persona,
                                       TestPersona.Address address) {
        return new TestPersona(email(local), first, last, null, persona, STOREFRONT, List.of(CUSTOMER), null, null,
                address);
    }

    private static TestPersona member(String local, String first, String last, String persona, String orgId,
                                      String orgRole) {
        return new TestPersona(email(local), first, last, null, persona, SELLER_CENTER, List.of(CUSTOMER), orgId,
                orgRole, null);
    }

    /** Staff phones are fictional (555-01xx); their texted codes land in Mailpit locally. */
    private static TestPersona staff(String local, String first, String last, String persona, String role,
                                     String line) {
        return new TestPersona(email(local), first, last, "+1713555" + line, persona, STAFF_CONSOLE, List.of(role),
                null, null, null);
    }
}
