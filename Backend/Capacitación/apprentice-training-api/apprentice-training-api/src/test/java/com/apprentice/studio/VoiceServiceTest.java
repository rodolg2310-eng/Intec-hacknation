package com.apprentice.studio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceServiceTest {
  @Test void wrapsAgentPcmInAMonoLittleEndianWaveWithCorrectLengths() {
    byte[] pcm={0,0,1,0,-1,127,0,-128};
    byte[] audio=VoiceService.wav(pcm,16000);
    ByteBuffer header=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(44+pcm.length,audio.length);
    assertEquals("RIFF",new String(audio,0,4,StandardCharsets.US_ASCII));
    assertEquals(36+pcm.length,header.getInt(4));
    assertEquals("WAVEfmt ",new String(audio,8,8,StandardCharsets.US_ASCII));
    assertEquals(16,header.getInt(16));assertEquals(1,header.getShort(20));assertEquals(1,header.getShort(22));
    assertEquals(16000,header.getInt(24));assertEquals(32000,header.getInt(28));assertEquals(2,header.getShort(32));assertEquals(16,header.getShort(34));
    assertEquals("data",new String(audio,36,4,StandardCharsets.US_ASCII));assertEquals(pcm.length,header.getInt(40));
    assertArrayEquals(pcm,java.util.Arrays.copyOfRange(audio,44,audio.length));
  }
  @Test void supportsTheConfiguredAgentPcmSampleRate() {
    for(int rate:new int[]{8000,16000,22050,24000,44100,48000}){
      ByteBuffer header=ByteBuffer.wrap(VoiceService.wav(new byte[]{0,0},rate)).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(rate,header.getInt(24));assertEquals(rate*2,header.getInt(28));
    }
  }
  @Test void rejectsUnsupportedRatesAndIncompletePcmSamples() {
    assertThrows(IllegalArgumentException.class,()->VoiceService.wav(new byte[]{0,0},7999));
    assertThrows(IllegalArgumentException.class,()->VoiceService.wav(new byte[]{0,0},48001));
    assertThrows(IllegalArgumentException.class,()->VoiceService.wav(new byte[]{0},16000));
  }
  @Test void reportsAgentAndScribeConfigurationSeparately() {
    assertFalse(new VoiceService("","").configured());assertFalse(new VoiceService("","").scribeConfigured());
    VoiceService scribeOnly=new VoiceService("test-key","");assertTrue(scribeOnly.scribeConfigured());assertFalse(scribeOnly.configured());
    VoiceService agent=new VoiceService("test-key","agent-qa");assertTrue(agent.configured());assertEquals("agent-qa",agent.agentId());
  }
}
