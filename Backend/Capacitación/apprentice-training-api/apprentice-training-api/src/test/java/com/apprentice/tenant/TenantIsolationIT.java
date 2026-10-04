package com.apprentice.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.apprentice.workmap.Workmap;
import com.apprentice.workmap.WorkmapRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Dos empresas con datos: cada una solo ve lo suyo. */
@SpringBootTest(properties = "apprentice.demo-data=false")
@Testcontainers
class TenantIsolationIT {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired TenantRepository tenants;
  @Autowired WorkmapRepository workmaps;

  @Test
  void cadaEmpresaVeSoloSusWorkmaps() {
    Tenant a = tenants.save(new Tenant("empresa-a", "A", TenantFilter.sha256("key-a")));
    Tenant b = tenants.save(new Tenant("empresa-b", "B", TenantFilter.sha256("key-b")));

    TenantContext.set(a);
    try {
      Workmap wm = new Workmap();
      wm.setTitle("Proceso de A");
      workmaps.save(wm);
      assertEquals(1, workmaps.findAll().size());
    } finally {
      TenantContext.clear();
    }

    TenantContext.set(b);
    try {
      assertEquals(0, workmaps.findAll().size(), "B no debe ver los datos de A");
    } finally {
      TenantContext.clear();
    }
  }
}
