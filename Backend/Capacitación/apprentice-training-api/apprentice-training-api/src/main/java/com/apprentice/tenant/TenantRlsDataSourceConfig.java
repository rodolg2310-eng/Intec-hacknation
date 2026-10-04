package com.apprentice.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Cada conexion que sale del pool fija app.tenant_id en Postgres,
 * para que las politicas RLS dejen pasar solo las filas del tenant activo.
 */
@Configuration
@ConditionalOnProperty(name = "apprentice.postgres-rls", havingValue = "true", matchIfMissing = true)
public class TenantRlsDataSourceConfig {

  @Bean
  @Primary
  DataSource tenantAwareDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setUsername(user);
    pool.setPassword(password);

    DelegatingDataSource delegating = new DelegatingDataSource(pool) {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        return apply(super.getConnection());
      }

      @Override
      public Connection getConnection(String username, String pwd) throws java.sql.SQLException {
        return apply(super.getConnection(username, pwd));
      }

      private Connection apply(Connection conn) throws java.sql.SQLException {
        try {
          String tenantId = TenantContext.getId().toString();
          try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
          }
        } catch (IllegalStateException noTenant) {
          try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '', false)");
          }
        }
        return conn;
      }
    };
    return new TransactionAwareDataSourceProxy(delegating);
  }
}
