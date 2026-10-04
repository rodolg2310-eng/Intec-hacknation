package com.apprentice.studio;
import com.apprentice.auth.*;
import com.apprentice.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController @RequestMapping("/api/t/{slug}/studio")
public class StudioController {
  private final StudioService studio;
  private final StudioEvents events;
  public StudioController(StudioService studio,StudioEvents events){this.studio=studio;this.events=events;}
  @GetMapping("/status") public Object status(@RequestParam(defaultValue="false") boolean verify){studio.auth.requireCurrent(Role.MASTER,Role.SENIOR);Map<String,Object> c=new HashMap<>(),v=new HashMap<>(),s=new HashMap<>();c.put("configured",studio.claude.configured());c.put("model",studio.claude.model());v.put("configured",studio.voice.configured());v.put("model",studio.voice.agentId());v.put("mode","Claude-controlled voice");s.put("configured",studio.voice.scribeConfigured());if(verify){try{studio.claude.verify();c.put("verified",true);}catch(Exception e){c.put("verified",false);c.put("error",e.getMessage());}try{studio.voice.verify();v.put("verified",true);}catch(Exception e){v.put("verified",false);v.put("error",e.getMessage());}try{studio.voice.scribeToken();s.put("verified",true);}catch(Exception e){s.put("verified",false);s.put("error",e.getMessage());}}return Map.of("claude",c,"agent",v,"scribe",s);}
  @GetMapping("/sessions") public Object list(){return studio.list();}
  @PostMapping("/sessions") public Object create(@RequestBody JsonNode input){return studio.create(input);}
  @GetMapping("/sessions/{id}") public Object get(@PathVariable UUID id){return studio.get(id);}
  @DeleteMapping("/sessions/{id}") public Object delete(@PathVariable UUID id){return studio.delete(id);}
  public record Frame(String image,String cameraImage,long sequence,long elapsedSeconds,boolean offRecord){}
  @PostMapping("/sessions/{id}/frame") public ResponseEntity<Object> frame(@PathVariable UUID id,@RequestBody Frame f){return ResponseEntity.accepted().body(studio.enqueue(id,f.image(),f.cameraImage(),f.sequence(),f.elapsedSeconds(),f.offRecord()));}
  @PostMapping("/sessions/{id}/camera-frame") public ResponseEntity<Object> cameraFrame(@PathVariable UUID id,@RequestBody Frame f){return ResponseEntity.accepted().body(studio.cameraFrame(id,f.image(),f.sequence(),f.elapsedSeconds(),f.offRecord()));}
  @PostMapping("/sessions/{id}/turn") public Object turn(@PathVariable UUID id,@RequestBody JsonNode input){return studio.turn(id,input);}
  @PostMapping("/sessions/{id}/heartbeat") public Object heartbeat(@PathVariable UUID id,@RequestBody JsonNode input){return studio.heartbeat(id,input);}
  @PostMapping("/sessions/{id}/finish") public ResponseEntity<Object> finish(@PathVariable UUID id){return ResponseEntity.accepted().body(studio.finish(id));}
  @PostMapping("/sessions/{id}/retry") public Object retry(@PathVariable UUID id){return studio.retry(id);}
  @PostMapping("/sessions/{id}/questions/{questionId}/delivered") public Object delivered(@PathVariable UUID id,@PathVariable UUID questionId,@RequestBody JsonNode input){return studio.delivered(id,questionId,input.path("activeSeconds").asLong());}
  @PostMapping("/sessions/{id}/defer") public Object defer(@PathVariable UUID id){studio.own(id);return studio.store.locked(id,s->{var d=studio.store.data(s);if(d.hasNonNull("pendingQuestionId"))studio.park(s,d);studio.store.save(s,d);return studio.get(id);});}
  @PostMapping("/sessions/{id}/confirm") public Object confirm(@PathVariable UUID id,@RequestBody JsonNode input){return studio.confirm(id,input.path("map"));}
  @PostMapping("/sessions/{id}/review") public Object review(@PathVariable UUID id){return studio.reopen(id);}
  @DeleteMapping("/sessions/{id}/events/{eventId}") public Object remove(@PathVariable UUID id,@PathVariable UUID eventId){return studio.removeEvent(id,eventId);}
  @GetMapping("/sessions/{id}/screens/{eventId}") public ResponseEntity<Resource> screen(@PathVariable UUID id,@PathVariable UUID eventId){StudioSession s=studio.readable(id);if(StudioStore.event(studio.store.data(s),eventId.toString())==null)throw ApiException.notFound("Screen moment");Path path=studio.media.image(id,eventId);if(!Files.isRegularFile(path))throw ApiException.notFound("Screen moment");return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));}
  @PostMapping("/sessions/{id}/scribe-token") public Object token(@PathVariable UUID id){studio.own(id);if(!Set.of("capture","debrief","review").contains(studio.store.require(id).phase))throw ApiException.conflict("Microphone transcription is not available in this phase.");return studio.voice.scribeToken();}
  @PostMapping(value="/sessions/{id}/recordings",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object recording(@PathVariable UUID id,@RequestParam UUID recordingId,@RequestParam long sequence,@RequestParam double startSeconds,@RequestParam double endSeconds,@RequestParam String kind,@RequestPart("file") MultipartFile file){studio.own(id);return studio.store.locked(id,s->{studio.store.check(s,studio.senior(),true);if(!Set.of("capture","finishing","debrief","review").contains(s.phase))throw ApiException.conflict("This activity is no longer accepting recording fragments.");JsonNode opened=null;for(JsonNode entry:studio.store.data(s).path("openRecordings"))if(entry.path("id").asText().equals(recordingId.toString()))opened=entry;if(opened==null)throw ApiException.conflict("Open this recording before uploading fragments.");if(!opened.path("kind").asText().equals(kind)||startSeconds<opened.path("startSeconds").asDouble())throw ApiException.badRequest("Recording fragment metadata does not match its open recording.");return studio.media.upload(id,recordingId,sequence,startSeconds,endSeconds,kind,file);});}
  @PostMapping("/sessions/{id}/recordings/open") public Object open(@PathVariable UUID id,@RequestBody JsonNode input){studio.own(id);return studio.store.locked(id,s->{if(!Set.of("capture","debrief").contains(s.phase))throw ApiException.conflict("Recording is not available in this phase.");var d=studio.store.data(s);UUID recording=UUID.fromString(input.path("recordingId").asText());if(!Set.of("video","audio").contains(input.path("kind").asText()))throw ApiException.badRequest("Invalid recording kind.");double start=input.path("startSeconds").asDouble(-1);if(!Double.isFinite(start)||start<0||start>5400)throw ApiException.badRequest("Invalid recording start time.");for(JsonNode entry:d.path("openRecordings"))if(entry.path("id").asText().equals(recording.toString()))return Map.of("opened",true);d.withArray("openRecordings").addObject().put("id",recording.toString()).put("kind",input.path("kind").asText()).put("startSeconds",input.path("startSeconds").asDouble());studio.store.save(s,d);return Map.of("opened",true);});}
  @PostMapping("/sessions/{id}/recordings/{recordingId}/close") public Object close(@PathVariable UUID id,@PathVariable UUID recordingId,@RequestBody JsonNode input){studio.own(id);studio.media.close(id,recordingId,input.path("count").asLong(),input.path("endSeconds").asDouble());return studio.store.locked(id,s->{var d=studio.store.data(s);var open=d.withArray("openRecordings");for(int i=open.size()-1;i>=0;i--)if(open.get(i).path("id").asText().equals(recordingId.toString()))open.remove(i);studio.store.save(s,d);return Map.of("closed",true);});}
  @PostMapping("/sessions/{id}/recordings/{recordingId}/cancel") public Object cancel(@PathVariable UUID id,@PathVariable UUID recordingId){
    studio.own(id);
    return studio.store.locked(id,s->{
      studio.store.check(s,studio.senior(),true);
      var d=studio.store.data(s);var open=d.withArray("openRecordings");int index=-1;
      for(int i=0;i<open.size();i++)if(open.get(i).path("id").asText().equals(recordingId.toString())){index=i;break;}
      if(index<0)throw ApiException.conflict("This recording is not open.");
      if(studio.media.hasFragments(id,recordingId))throw ApiException.conflict("This recording already contains evidence. Finish or recover its uploads instead of canceling it.");
      open.remove(index);studio.store.save(s,d);return Map.of("cancelled",true);
    });
  }
  @PostMapping("/sessions/{id}/recordings/recover") public Object recover(@PathVariable UUID id){studio.own(id);return studio.store.locked(id,s->{var d=studio.store.data(s);if(!d.path("offRecord").asBoolean()&&System.currentTimeMillis()-d.path("heartbeatAt").asLong()<10000)throw ApiException.conflict("Go off record before recovering an interrupted recording.");for(JsonNode open:d.path("openRecordings")){UUID recording=UUID.fromString(open.path("id").asText());var received=studio.media.received(id,recording);if(!received.isEmpty())studio.media.close(id,recording,received.size(),received.get(received.size()-1).endSeconds);}d.putArray("openRecordings");var frameJobs=studio.frames.findBySessionIdOrderBySequence(id);Set<Long> receivedSequences=new HashSet<>();for(FrameJob f:frameJobs)receivedSequences.add(f.sequence);var lost=d.putArray("lostFrameSequences");long last=frameJobs.stream().mapToLong(f->f.sequence).max().orElse(-1);for(long seq=0;seq<last;seq++)if(!receivedSequences.contains(seq))lost.add(seq);d.put("recordingIncomplete",true);d.put("offRecord",true);d.remove("processingError");studio.store.save(s,d);return studio.get(id);});}
  @GetMapping("/sessions/{id}/recordings/{recordingId}") public ResponseEntity<Resource> recording(@PathVariable UUID id,@PathVariable UUID recordingId){studio.readable(id);MediaChunk file=studio.media.file(id,recordingId);return ResponseEntity.ok().header("Accept-Ranges","bytes").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.contentType)).body(new FileSystemResource(studio.media.checked(file.path)));}
  @GetMapping(value="/sessions/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@PathVariable UUID id){studio.readable(id);return events.subscribe(id,studio.auth.requireCurrent());}
  public record Speech(UUID sessionId,String messageId){}
  @PostMapping("/speech") public ResponseEntity<byte[]> speech(@RequestBody Speech input){return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).cacheControl(CacheControl.noStore()).body(studio.speech(input.sessionId(),input.messageId()));}
  @PostMapping("/sessions/{id}/practice") public Object practice(@PathVariable UUID id){return studio.practice(id);}
  @PostMapping("/sessions/{id}/practice/check") public Object check(@PathVariable UUID id,@RequestBody JsonNode input){return studio.check(id,input.path("choiceId").asText());}
  @PostMapping("/sessions/{id}/practice/save") public Object save(@PathVariable UUID id,@RequestBody JsonNode input){return studio.saveDecision(id,input.path("choiceId").asText());}
  @GetMapping("/drafts") public Object drafts(){return studio.drafts();}
  @PostMapping("/sessions/{id}/assign") public Object assign(@PathVariable UUID id,@RequestBody JsonNode input){return studio.assign(id,UUID.fromString(input.path("authorAccountId").asText()));}
  @PostMapping("/example") public Object example() throws IOException {return studio.example();}
}
