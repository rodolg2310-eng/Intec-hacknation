package com.apprentice.demo;

import com.apprentice.cases.CaseRepository;
import com.apprentice.cases.PracticeCase;
import com.apprentice.learner.Learner;
import com.apprentice.learner.LearnerRepository;
import com.apprentice.tenant.Tenant;
import com.apprentice.tenant.TenantContext;
import com.apprentice.tenant.TenantFilter;
import com.apprentice.tenant.TenantRepository;
import com.apprentice.workmap.*;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Datos de ejemplo (solo si apprentice.demo-data=true): empresa "demo"
 * con el proceso de facturas de Sabine, 6 casos y la alumna Lena.
 * Clave demo: demo-key (cambiar en produccion).
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

  private final boolean enabled;
  private final TenantRepository tenants;
  private final WorkmapRepository workmaps;
  private final StepRepository steps;
  private final GuardrailRepository guardrails;
  private final CaseRepository cases;
  private final LearnerRepository learners;
  private final TransactionTemplate tx;

  public DemoDataSeeder(
      @Value("${apprentice.demo-data:false}") boolean enabled,
      TenantRepository tenants, WorkmapRepository workmaps, StepRepository steps,
      GuardrailRepository guardrails, CaseRepository cases, LearnerRepository learners,
      PlatformTransactionManager txManager) {
    this.enabled = enabled;
    this.tenants = tenants;
    this.workmaps = workmaps;
    this.steps = steps;
    this.guardrails = guardrails;
    this.cases = cases;
    this.learners = learners;
    this.tx = new TransactionTemplate(txManager);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled || tenants.findBySlug("demo").isPresent()) return;
    Tenant demo = tenants.save(new Tenant("demo", "Empresa Demo", TenantFilter.sha256("demo-key")));
    // El contexto se fija ANTES de abrir la transaccion, para que la conexion
    // herede app.tenant_id y las politicas RLS acepten las inserciones.
    TenantContext.set(demo);
    try {
      tx.executeWithoutResult(status -> seed());
    } finally {
      TenantContext.clear();
    }
  }

  private void seed() {
    Workmap wm = new Workmap();
    wm.setTitle("Procesar facturas de proveedores");
    wm.setRole("Contabilidad");
    wm.setExpertName("Sabine");
    wm.setLanguage("es");
    wm.setSummary("Como Sabine recibe, valida y contabiliza facturas de proveedores.");
    workmaps.save(wm);

    record StepDef(int pos, String title, String decision, String quote, String risk) {}
    List<StepDef> defs = List.of(
        new StepDef(1, "Recibir la factura", "Revisar el buzon de facturas y descargar el PDF",
            "Siempre empiezo por el buzon compartido, nada se pide por otro canal", "low"),
        new StepDef(2, "Verificar el proveedor", "Comprobar que el proveedor existe en el maestro",
            "Si el proveedor no esta dado de alta, no se paga nada", "medium"),
        new StepDef(3, "Validar importe e IVA", "Cruzar el importe con el pedido y revisar el IVA",
            "El IVA lo reviso dos veces, es donde mas errores hay", "high"),
        new StepDef(4, "Aprobar o escalar", "Aprobar si todo cuadra; escalar si hay diferencias",
            "Cualquier diferencia mayor a 50 euros va al jefe de compras", "high"),
        new StepDef(5, "Contabilizar", "Registrar la factura en el ERP con la cuenta correcta",
            "La cuenta contable depende del tipo de gasto, no del proveedor", "medium"),
        new StepDef(6, "Programar el pago", "Programar el pago segun la fecha de vencimiento",
            "Nunca pago antes de tiempo salvo descuento por pronto pago", "low"),
        new StepDef(7, "Archivar", "Archivar la factura con su justificante",
            "Todo queda archivado el mismo dia, sin excepciones", "low"));

    Map<Integer, Step> byPos = new java.util.HashMap<>();
    for (StepDef d : defs) {
      Step s = new Step();
      s.setWorkmap(wm);
      s.setPosition(d.pos());
      s.setTitle(d.title());
      s.setDecision(d.decision());
      s.setReasonQuote(d.quote());
      s.setReasonAuthor("Sabine");
      s.setRisk(Step.Risk.valueOf(d.risk()));
      steps.save(s);
      byPos.put(d.pos(), s);
    }

    record GDef(int step, Guardrail.Kind kind, String cond, String action, String quote) {}
    List<GDef> gdefs = List.of(
        new GDef(2, Guardrail.Kind.stop_and_ask, "el proveedor no existe en el maestro",
            "Detener y pedir alta del proveedor a compras", "Sin alta no hay pago, punto"),
        new GDef(3, Guardrail.Kind.limit, "la diferencia con el pedido supera 50 euros",
            "No aprobar; escalar al jefe de compras", "Mas de 50 euros de diferencia nunca lo apruebo yo"),
        new GDef(4, Guardrail.Kind.exception, "la factura es de un pais fuera de la UE",
            "Aplicar el procedimiento de importacion, no el normal", "Las facturas de fuera de la UE son otro mundo"),
        new GDef(6, Guardrail.Kind.limit, "el pago seria anterior al vencimiento",
            "Programar para la fecha de vencimiento salvo descuento", "El dinero se queda con nosotros hasta el vencimiento"));
    for (GDef g : gdefs) {
      Guardrail gr = new Guardrail();
      gr.setWorkmap(wm);
      gr.setStep(byPos.get(g.step()));
      gr.setKind(g.kind());
      gr.setCondition(g.cond());
      gr.setCorrectAction(g.action());
      gr.setExpertQuote(g.quote());
      guardrails.save(gr);
    }

    record CDef(String title, PracticeCase.Kind kind, int diff, Map<String, Object> data) {}
    List<CDef> cdefs = List.of(
        new CDef("Factura estandar de papeleria", PracticeCase.Kind.happy, 1,
            Map.of("proveedor", "OfiMax SL", "importe", 320.50, "iva", "21%", "pedido", "PO-1042")),
        new CDef("Factura con pedido parcial", PracticeCase.Kind.happy, 1,
            Map.of("proveedor", "Logistica Sur", "importe", 890.00, "iva", "21%", "pedido", "PO-1055")),
        new CDef("Diferencia de 120 euros", PracticeCase.Kind.limit, 2,
            Map.of("proveedor", "OfiMax SL", "importe", 1020.00, "pedido_importe", 900.00, "pedido", "PO-1061")),
        new CDef("Proveedor nuevo sin alta", PracticeCase.Kind.stop_and_ask, 2,
            Map.of("proveedor", "TecnoNorte (nuevo)", "importe", 450.00, "pedido", "PO-1070")),
        new CDef("Factura de proveedor de Japon", PracticeCase.Kind.exception, 3,
            Map.of("proveedor", "Kyoto Parts KK", "importe", 5300.00, "moneda", "JPY", "pedido", "PO-1077")),
        new CDef("Factura urgente con descuento", PracticeCase.Kind.happy, 3,
            Map.of("proveedor", "Logistica Sur", "importe", 1200.00, "descuento_pronto_pago", "2%", "pedido", "PO-1080")));
    for (CDef c : cdefs) {
      PracticeCase pc = new PracticeCase();
      pc.setWorkmap(wm);
      pc.setTitle(c.title());
      pc.setKind(c.kind());
      pc.setDifficulty(c.diff());
      pc.setData(c.data());
      pc.setExpected(defs.stream()
          .map(d -> new PracticeCase.ExpectedDecision(d.pos(), d.decision()))
          .toList());
      cases.save(pc);
    }

    learners.save(new Learner("Lena", "lena-demo"));
  }
}
