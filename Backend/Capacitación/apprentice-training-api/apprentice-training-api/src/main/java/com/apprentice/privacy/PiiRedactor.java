package com.apprentice.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Oculta datos personales antes de enviar texto al tutor o a la IA:
 * correos, IBAN y telefonos se reemplazan por marcadores.
 */
@Component
public class PiiRedactor {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern IBAN =
      Pattern.compile("\\b[A-Z]{2}\\d{2}(?:[ ]?\\p{Alnum}{4}){2,7}(?:[ ]?\\p{Alnum}{1,3})?\\b");
  private static final Pattern PHONE =
      Pattern.compile("(?<![\\d])\\+?\\d[\\d .()-]{7,}\\d(?![\\d])");

  private final ObjectMapper mapper = new ObjectMapper();

  public String redact(String text) {
    if (text == null) return null;
    String out = EMAIL.matcher(text).replaceAll("[email]");
    out = IBAN.matcher(out).replaceAll("[iban]");
    out = PHONE.matcher(out).replaceAll("[telefono]");
    return out;
  }

  /** Redacta recursivamente los valores de texto de un objeto JSON. */
  public Map<String, Object> redactMap(Map<String, Object> map) {
    if (map == null) return null;
    JsonNode node = redactNode(mapper.valueToTree(map));
    return mapper.convertValue(node, Map.class);
  }

  private JsonNode redactNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        obj.set(e.getKey(), redactNode(e.getValue()));
      }
    } else if (node.isTextual()) {
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(redact(node.asText()));
    }
    return node;
  }
}
