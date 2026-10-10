package com.afrotransact.identity.domain;

import java.util.Set;

/**
 * The ways a user can sign in; used to stop people locking themselves out.
 *
 * @param password  has a password
 * @param passkeys  number of registered passkeys
 * @param providers linked social providers (google, apple, facebook)
 */
public record SignInMethods(boolean password, int passkeys, Set<String> providers) {

    public SignInMethods {
        providers = Set.copyOf(providers);
    }

    public int count() {
        return (password ? 1 : 0) + passkeys + providers.size();
    }

    /** A provider can be unlinked only if another sign-in method remains. */
    public boolean canUnlink(String provider) {
        return providers.contains(provider) && count() > 1;
    }

    public boolean canRemovePasskey() {
        return passkeys > 0 && count() > 1;
    }
}
