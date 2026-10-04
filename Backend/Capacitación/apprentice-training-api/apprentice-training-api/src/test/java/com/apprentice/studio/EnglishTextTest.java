package com.apprentice.studio;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class EnglishTextTest {
 @Test void acceptsWrappedOrCompleteFlatTranslation()throws Exception{var json=new ObjectMapper();var claude=mock(ClaudeEngine.class);var english=new EnglishText(claude);var fields=json.readTree("{\"title\":\"Factura\"}");when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"fields\":{\"title\":\"Invoice\"}}"));assertEquals("Invoice",english.fields(fields).path("title").asText());when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"title\":\"Invoice\"}"));assertEquals("Invoice",english.fields(fields).path("title").asText());}
 @Test void retriesMalformedTranslationOnce()throws Exception{var json=new ObjectMapper();var claude=mock(ClaudeEngine.class);var english=new EnglishText(claude);when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"reply\":\"Invoice\"}"),json.readTree("{\"fields\":{\"title\":\"Invoice\"}}"));assertEquals("Invoice",english.fields(json.readTree("{\"title\":\"Factura\"}")).path("title").asText());verify(claude,times(2)).generate(anyString(),anyString(),isNull());}
}
