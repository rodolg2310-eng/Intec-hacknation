package com.apprentice.studio;
import com.apprentice.auth.*;
import com.apprentice.tenant.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:studio_v3_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","apprentice.data-dir=./target/test-data"})
@ActiveProfiles("local") @AutoConfigureMockMvc
class StudioFlowTest {
  @Autowired MockMvc mvc;
  @Autowired StudioService studio;
  @Autowired TenantRepository tenants;
  @Autowired InvitationRepository invitations;
  @MockBean ClaudeEngine claude;
  @MockBean VoiceService voice;
  @MockBean EnglishText english;
  @MockBean StudioWorker worker;
  final ObjectMapper json=new ObjectMapper();
  Cookie master,senior,learner,learner2;
  String slug,base;
  @BeforeEach void setup() throws Exception {
    when(voice.agentId()).thenReturn("agent-qa");
    when(english.fields(any())).thenAnswer(i->i.getArgument(0));when(english.translate(anyString(),anyString())).thenAnswer(i->new EnglishText.Translation(i.getArgument(0),"en"));
    var r=request("/api/auth/register",null,Map.of("email",UUID.randomUUID()+"@qa.invalid","password","QA-password-only!","name","QA Master","workspace","QA Team"),200);master=cookie(r);slug=json.readTree(r.getResponse().getContentAsString()).path("slug").asText();base="/api/t/"+slug+"/studio";
    senior=join(Role.SENIOR);learner=join(Role.LEARNER);learner2=join(Role.LEARNER);
  }
  MvcResult request(String path,Cookie cookie,Object body,int expected) throws Exception {var builder=post(path).contentType("application/json").content(json.writeValueAsString(body));if(cookie!=null)builder.cookie(cookie);var r=mvc.perform(builder).andReturn();assertEquals(expected,r.getResponse().getStatus(),r.getResponse().getContentAsString());return r;}
  Cookie cookie(MvcResult r){return new Cookie("traina_session",r.getResponse().getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);}
  JsonNode call(String path,Cookie c,Object body) throws Exception{return json.readTree(request(path,c,body,200).getResponse().getContentAsString());}
  Cookie join(Role role) throws Exception {JsonNode i=call("/api/t/"+slug+"/team/invitations",master,Map.of("email",UUID.randomUUID()+"@qa.invalid","role",role.name()));return cookie(request("/api/auth/accept-invitation",null,Map.of("token",i.path("path").asText().split("=",2)[1],"name","QA "+role,"password","QA-password-only!"),200));}
  JsonNode create()throws Exception{return call(base+"/sessions",senior,Map.of("title","Invoice processing","objective","Classify and verify an invoice","application","Invoice sandbox","initialState","Unverified invoice","expectedOutcome","A correctly classified invoice"));}
  JsonNode example()throws Exception{return call(base+"/example",senior,Map.of());}
  JsonNode get(String path,Cookie c)throws Exception {var r=mvc.perform(get(path).cookie(c)).andReturn();assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString());return json.readTree(r.getResponse().getContentAsString());}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String path){return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);}
  void inTenant(Runnable work){TenantContext.set(tenants.findBySlug(slug).orElseThrow());try{work.run();}finally{TenantContext.clear();}}
  String image()throws Exception{var image=new BufferedImage(60,40,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());}
  @Test void rolesAreEnforcedIncludingLegacyRoutesAndApiKeyBypass()throws Exception{
    assertEquals("MASTER",get("/api/auth/me",master).path("role").asText());request(base+"/sessions",master,Map.of(),403);request(base+"/sessions",learner,Map.of(),403);
    request("/api/t/"+slug+"/team/invitations",senior,Map.of("email","x@qa.invalid","role","LEARNER"),403);
    assertEquals(403,mvc.perform(get("/api/t/"+slug+"/workmaps").cookie(learner)).andReturn().getResponse().getStatus());
    assertEquals(401,mvc.perform(get(base+"/sessions").header("x-api-key","irrelevant")).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(post(base+"/sessions").cookie(senior).header("Origin","https://foreign.invalid").contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
  }
  @Test void invitationsBindEmailRoleAndAreSingleUseRevocableAndExpiring()throws Exception{
    JsonNode i=call("/api/t/"+slug+"/team/invitations",master,Map.of("email","invite-"+UUID.randomUUID()+"@qa.invalid","role","LEARNER"));String token=i.path("path").asText().split("=",2)[1];
    JsonNode preview=get("/api/auth/invitation?token="+token,master);assertEquals("LEARNER",preview.path("role").asText());
    var accepted=request("/api/auth/accept-invitation",null,Map.of("token",token,"email","spoof@qa.invalid","role","MASTER","name","Invited","password","QA-password-only!"),200);assertEquals(preview.path("email"),json.readTree(accepted.getResponse().getContentAsString()).path("email"));
    request("/api/auth/accept-invitation",null,Map.of("token",token,"name","Again","password","QA-password-only!"),409);
    JsonNode other=call("/api/t/"+slug+"/team/invitations",master,Map.of("email",UUID.randomUUID()+"@qa.invalid","role","SENIOR"));mvc.perform(delete("/api/t/"+slug+"/team/invitations/"+other.path("id").asText()).cookie(master));request("/api/auth/accept-invitation",null,Map.of("token",other.path("path").asText().split("=",2)[1],"name","No","password","QA-password-only!"),409);
    request("/api/t/"+slug+"/team/invitations",master,Map.of("email","master@qa.invalid","role","MASTER"),400);
  }
  @Test void masterCannotBeDemotedAndDeactivationInvalidatesExistingCookie()throws Exception{
    String masterId=get("/api/auth/me",master).path("id").asText(),learnerId=get("/api/auth/me",learner).path("id").asText();
    assertEquals(409,mvc.perform(patch("/api/t/"+slug+"/team/members/"+masterId).cookie(master).contentType("application/json").content("{\"role\":\"LEARNER\",\"active\":true}")).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(patch("/api/t/"+slug+"/team/members/"+learnerId).cookie(master).contentType("application/json").content("{\"role\":\"LEARNER\",\"active\":false}")).andReturn().getResponse().getStatus());assertEquals(401,mvc.perform(get(base+"/sessions").cookie(learner)).andReturn().getResponse().getStatus());
  }
  @Test void seniorContextAndOwnershipAreRequiredAndLearnerSeesPublishedOnly()throws Exception{
    request(base+"/sessions",senior,Map.of("title","Incomplete"),400);JsonNode s=create();String path=base+"/sessions/"+s.path("id").asText();assertEquals("QA SENIOR",s.path("expert").asText());assertEquals(403,mvc.perform(get(path).cookie(learner)).andReturn().getResponse().getStatus());assertTrue(get(base+"/sessions",learner).isEmpty());
    Cookie otherSenior=join(Role.SENIOR);assertEquals(403,mvc.perform(get(path).cookie(otherSenior)).andReturn().getResponse().getStatus());
    request(path+"/assign",master,Map.of("authorAccountId",get("/api/auth/me",otherSenior).path("id").asText()),200);assertEquals(403,mvc.perform(get(path).cookie(senior)).andReturn().getResponse().getStatus());assertEquals(200,mvc.perform(get(path).cookie(otherSenior)).andReturn().getResponse().getStatus());
  }
  @Test void screenshotsAreAcceptedWithoutWaitingForClaudeAndIdempotent()throws Exception{
    JsonNode s=create();String path=base+"/sessions/"+s.path("id").asText();var body=Map.of("image",image(),"sequence",0,"elapsedSeconds",2);request(path+"/frame",senior,body,202);request(path+"/frame",senior,body,202);verifyNoInteractions(claude);
    assertEquals(1,get(path,senior).path("pipeline").path("pending").asInt());
    when(claude.generateImages(anyString(),anyString(),anyList())).thenReturn(json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice classified\",\"decision\":\"Use capex\",\"description\":\"Cost center changed\",\"open_question\":\"Why capex?\"}],\"memory\":{\"decisions\":[\"Use capex\"]}}"));
    inTenant(()->studio.processFrames(UUID.fromString(s.path("id").asText())));JsonNode updated=get(path,senior);assertEquals(1,updated.path("events").size());assertEquals(0,updated.path("pipeline").path("pending").asInt());assertEquals(1,updated.path("doubts").size());
    request(path+"/frame",senior,Map.of("image",image(),"sequence",1,"elapsedSeconds",3601),400);
  }
  @Test void onlyDeliveredQuestionsCountAndDeferredQuestionsAreSaved()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"reply\":\"Why is this the correct classification?\",\"spoken_reply\":\"Why is this the correct classification?\",\"question_type\":\"reason\"}"));
    inTenant(()->studio.generateQuestion(id,"capture",false));JsonNode pending=get(path,senior);assertEquals(0,pending.path("liveQuestions").asInt());String question=pending.path("pendingQuestionId").asText();
    call(path+"/questions/"+question+"/delivered",senior,Map.of("activeSeconds",60));call(path+"/questions/"+question+"/delivered",senior,Map.of("activeSeconds",60));assertEquals(1,get(path,senior).path("liveQuestions").asInt());call(path+"/defer",senior,Map.of());assertEquals("open",get(path,senior).path("doubts").get(0).path("status").asText());
  }
  @Test void spanishStatementIsStoredInEnglishWithOriginalLanguageProvenance()throws Exception{
    JsonNode s=create();when(english.translate("Sin número de activo, no registro capex.","es")).thenReturn(new EnglishText.Translation("No asset number, no capex booking.","es"));
    JsonNode updated=call(base+"/sessions/"+s.path("id").asText()+"/turn",senior,Map.of("text","Sin número de activo, no registro capex.","kind","message","language","es","elapsedSeconds",195,"startSeconds",192,"endSeconds",198));JsonNode m=updated.path("messages").get(0);assertEquals("No asset number, no capex booking.",m.path("text").asText());assertTrue(m.path("translated").asBoolean());assertEquals("es",m.path("sourceLanguage").asText());
  }
  @Test void practiceIsPrivateAndWrongDecisionCannotSaveEvenIfClaudeAllows()throws Exception{
    JsonNode s=example();String path=base+"/sessions/"+s.path("id").asText();request(path+"/practice",senior,Map.of(),403);
    when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"title\":\"New invoice\",\"scenario\":\"No asset number\",\"facts\":[],\"choices\":[{\"id\":\"a\",\"label\":\"Save anyway\",\"correct\":false},{\"id\":\"b\",\"label\":\"Request asset number\",\"correct\":true},{\"id\":\"c\",\"label\":\"Ignore\",\"correct\":false}]}"));
    JsonNode p=call(path+"/practice",learner,Map.of());assertFalse(p.path("practice").path("choices").get(0).has("correct"));assertFalse(get(path,learner2).has("practice"));
    when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"allowed\":true,\"feedback\":\"Check the asset number\",\"expert_quote\":\"Invented quote\",\"score\":100,\"step_position\":1}"));JsonNode wrong=call(path+"/practice/check",learner,Map.of("choiceId","a"));assertFalse(wrong.path("validation").path("allowed").asBoolean());assertNotEquals("Invented quote",wrong.path("validation").path("expert_quote").asText());request(path+"/practice/save",learner,Map.of("choiceId","a"),409);call(path+"/practice/check",learner,Map.of("choiceId","b"));assertEquals(1,call(path+"/practice/save",learner,Map.of("choiceId","b")).path("completedPractices").asInt());assertEquals(1,call(path+"/practice/save",learner,Map.of("choiceId","b")).path("completedPractices").asInt());assertFalse(get(path,learner2).has("validation"));assertEquals(0,get(path,learner2).path("completedPractices").asInt());
  }
  @Test void removalErasesScreenAndWithdrawsPublishedEvidence()throws Exception{
    JsonNode s=example();String path=base+"/sessions/"+s.path("id").asText();JsonNode e=s.path("events").get(0);var r=mvc.perform(delete(path+"/events/"+e.path("id").asText()).cookie(senior)).andReturn();assertEquals(200,r.getResponse().getStatus());JsonNode updated=json.readTree(r.getResponse().getContentAsString());assertFalse(updated.path("mapConfirmed").asBoolean());assertFalse(updated.has("map"));assertEquals(404,mvc.perform(get(e.path("screenUrl").asText()).cookie(senior)).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(get(path).cookie(learner)).andReturn().getResponse().getStatus());
  }
  @Test void speechUsesOnlyAccessibleSavedClaudeMessages()throws Exception{
    byte[] audio=VoiceService.wav(new byte[]{1,2,3,4},16000);
    when(voice.speak(anyString(),anyString())).thenReturn(new VoiceService.SpeechClip(audio,"conv-qa-senior","agent-qa"));
    JsonNode s=example();JsonNode message=s.path("messages").get(0);String id=s.path("id").asText(),messageId=message.path("id").asText();var body=Map.of("sessionId",id,"messageId",messageId);
    var response=request(base+"/speech",senior,body,200);assertEquals("audio/wav",response.getResponse().getContentType());assertArrayEquals(audio,response.getResponse().getContentAsByteArray());
    verify(voice).speak(message.path("text").asText(),"en");
    JsonNode updated=get(base+"/sessions/"+id,senior);JsonNode receipt=updated.path("messages").get(0);assertEquals("ElevenLabs Agent",receipt.path("voiceProvider").asText());assertEquals("conv-qa-senior",receipt.path("voiceConversationId").asText());assertEquals("agent-qa",receipt.path("voiceAgentId").asText());
    assertArrayEquals(audio,request(base+"/speech",senior,body,200).getResponse().getContentAsByteArray());verify(voice,times(1)).speak(anyString(),anyString());
    request(base+"/speech",master,body,403);request(base+"/speech",senior,Map.of("sessionId",id,"messageId",s.path("messages").get(1).path("id").asText()),404);verifyNoMoreInteractions(ignoreStubs(voice));
  }
  @Test void learnerAgentReceiptsStayPrivateAndCannotUseAnotherLearnersMessage()throws Exception{
    when(voice.speak(anyString(),anyString())).thenReturn(new VoiceService.SpeechClip(VoiceService.wav(new byte[]{1,2},16000),"conv-qa-learner","agent-qa"));
    when(claude.generate(anyString(),anyString(),isNull())).thenReturn(json.readTree("{\"reply\":\"Verify the invoice against the asset register.\",\"event_id\":null}"));
    JsonNode s=example();String id=s.path("id").asText(),path=base+"/sessions/"+id;
    JsonNode own=call(path+"/turn",learner,Map.of("text","Which evidence should I verify?"));JsonNode reply=own.path("learningMessages").get(1);String messageId=reply.path("id").asText();var body=Map.of("sessionId",id,"messageId",messageId);
    request(base+"/speech",learner,body,200);JsonNode updated=get(path,learner);assertEquals("conv-qa-learner",updated.path("learningMessages").get(1).path("voiceConversationId").asText());assertEquals("ElevenLabs Agent",updated.path("learningMessages").get(1).path("voiceProvider").asText());
    assertFalse(get(path,learner2).has("learningMessages"));assertFalse(get(path,senior).has("learningMessages"));
    request(base+"/speech",learner2,body,404);request(base+"/speech",senior,body,404);request(base+"/speech",learner,Map.of("sessionId",id,"messageId",own.path("learningMessages").get(0).path("id").asText()),404);
    request(base+"/speech",learner,body,200);verify(voice,times(1)).speak(reply.path("text").asText(),"en");verifyNoMoreInteractions(ignoreStubs(voice));
  }
  @Test void failedAgentSpeechHasNoSuccessReceiptAndCanBeRetried()throws Exception{
    byte[] audio=VoiceService.wav(new byte[]{0,0},16000);
    when(voice.speak(anyString(),anyString())).thenThrow(new com.apprentice.common.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Agent unavailable; retry.")).thenReturn(new VoiceService.SpeechClip(audio,"conv-qa-retry","agent-qa"));
    JsonNode s=example();String id=s.path("id").asText(),messageId=s.path("messages").get(0).path("id").asText();var body=Map.of("sessionId",id,"messageId",messageId);
    request(base+"/speech",senior,body,502);assertFalse(get(base+"/sessions/"+id,senior).path("messages").get(0).has("voiceConversationId"));
    assertArrayEquals(audio,request(base+"/speech",senior,body,200).getResponse().getContentAsByteArray());assertEquals("conv-qa-retry",get(base+"/sessions/"+id,senior).path("messages").get(0).path("voiceConversationId").asText());verify(voice,times(2)).speak(anyString(),anyString());
  }
  @Test void knownSessionIdsRemainIsolatedAcrossWorkspaces()throws Exception{
    JsonNode s=example();var foreign=request("/api/auth/demo",null,Map.of(),200);Cookie other=cookie(foreign);assertEquals(401,mvc.perform(get(base+"/sessions/"+s.path("id").asText()).cookie(other)).andReturn().getResponse().getStatus());
  }
  @Test void interruptedBrowserRecordingRecoversReceivedFragmentsAndStaysIncomplete()throws Exception{
    JsonNode s=create();String path=base+"/sessions/"+s.path("id").asText();String recording=UUID.randomUUID().toString();
    call(path+"/recordings/open",senior,Map.of("recordingId",recording,"kind","audio","startSeconds",0));
    call(path+"/recordings/open",senior,Map.of("recordingId",recording,"kind","audio","startSeconds",0));assertEquals(1,get(path,senior).path("openRecordings").size());
    var file=new org.springframework.mock.web.MockMultipartFile("file","chunk.webm","audio/webm",new byte[]{1,2,3,4});
    assertEquals(200,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path+"/recordings").file(file).cookie(senior).param("recordingId",recording).param("sequence","0").param("startSeconds","0").param("endSeconds","3").param("kind","audio")).andReturn().getResponse().getStatus());
    JsonNode recovered=call(path+"/recordings/recover",senior,Map.of());assertTrue(recovered.path("recordingIncomplete").asBoolean());assertTrue(recovered.path("openRecordings").isEmpty());assertTrue(recovered.path("mediaPending").asBoolean());
  }
  @Test void queuedProcessingSurvivesWorkerRecoveryAndDuplicateImagesSkipClaude()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"sequence",0,"elapsedSeconds",2),202);
    inTenant(()->{FrameJob f=studio.frames.findBySessionIdOrderBySequence(id).get(0);f.status="processing";studio.frames.save(f);});new StudioWorker(tenants,studio).recover();
    inTenant(()->assertEquals("queued",studio.frames.findBySessionIdOrderBySequence(id).get(0).status));
    when(claude.generateImages(anyString(),anyString(),anyList())).thenReturn(json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice visible\",\"decision\":\"Verify\"}]}"));inTenant(()->studio.processFrames(id));
    request(path+"/frame",senior,Map.of("image",image(),"sequence",1,"elapsedSeconds",4),202);inTenant(()->studio.processFrames(id));verify(claude,times(1)).generateImages(anyString(),anyString(),anyList());assertEquals(2,get(path,senior).path("pipeline").path("processed").asInt());assertEquals(1,get(path,senior).path("events").size());
  }
  @Test void screenshotTimeoutShowsRecoveryMessageAndKeepsTheFrameRetryable()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"sequence",0,"elapsedSeconds",2),202);
    when(claude.generateImages(anyString(),anyString(),anyList())).thenThrow(new com.apprentice.common.ApiException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,"Claude timed out after 90 seconds. Your evidence is saved; retry processing."));
    inTenant(()->studio.processFrames(id));JsonNode waiting=get(path,senior);assertTrue(waiting.path("analysisError").asText().contains("timed out after 90 seconds"));assertEquals(1,waiting.path("pipeline").path("pending").asInt());
    call(path+"/retry",senior,Map.of());doReturn(json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice visible\",\"decision\":\"Verify\"}]}" )).when(claude).generateImages(anyString(),anyString(),anyList());inTenant(()->studio.processFrames(id));JsonNode recovered=get(path,senior);assertEquals(1,recovered.path("pipeline").path("processed").asInt());assertFalse(recovered.has("analysisError"));
  }
  @Test void ordinaryRevisionPreservesPrivateAttemptsLinkedToTheirOriginalVersion()throws Exception{
    JsonNode s=example();UUID id=UUID.fromString(s.path("id").asText()),learnerId=UUID.fromString(get("/api/auth/me",learner).path("id").asText());String path=base+"/sessions/"+id;
    inTenant(()->{LearningAttempt a=new LearningAttempt();a.sessionId=id;a.learnerId=learnerId;a.mapVersion=1;a.payload="{\"completedPractices\":2}";studio.attempts.save(a);});
    JsonNode review=call(path+"/review",senior,Map.of());JsonNode revised=call(path+"/confirm",senior,Map.of("map",review.path("map")));assertEquals(2,revised.path("publicationVersion").asInt());assertEquals(0,get(path,learner).path("completedPractices").asInt());inTenant(()->{var old=studio.attempts.findBySessionIdAndLearnerIdAndMapVersion(id,learnerId,1).orElseThrow();assertEquals(2,studio.store.parse(old.payload).path("completedPractices").asInt());assertEquals(2,studio.publications.findBySessionId(id).size());});
  }
  @Test void outOfOrderReceiptsWaitForTheEarlierScreenshot()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"sequence",1,"elapsedSeconds",4),202);inTenant(()->studio.processFrames(id));verifyNoInteractions(claude);assertEquals(0,get(path,senior).path("pipeline").path("missingSequence").asLong());
    request(path+"/frame",senior,Map.of("image",image(),"sequence",0,"elapsedSeconds",2),202);when(claude.generateImages(anyString(),anyString(),anyList())).thenReturn(json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice visible\",\"decision\":\"Verify\"}]}"));inTenant(()->studio.processFrames(id));assertEquals(2,get(path,senior).path("pipeline").path("processed").asInt());assertFalse(get(path,senior).path("pipeline").has("missingSequence"));
  }
  @Test void aSimulatedHourPresentsThreeDistinctQuestionsInEachOfSixIntervals()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;var number=new java.util.concurrent.atomic.AtomicInteger();
    when(claude.generate(anyString(),anyString(),isNull())).thenAnswer(invocation->json.createObjectNode().put("reply","What evidence supports decision "+number.incrementAndGet()+"?").put("question_type","reason"));
    for(int window=0;window<6;window++)for(int question=0;question<3;question++){long seconds=window*600+420+question*60;call(path+"/heartbeat",senior,Map.of("activeSeconds",seconds,"speaking",true,"idleMs",0,"continuousMs",1000,"offRecord",false));inTenant(()->studio.liveTick(id));JsonNode pending=get(path,senior);String q=pending.path("pendingQuestionId").asText();assertFalse(q.isBlank());call(path+"/questions/"+q+"/delivered",senior,Map.of("activeSeconds",seconds));}
    call(path+"/heartbeat",senior,Map.of("activeSeconds",3600,"offRecord",true));JsonNode complete=get(path,senior);assertEquals(18,complete.path("liveQuestions").asInt());assertEquals(6,complete.path("questionWindows").size());for(JsonNode interval:complete.path("questionWindows")){assertEquals(3,interval.path("delivered").asInt());assertEquals("complete",interval.path("status").asText());}
  }
  @Test void cameraIsPersistedWithScreenAndUsedAsSupplementaryQuestionContext()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"cameraImage",image(),"sequence",0,"elapsedSeconds",2),202);verifyNoInteractions(claude);
    inTenant(()->{FrameJob frame=studio.frames.findBySessionIdOrderBySequence(id).get(0);assertNotNull(frame.cameraImagePath);assertTrue(java.nio.file.Files.isRegularFile(studio.media.checked(frame.cameraImagePath)));});
    when(claude.generateImages(anyString(),anyString(),anyList())).thenAnswer(i->{assertEquals(2,((List<?>)i.getArgument(2)).size());assertTrue(((String)i.getArgument(1)).contains("CAMERA supplemental"));assertTrue(((String)i.getArgument(0)).contains("camera changes do not create events"));return json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice visible\",\"decision\":\"Verify\"}]}");});
    inTenant(()->studio.processFrames(id));
    when(claude.generate(anyString(),anyString(),anyString())).thenAnswer(i->{assertTrue(((String)i.getArgument(0)).contains("Do not infer identity, emotion, attention"));assertTrue(((String)i.getArgument(1)).contains("CAMERA context at media timestamp 00:02"));return json.readTree("{\"reply\":\"Which visible document supports this entry?\",\"question_type\":\"reason\"}");});
    inTenant(()->studio.generateQuestion(id,"capture",false));JsonNode result=get(path,senior);assertEquals(2,result.path("messages").get(0).path("cameraSeconds").asInt());assertFalse(result.path("cameraContext").has("imagePath"));assertEquals(1,result.path("events").size());
  }
  @Test void offRecordRejectsNewCameraEvidenceAndClearsCurrentCameraForQuestions()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"cameraImage",image(),"sequence",0,"elapsedSeconds",2),202);
    call(path+"/heartbeat",senior,Map.of("activeSeconds",2,"offRecord",true));request(path+"/frame",senior,Map.of("image",image(),"cameraImage",image(),"sequence",1,"elapsedSeconds",4,"offRecord",true),202);
    inTenant(()->{studio.generateQuestion(id,"capture",false);assertFalse(java.nio.file.Files.exists(studio.media.directory(id).resolve("camera-current.jpg")));});verifyNoInteractions(claude);assertEquals(1,get(path,senior).path("pipeline").path("received").asInt());assertFalse(get(path,senior).path("cameraContext").has("id"));
  }
  @Test void debriefCameraUsesBoundedPersistentContextAndIndependentSequence()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/camera-frame",senior,Map.of("image",image(),"sequence",0,"elapsedSeconds",5),409);
    inTenant(()->studio.store.locked(id,current->{current.phase="debrief";studio.store.save(current,studio.store.data(current));return null;}));
    request(path+"/camera-frame",learner,Map.of("image",image(),"sequence",0,"elapsedSeconds",5),403);request(path+"/camera-frame",master,Map.of("image",image(),"sequence",0,"elapsedSeconds",5),403);
    for(int i=0;i<4;i++)request(path+"/camera-frame",senior,Map.of("image",image(),"sequence",i,"elapsedSeconds",5+2*i),202);
    JsonNode context=get(path,senior).path("cameraContext");assertEquals(3,context.path("sequence").asInt());assertEquals(4,context.path("nextSequence").asInt());assertEquals(11,context.path("elapsedSeconds").asInt());assertEquals(0,get(path,senior).path("pipeline").path("received").asInt());
    var duplicate=request(path+"/camera-frame",senior,Map.of("image",image(),"sequence",0,"elapsedSeconds",5),202);assertEquals("superseded",json.readTree(duplicate.getResponse().getContentAsString()).path("status").asText());
    inTenant(()->{try(var files=java.nio.file.Files.list(studio.media.directory(id))){assertEquals(1,files.filter(p->p.getFileName().toString().startsWith("camera-")).count());}catch(Exception e){throw new RuntimeException(e);}});
    when(claude.generate(anyString(),anyString(),anyString())).thenReturn(json.readTree("{\"reply\":\"Which exception remains unresolved?\",\"question_type\":\"followup\"}"));inTenant(()->studio.generateQuestion(id,"debrief",false));assertEquals(11,get(path,senior).path("messages").get(0).path("cameraSeconds").asInt());
    call(path+"/heartbeat",senior,Map.of("activeSeconds",0,"mediaSeconds",11,"offRecord",true));assertEquals(4,get(path,senior).path("cameraContext").path("nextSequence").asInt());request(path+"/camera-frame",senior,Map.of("image",image(),"sequence",4,"elapsedSeconds",13,"offRecord",true),202);assertFalse(get(path,senior).path("cameraContext").has("id"));
  }
  @Test void withdrawalErasesPairedAndRecentCameraEvidence()throws Exception{
    JsonNode s=create();UUID id=UUID.fromString(s.path("id").asText());String path=base+"/sessions/"+id;
    request(path+"/frame",senior,Map.of("image",image(),"cameraImage",image(),"sequence",0,"elapsedSeconds",2),202);when(claude.generateImages(anyString(),anyString(),anyList())).thenReturn(json.readTree("{\"observations\":[{\"sequence\":0,\"changed\":true,\"title\":\"Invoice visible\",\"decision\":\"Verify\"}]}"));inTenant(()->studio.processFrames(id));String event=get(path,senior).path("events").get(0).path("id").asText();
    assertEquals(200,mvc.perform(delete(path+"/events/"+event).cookie(senior)).andReturn().getResponse().getStatus());inTenant(()->{assertNull(studio.frames.findBySessionIdOrderBySequence(id).get(0).cameraImagePath);assertFalse(java.nio.file.Files.exists(studio.media.cameraImage(id,UUID.fromString(event))));assertFalse(java.nio.file.Files.exists(studio.media.directory(id).resolve("camera-current.jpg")));});
  }
}
