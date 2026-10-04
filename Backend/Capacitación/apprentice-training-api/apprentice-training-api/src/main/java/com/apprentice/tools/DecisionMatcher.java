package com.apprentice.tools;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Compara la decision del aprendiz con la esperada sin depender de la IA:
 * normaliza acentos y mayusculas y exige coincidencia de palabras clave.
 */
@Component
public class DecisionMatcher {

  public boolean matches(String given, String expected) {
    if (given == null || expected == null) return false;
    String g = normalize(given);
    String e = normalize(expected);
    if (g.equals(e) || g.contains(e) || e.contains(g)) return true;

    String[] keywords = e.split(" ");
    int hits = 0;
    int meaningful = 0;
    for (String kw : keywords) {
      if (kw.length() < 4) continue;
      meaningful++;
      if (matchesKeyword(g, kw)) hits++;
    }
    return meaningful > 0 && hits >= Math.ceil(meaningful * 0.6);
  }

  private boolean matchesKeyword(String given, String expectedKeyword) {
    if (given.contains(expectedKeyword)) return true;
    return switch (expectedKeyword) {
      case "comprobar" -> containsAny(given,
          "verificar", "verifico", "verifica", "revisar", "reviso", "validar", "valido");
      default -> false;
    };
  }

  private boolean containsAny(String text, String... candidates) {
    for (String candidate : candidates) {
      if (text.contains(candidate)) return true;
    }
    return false;
  }

  private String normalize(String s) {
    String n = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
    return n.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
  }
}
