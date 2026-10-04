package com.apprentice.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DecisionMatcherTest {

  private final DecisionMatcher matcher = new DecisionMatcher();

  @Test
  void aceptaCoincidenciaExacta() {
    assertTrue(matcher.matches(
        "Comprobar que el proveedor existe en el maestro",
        "Comprobar que el proveedor existe en el maestro"));
  }

  @Test
  void ignoraAcentosYMayusculas() {
    assertTrue(matcher.matches(
        "comprobar que el proveedor existe en el maestro",
        "Comprobar que el proveedor existe en el maestro"));
  }

  @Test
  void aceptaParafrasisConPalabrasClave() {
    assertTrue(matcher.matches(
        "Verifico que el proveedor este en el maestro de proveedores",
        "Comprobar que el proveedor existe en el maestro"));
  }

  @Test
  void rechazaDecisionDistinta() {
    assertFalse(matcher.matches(
        "Pago la factura directamente",
        "Comprobar que el proveedor existe en el maestro"));
  }

  @Test
  void rechazaNulos() {
    assertFalse(matcher.matches(null, "algo"));
    assertFalse(matcher.matches("algo", null));
  }
}
