package com.apprentice.mastery;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MasteryTest {

  @Test
  void dominioExigeTresAciertosSeguidosYCasoNuevo() {
    Mastery m = new Mastery(null, null);
    m.record(true, false);
    m.record(true, false);
    m.record(true, false);
    assertFalse(m.isMastered(), "Sin caso nuevo no hay dominio");
    m.record(true, true);
    assertTrue(m.isMastered());
  }

  @Test
  void unFalloRompeLaRacha() {
    Mastery m = new Mastery(null, null);
    m.record(true, true);
    m.record(true, false);
    m.record(false, false);
    assertEquals(0, m.getStreak());
    assertFalse(m.isMastered());
  }
}
