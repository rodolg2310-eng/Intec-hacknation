package com.apprentice.tenant;

import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conecta el TenantContext con el @TenantId de Hibernate. */
@Configuration
public class TenantHibernateConfig {

  @Bean
  CurrentTenantIdentifierResolver<UUID> tenantIdentifierResolver() {
    return new CurrentTenantIdentifierResolver<>() {
      @Override
      public UUID resolveCurrentTenantIdentifier() {
        try { return TenantContext.getId(); }
        catch (IllegalStateException noTenant) { return new UUID(0, 0); }
      }

      @Override
      public boolean validateExistingCurrentSessions() {
        return true;
      }
    };
  }

  @Bean
  HibernatePropertiesCustomizer hibernateTenantCustomizer(
      CurrentTenantIdentifierResolver<UUID> resolver) {
    return props -> props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
  }
}
