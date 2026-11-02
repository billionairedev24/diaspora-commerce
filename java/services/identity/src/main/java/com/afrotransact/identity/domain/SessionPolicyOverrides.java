package com.afrotransact.identity.domain;

/** The values an admin stored for one audience; null means "use the built-in default". */
public record SessionPolicyOverrides(Integer idleTimeoutMinutes, Integer absoluteTimeoutMinutes,
                                     Integer warnSeconds) {

    public static SessionPolicyOverrides none() {
        return new SessionPolicyOverrides(null, null, null);
    }

    /** A value equal to the default is stored as "use the default", so it keeps following the default. */
    public SessionPolicyOverrides withoutDefaults(SessionPolicy defaults) {
        return new SessionPolicyOverrides(
                same(idleTimeoutMinutes, defaults.idleTimeoutMinutes()),
                same(absoluteTimeoutMinutes, defaults.absoluteTimeoutMinutes()),
                same(warnSeconds, defaults.warnSeconds()));
    }

    private static Integer same(Integer value, int def) {
        return value != null && value == def ? null : value;
    }

    public boolean isEmpty() {
        return idleTimeoutMinutes == null && absoluteTimeoutMinutes == null && warnSeconds == null;
    }
}
