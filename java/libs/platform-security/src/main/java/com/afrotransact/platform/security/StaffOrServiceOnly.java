package com.afrotransact.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A controller (or one handler method) that only AfroTransact staff or service clients may call. Enforced before the
 * handler runs by {@link StaffOrServiceOnlyInterceptor}: the principal type must be STAFF or SERVICE (or a super
 * admin). It is an addition to the permission check in the use case, not a replacement: it keeps a permission that
 * is ever granted to an org-member, driver, shopper or agent role from opening a platform-wide route.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface StaffOrServiceOnly {
}
