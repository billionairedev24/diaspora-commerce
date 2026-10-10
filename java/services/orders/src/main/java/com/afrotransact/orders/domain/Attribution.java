package com.afrotransact.orders.domain;

/**
 * Marketing attribution the storefront sends with checkout (S272): last touch, kept for the visit (or 30 days with
 * consent).
 *
 * @param src source: ig, fb, tt, wa, email, sms, app or the on-site placement (hero, ribbon, pdp, page)
 * @param cr  creative slot (HERO, PDP, STORY, FEED, EMAIL)
 * @param po  pre-order window code, e.g. LBK-7
 */
public record Attribution(String src, String cr, String po) {

    private static final int MAX = 40;

    public Attribution {
        src = clean(src);
        cr = clean(cr);
        po = clean(po);
    }

    /** Null when nothing was attributed. */
    public static Attribution ofNullable(String src, String cr, String po) {
        Attribution a = new Attribution(src, cr, po);
        return a.empty() ? null : a;
    }

    public boolean empty() {
        return src == null && cr == null && po == null;
    }

    private static String clean(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        return t.length() > MAX ? t.substring(0, MAX) : t;
    }
}
