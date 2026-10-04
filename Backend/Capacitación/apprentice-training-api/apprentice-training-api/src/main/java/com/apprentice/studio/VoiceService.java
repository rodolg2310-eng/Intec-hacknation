package com.apprentice.studio;

import com.apprentice.common.ApiException;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Claude supplies the exact text. A genuine ElevenLabs Agent conversation supplies its voice. */
@Service
public class VoiceService {
  private final String key, agentId;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  public record SpeechClip(byte[] audio, String conversationId, String agentId) {}
  public VoiceService(@Value("${apprentice.voice.api-key}") String key, @Value("${apprentice.voice.agent-id}") String agentId) { this.key=key; this.agentId=agentId; }
  public boolean configured() { return scribeConfigured() && agentId != null && !agentId.isBlank(); }
  public boolean scribeConfigured() { return key != null && !key.isBlank(); }
  public String agentId() { return agentId; }
  public void verify() {
    requireAgent();
    try {
      JsonNode agent=json.readTree(request("https://api.elevenlabs.io/v1/convai/agents/"+agentId,null));
      JsonNode overrides=agent.path("platform_settings").path("overrides").path("conversation_config_override").path("agent");
      if(!overrides.path("first_message").asBoolean() || !overrides.path("language").asBoolean()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Enable First message and Language overrides on the ElevenLabs Agent so Claude controls the spoken text.");
      if(!agent.path("conversation_config").path("tts").path("agent_output_audio_format").asText("pcm_16000").startsWith("pcm_")) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"The ElevenLabs Agent must output PCM audio.");
    } catch(IOException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Invalid ElevenLabs Agent configuration.");}
  }
  public Map<String,Object> scribeToken() {
    try {
      JsonNode result=json.readTree(request("https://api.elevenlabs.io/v1/single-use-token/realtime_scribe","{}"));
      if(result.path("token").asText().isBlank())throw new ApiException(HttpStatus.BAD_GATEWAY,"ElevenLabs did not return a Scribe token.");
      return Map.of("token",result.path("token").asText());
    }catch(IOException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"Invalid Scribe response.");}
  }
  public SpeechClip speak(String text,String language) {
    requireAgent();
    if(text==null || text.isBlank() || text.length()>4000)throw ApiException.badRequest("Speech text must contain 1–4,000 characters.");
    WebSocket socket=null;
    try {
      JsonNode signed=json.readTree(request("https://api.elevenlabs.io/v1/convai/conversation/get-signed-url?agent_id="+URLEncoder.encode(agentId,java.nio.charset.StandardCharsets.UTF_8),null));
      URI url=URI.create(signed.path("signed_url").asText());
      if(!url.getScheme().equals("wss") || !url.getHost().equals("api.elevenlabs.io"))throw new IOException("Invalid signed conversation URL.");
      AgentListener listener=new AgentListener(text);
      socket=http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(url,listener).get(20,TimeUnit.SECONDS);
      String lang=language==null?"en":language.split("[-_]")[0];if(lang.equals("spa"))lang="es";if(lang.equals("eng")||lang.equals("auto")||lang.isBlank())lang="en";
      // No user audio/message is sent to this agent. Its model is never asked to generate a reply.
      String initiation=json.writeValueAsString(Map.of("type","conversation_initiation_client_data","conversation_config_override",Map.of("agent",Map.of("first_message",text,"language",lang)),"dynamic_variables",Map.of("traina_voice_mode","claude_controlled_readout")));
      socket.sendText(initiation,true).get(10,TimeUnit.SECONDS);
      SpeechClip clip=listener.done.get(150,TimeUnit.SECONDS);
      socket.sendClose(WebSocket.NORMAL_CLOSURE,"Claude readout complete").get(5,TimeUnit.SECONDS);
      return clip;
    }catch(ApiException e){throw e;}
    catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"ElevenLabs Agent could not finish Claude's spoken response. Check the agent ID, overrides, permissions and credits; retry this response.");}
    finally{if(socket!=null)socket.abort();}
  }
  private final class AgentListener implements WebSocket.Listener {
    final CompletableFuture<SpeechClip> done=new CompletableFuture<>();
    final StringBuilder message=new StringBuilder();
    final ByteArrayOutputStream pcm=new ByteArrayOutputStream();
    final String expectedText;
    final StringBuilder alignedText=new StringBuilder();
    final java.util.concurrent.atomic.AtomicInteger completionGeneration=new java.util.concurrent.atomic.AtomicInteger();
    String conversationId="",agentText="";int sampleRate=16000;long firstAudioAt;boolean finalAudio;
    AgentListener(String text){expectedText=text;}
    public void onOpen(WebSocket ws){ws.request(1);}
    public synchronized CompletionStage<?> onText(WebSocket ws,CharSequence part,boolean last){
      message.append(part);
      if(last){try{
        JsonNode event=json.readTree(message.toString());message.setLength(0);
        switch(event.path("type").asText()){
          case "conversation_initiation_metadata" -> {JsonNode meta=event.path("conversation_initiation_metadata_event");conversationId=meta.path("conversation_id").asText();String format=meta.path("agent_output_audio_format").asText();if(!format.matches("pcm_[0-9]+"))throw new IOException("Unsupported Agent audio format.");sampleRate=Integer.parseInt(format.substring(4));if(sampleRate<8000||sampleRate>48000)throw new IOException("Unsupported Agent sample rate.");}
          case "agent_response" -> {agentText=event.path("agent_response_event").path("agent_response").asText();if(!expectedText.equals(agentText))throw new IOException("Agent changed Claude's supplied text.");if(fullAlignment())scheduleCompletion();}
          case "audio" -> {JsonNode audio=event.path("audio_event");byte[] bytes=Base64.getDecoder().decode(audio.path("audio_base_64").asText());if(pcm.size()+bytes.length>16*1024*1024)throw new IOException("Agent speech exceeded the audio limit.");if(firstAudioAt==0)firstAudioAt=System.nanoTime();pcm.write(bytes);for(JsonNode character:audio.path("alignment").path("chars"))alignedText.append(character.asText());finalAudio=finalAudio||audio.path("is_final").asBoolean();if(finalAudio||fullAlignment())scheduleCompletion();}
          case "agent_response_complete" -> complete();
          case "ping" -> ws.sendText(json.writeValueAsString(Map.of("type","pong","event_id",event.path("ping_event").path("event_id").asLong())),true);
          case "error", "client_error", "guardrail_triggered" -> throw new IOException("Agent rejected the conversation.");
          default -> {}
        }
      }catch(Exception e){done.completeExceptionally(e);}}
      ws.request(1);return null;
    }
    private boolean fullAlignment(){return expectedText.equals(agentText)&&normalize(alignedText.toString()).equals(normalize(expectedText));}
    private void scheduleCompletion(){
      if(firstAudioAt==0)return;
      int generation=completionGeneration.incrementAndGet();long durationMs=pcm.size()*1000L/(sampleRate*2L),elapsedMs=(System.nanoTime()-firstAudioAt)/1000000L;
      // First-message readouts do not always emit a completion event. Full text alignment proves
      // synthesis completion; wait for its exact PCM playback duration before closing the receipt.
      CompletableFuture.delayedExecutor(Math.max(0,durationMs+500-elapsedMs),TimeUnit.MILLISECONDS).execute(()->{synchronized(this){if(generation==completionGeneration.get())complete();}});
    }
    private synchronized void complete(){if(!expectedText.equals(agentText)){done.completeExceptionally(new IOException("Agent did not confirm Claude's supplied text."));return;}try{if(!conversationId.isBlank() && pcm.size()>0)done.complete(new SpeechClip(wav(pcm.toByteArray(),sampleRate),conversationId,agentId));}catch(Exception e){done.completeExceptionally(e);}}
    public synchronized CompletionStage<?> onClose(WebSocket ws,int status,String reason){if(!done.isDone())done.completeExceptionally(new IOException("Agent disconnected before speech completed."));return null;}
    public synchronized void onError(WebSocket ws,Throwable e){done.completeExceptionally(e);}
  }
  static String normalize(String text){return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("\\p{M}","").replaceAll("[^\\p{L}\\p{N}]","");}
  static byte[] wav(byte[] pcm,int rate){
    if(rate<8000 || rate>48000 || pcm.length%2!=0)throw new IllegalArgumentException("Invalid PCM audio.");
    ByteBuffer bytes=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);return bytes.array();
  }
  private void requireAgent(){if(!configured())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"ELEVENLABS_API_KEY or ELEVENLABS_AGENT_ID is missing from .env.");}
  private byte[] request(String url,String body){
    if(!scribeConfigured())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"ELEVENLABS_API_KEY is missing from .env.");
    try{
      HttpRequest.Builder req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("xi-api-key",key).header("Content-Type","application/json");
      if(body==null)req.GET();else req.POST(HttpRequest.BodyPublishers.ofString(body));
      HttpResponse<byte[]> result=http.send(req.build(),HttpResponse.BodyHandlers.ofByteArray());
      if(result.statusCode()>=300)throw new ApiException(HttpStatus.BAD_GATEWAY,"ElevenLabs returned HTTP "+result.statusCode()+". Check agent access, permissions and credits.");
      return result.body();
    }catch(ApiException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiException(HttpStatus.BAD_GATEWAY,"Could not connect to ElevenLabs.");}
  }
}
