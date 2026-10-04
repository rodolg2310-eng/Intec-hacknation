package com.apprentice.privacy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PiiRedactorTest {

  private final PiiRedactor redactor = new PiiRedactor();

  @Test
  void ocultaCorreos() {
    assertEquals("Escribe a [email] por favor",
        redactor.redact("Escribe a sabine@empresa.com por favor"));
  }

  @Test
  void ocultaIban() {
    assertEquals("Cuenta: [iban]",
        redactor.redact("Cuenta: DE89370400440532013000"));
  }

  @Test
  void ocultaTelefonos() {
    assertEquals("Llama al [telefono]",
        redactor.redact("Llama al +49 151 23456789"));
  }

  @Test
  void redactaMapasRecursivamente() {
    Map<String, Object> out = redactor.redactMap(
        Map.of("contacto", Map.of("email", "a@b.com"), "nota", "sin datos"));
    @SuppressWarnings("unchecked")
    Map<String, Object> contacto = (Map<String, Object>) out.get("contacto");
    assertEquals("[email]", contacto.get("email"));
    assertEquals("sin datos", out.get("nota"));
  }
}
