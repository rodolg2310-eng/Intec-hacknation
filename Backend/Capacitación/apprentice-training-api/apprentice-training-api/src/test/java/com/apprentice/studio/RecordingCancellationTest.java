package com.apprentice.studio;

import com.apprentice.auth.Role;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recording_cancel_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","apprentice.data-dir=./target/test-data"})
@ActiveProfiles("local") @AutoConfigureMockMvc
class RecordingCancellationTest {
  @Autowired MockMvc mvc;
  @MockBean ClaudeEngine claude;
  @MockBean VoiceService voice;
  @MockBean EnglishText english;
  @MockBean StudioWorker worker;
  private final ObjectMapper json=new ObjectMapper();
  private Cookie master,senior;
  private String slug,path;

  @BeforeEach void setup() throws Exception {
    when(voice.agentId()).thenReturn("agent-qa");
    when(english.fields(any())).thenAnswer(i->i.getArgument(0));
    var registered=postJson("/api/auth/register",null,Map.of("email",UUID.randomUUID()+"@qa.invalid","password","QA-password-only!","name","QA Master","workspace","Cancel Test"),200);
    master=cookie(registered);slug=json.readTree(registered.getResponse().getContentAsString()).path("slug").asText();senior=join(Role.SENIOR);
    JsonNode activity=body(postJson("/api/t/"+slug+"/studio/sessions",senior,Map.of("title","Recorder startup","objective","Record the task","application","Sandbox","initialState","Ready","expectedOutcome","Recorded activity"),200));
    path="/api/t/"+slug+"/studio/sessions/"+activity.path("id").asText();
  }
  private MvcResult postJson(String uri,Cookie account,Object data,int status) throws Exception {
    var request=post(uri).contentType("application/json").content(json.writeValueAsString(data));if(account!=null)request.cookie(account);
    var result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());return result;
  }
  private Cookie cookie(MvcResult result){return new Cookie("traina_session",result.getResponse().getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);}
  private JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsString());}
  private Cookie join(Role role)throws Exception {
    JsonNode invite=body(postJson("/api/t/"+slug+"/team/invitations",master,Map.of("email",UUID.randomUUID()+"@qa.invalid","role",role.name()),200));
    return cookie(postJson("/api/auth/accept-invitation",null,Map.of("token",invite.path("path").asText().split("=",2)[1],"name","QA "+role,"password","QA-password-only!"),200));
  }
  private String openRecording()throws Exception {String id=UUID.randomUUID().toString();postJson(path+"/recordings/open",senior,Map.of("recordingId",id,"kind","audio","startSeconds",0),200);return id;}
  private JsonNode activity()throws Exception {var r=mvc.perform(get(path).cookie(senior)).andReturn();assertEquals(200,r.getResponse().getStatus());return body(r);}
  private void upload(String id,int status)throws Exception {
    var file=new MockMultipartFile("file","chunk.webm","audio/webm",new byte[]{1,2,3,4});
    var result=mvc.perform(multipart(path+"/recordings").file(file).cookie(senior).param("recordingId",id).param("sequence","0").param("startSeconds","0").param("endSeconds","1").param("kind","audio")).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());
  }

  @Test void emptyStartupRecordingCanBeCancelledWithoutMarkingEvidenceIncomplete()throws Exception {
    String id=openRecording();assertEquals(1,activity().path("openRecordings").size());
    assertTrue(body(postJson(path+"/recordings/"+id+"/cancel",senior,Map.of(),200)).path("cancelled").asBoolean());
    JsonNode state=activity();assertTrue(state.path("openRecordings").isEmpty());assertFalse(state.path("mediaPending").asBoolean());assertFalse(state.path("recordingIncomplete").asBoolean());
    upload(id,409);postJson(path+"/recordings/"+id+"/cancel",senior,Map.of(),409);
    openRecording();assertEquals(1,activity().path("openRecordings").size());
  }
  @Test void cancellationRejectsPersistedFragmentsAndLeavesThemRecoverable()throws Exception {
    String id=openRecording();upload(id,200);
    postJson(path+"/recordings/"+id+"/cancel",senior,Map.of(),409);assertEquals(1,activity().path("openRecordings").size());
    postJson(path+"/recordings/"+id+"/close",senior,Map.of("count",1,"endSeconds",1),200);
    assertTrue(activity().path("openRecordings").isEmpty());assertTrue(activity().path("mediaPending").asBoolean());
  }
  @Test void cancellationRequiresTheSeniorAuthorInTheCorrectWorkspace()throws Exception {
    String id=openRecording(),uri=path+"/recordings/"+id+"/cancel";
    postJson(uri,master,Map.of(),403);postJson(uri,join(Role.LEARNER),Map.of(),403);postJson(uri,join(Role.SENIOR),Map.of(),403);
    Cookie foreign=cookie(postJson("/api/auth/register",null,Map.of("email",UUID.randomUUID()+"@qa.invalid","password","QA-password-only!","name","Other Master","workspace","Other Cancel Test"),200));
    postJson(uri,foreign,Map.of(),401);assertEquals(1,activity().path("openRecordings").size());postJson(uri,senior,Map.of(),200);
  }
}
