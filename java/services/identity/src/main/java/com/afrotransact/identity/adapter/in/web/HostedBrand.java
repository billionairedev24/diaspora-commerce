package com.afrotransact.identity.adapter.in.web;

import java.util.List;

/**
 * Resolved copy for one hosted page: the form heading and the left brand panel.
 *
 * @param app    the app (CSS hook and template switches)
 * @param badge  small label next to the logo for the back-office apps (null for shoppers)
 * @param title  form heading
 * @param tag    gold pill on the brand panel
 * @param head   brand panel headline
 * @param sub    brand panel sub-heading
 * @param points three benefits (icon name, title, text)
 */
record HostedBrand(String app, String badge, String title, String tag, String head, String sub, List<Point> points) {

    record Point(String icon, String title, String text) {
    }
}
