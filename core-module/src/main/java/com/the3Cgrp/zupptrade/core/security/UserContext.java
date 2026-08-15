package com.the3Cgrp.zupptrade.core.security;

import java.util.Optional;

/**
 * Holds the authenticated user for the current request thread. A ThreadLocal-backed singleton
 * (not request-scoped) so the identity filter can populate it before the DispatcherServlet runs —
 * request scope isn't bound that early. The populating filter must {@link #clear()} in a finally
 * block to avoid leaking identity across pooled threads.
 */
public class UserContext {

    private final ThreadLocal<AuthenticatedUser> holder = new ThreadLocal<>();

    public void set(AuthenticatedUser user) { holder.set(user); }

    public Optional<AuthenticatedUser> current() { return Optional.ofNullable(holder.get()); }

    public boolean isAuthenticated() { return holder.get() != null; }

    public void clear() { holder.remove(); }
}
