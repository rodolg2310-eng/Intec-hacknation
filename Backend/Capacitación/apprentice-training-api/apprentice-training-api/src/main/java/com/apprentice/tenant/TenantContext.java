package com.apprentice.tenant;

import java.util.UUID;

/** Tenant activo del hilo actual. Lo fija el TenantFilter y lo lee Hibernate. */
public final class TenantContext {

  private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(Tenant tenant) {
    CURRENT.set(tenant);
  }

  public static Tenant get() {
    Tenant t = CURRENT.get();
    if (t == null) throw new IllegalStateException("No hay tenant en el contexto");
    return t;
  }

  public static UUID getId() {
    return get().getId();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
