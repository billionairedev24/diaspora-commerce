package com.afrotransact.module;

import java.util.List;

/**
 * Anonymous paths a module needs beyond the platform's {@code at.security.public-paths}/{@code public-post-paths}: the module
 * declares them once; the standalone service's own filter chain and the core application's shared chain both read this, so a
 * public storefront or webhook permit cannot be lost when modules share one JVM.
 */
public interface SecurityPermits {

    /** Anonymous GET ant patterns. */
    List<String> publicGets();

    /** Anonymous POST ant patterns (signed webhooks, sign-ups, visitor forms). */
    List<String> publicPosts();
}
