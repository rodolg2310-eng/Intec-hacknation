import assert from 'node:assert/strict';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';

// Real provider verification. Credentials and signed URLs are never logged.
// The private agent backup stays under the ignored .runtime directory.
const qa=path.resolve('.runtime/qa');
await mkdir(qa,{recursive:true});
const env=Object.fromEntries((await readFile('.env','utf8')).replace(/^\uFEFF/,'').split(/\r?\n/)
  .map(line=>line.match(/^\s*([^#=]+)=(.*)$/)).filter(Boolean)
  .map(([,name,value])=>[name.trim(),value.trim().replace(/^(['"])(.*)\1$/,'$2')]));
assert(env.ELEVENLABS_API_KEY,'ELEVENLABS_API_KEY is required.');
const agentId=env.ELEVENLABS_AGENT_ID||'agent_6001m4286dc2fnhb2aq3t6zmvz99';
const api='https://api.elevenlabs.io/v1/convai';
const headers={'xi-api-key':env.ELEVENLABS_API_KEY,'Content-Type':'application/json'};
async function request(endpoint,method='GET',body){
  const r=await fetch(api+endpoint,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  if(!r.ok)throw Error(`ElevenLabs ${method} failed with HTTP ${r.status}.`);
  return await r.json();
}
const before=await request('/agents/'+encodeURIComponent(agentId));
const backup=path.join(qa,'eleven-agent-before.json');
try{await writeFile(backup,JSON.stringify(before,null,2),{flag:'wx'});}catch(e){if(e.code!=='EEXIST')throw e;}
const originalEvents=before.conversation_config?.conversation?.client_events||[];
const changes={
  platform_settings:{overrides:{conversation_config_override:{agent:{first_message:true,language:true}}}},
  conversation_config:{conversation:{client_events:[...new Set([...originalEvents,'agent_response_complete'])]}}
};
await request('/agents/'+encodeURIComponent(agentId),'PATCH',changes);
const configured=await request('/agents/'+encodeURIComponent(agentId));
assert.equal(configured.platform_settings.overrides.conversation_config_override.agent.first_message,true);
assert.equal(configured.platform_settings.overrides.conversation_config_override.agent.language,true);
assert(configured.conversation_config.conversation.client_events.includes('agent_response_complete'));
assert.deepEqual(configured.conversation_config.agent,before.conversation_config.agent,'Agent persona or LLM changed.');
assert.deepEqual(configured.conversation_config.tts,before.conversation_config.tts,'Agent voice changed.');

const spokenText=process.argv.includes('--english')?'Claude prepares the questions. Your ElevenLabs agent speaks them.':'Esta es una verificación de Traina. Claude decide las preguntas y tu agente de ElevenLabs les da voz.';
const language=process.argv.includes('--english')?'en':'es';
const normalizedText=text=>text.normalize('NFKD').replace(/\p{M}/gu,'').toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
const signed=await request('/conversation/get-signed-url?'+new URLSearchParams({agent_id:agentId,include_conversation_id:'true'}));
assert(signed.signed_url,'ElevenLabs did not return a signed conversation URL.');
const started=Date.now(),events=[],chunks=[];
let conversationId=signed.conversation_id||null,format,agentText,completion,alignmentText='',firstAudioAt;
const ws=new WebSocket(signed.signed_url);
let probeError;
await new Promise((resolve,reject)=>{
  let settled=false,completionTimer;
  const finish=(error)=>{
    if(settled)return;settled=true;clearTimeout(timer);clearTimeout(completionTimer);
    ws.close(1000,'Readout complete');
    if(error)reject(error);else resolve();
  };
  const finishAfterPlayback=()=>{
    clearTimeout(completionTimer);
    const durationMs=Buffer.concat(chunks).length/32;
    completionTimer=setTimeout(()=>finish(),Math.max(0,firstAudioAt+durationMs+500-Date.now()));
  };
  const timer=setTimeout(()=>finish(Error('ElevenLabs agent readout timed out.')),20000);
  ws.addEventListener('error',()=>finish(Error('ElevenLabs agent WebSocket connection failed.')));
  ws.addEventListener('close',()=>{if(!settled)finish(Error('ElevenLabs agent closed before completing the readout.'));});
  ws.addEventListener('open',()=>ws.send(JSON.stringify({
    type:'conversation_initiation_client_data',
    conversation_config_override:{agent:{first_message:spokenText,language},conversation:{text_only:false}},
    user_id:'traina-agent-verification'
  })));
  ws.addEventListener('message',({data})=>{
    try{
      const e=JSON.parse(data);
      const alignment=e.audio_event?.alignment;
      events.push({type:e.type,afterMs:Date.now()-started,eventId:e.audio_event?.event_id??e.agent_response_event?.event_id??e.agent_response_complete_event?.event_id??null,isFinal:e.audio_event?.is_final??null,
        audioKeys:e.audio_event?Object.keys(e.audio_event):undefined,alignmentKeys:alignment?Object.keys(alignment):undefined,chars:alignment?.chars?.join(''),charEndMs:alignment?.char_start_times_ms?.at(-1)});
      if(e.type==='ping')ws.send(JSON.stringify({type:'pong',event_id:e.ping_event.event_id}));
      else if(e.type==='conversation_initiation_metadata'){
        conversationId=e.conversation_initiation_metadata_event.conversation_id;
        format=e.conversation_initiation_metadata_event.agent_output_audio_format;
      }else if(e.type==='agent_response')agentText=e.agent_response_event.agent_response;
      else if(e.type==='audio'){
        firstAudioAt??=Date.now();
        chunks.push(Buffer.from(e.audio_event.audio_base_64,'base64'));
        alignmentText+=(alignment?.chars||[]).join('');
        if(e.audio_event.is_final){completion='audio.is_final';finishAfterPlayback();}
        else if(agentText===spokenText&&normalizedText(alignmentText)===normalizedText(spokenText)){completion='full text alignment';finishAfterPlayback();}
        else if(completion)finishAfterPlayback();
      }else if(e.type==='agent_response_complete'){
        completion='agent_response_complete';finish();
      }else if(e.type==='client_error'||e.type==='guardrail_triggered')finish(Error('ElevenLabs agent returned '+e.type+'.'));
    }catch(e){finish(e);}
  });
}).catch(async(error)=>{
  probeError=error.message;
  await writeFile(path.join(qa,'agent-probe-events.json'),JSON.stringify({conversationId,format,agentText,pcmBytes:Buffer.concat(chunks).length,probeError,events},null,2));
});
assert(conversationId,'Agent conversation ID was missing.');
assert.equal(agentText,spokenText,'Agent changed the exact Claude readout text.');
assert.equal(format,'pcm_16000','Expected agent PCM audio format.');
const pcm=Buffer.concat(chunks);assert(pcm.length>1000,'Agent returned no usable audio.');
await writeFile(path.join(qa,'agent-probe-events.json'),JSON.stringify({conversationId,format,agentText,alignmentText,completion,pcmBytes:pcm.length,probeError,events},null,2));
const wav=Buffer.alloc(44+pcm.length);
wav.write('RIFF',0);wav.writeUInt32LE(36+pcm.length,4);wav.write('WAVE',8);wav.write('fmt ',12);wav.writeUInt32LE(16,16);
wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(16000,24);wav.writeUInt32LE(32000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);
wav.write('data',36);wav.writeUInt32LE(pcm.length,40);pcm.copy(wav,44);
await writeFile(path.join(qa,'eleven-agent-readout.wav'),wav);
let details;
for(let i=0;i<12;i++){
  await new Promise(r=>setTimeout(r,2000));
  details=await request('/conversations/'+encodeURIComponent(conversationId));
  if(details.status==='done'||details.status==='failed')break;
}
assert.equal(details.agent_id,agentId,'Conversation belongs to another agent.');
const transcript=details.transcript||[];
assert.equal(transcript.filter(t=>t.role==='agent').length,1,'Agent generated unexpected extra turns.');
assert.equal(transcript.find(t=>t.role==='agent').message,spokenText,'Stored agent transcript changed the requested readout.');
assert.equal(transcript.filter(t=>t.role==='user').length,0,'Unexpected user input reached the voice agent.');
const usage=details.metadata?.charging?.llm_usage||details.metadata?.llm_usage||null;
const llmPrice=details.metadata?.charging?.llm_price??details.metadata?.llm_price??null;
if(llmPrice!==null)assert.equal(llmPrice,0,'Agent charged an unintended LLM generation.');
const report={at:new Date().toISOString(),agentId,conversationId,agentName:configured.name,
  mode:'Claude text as exact agent first-message readout; no agent user input',
  language,spokenText,agentText,alignmentText,completion,format,pcmBytes:pcm.length,audioSeconds:pcm.length/32000,
  status:details.status,probeError,callDurationSeconds:details.metadata?.call_duration_secs??null,
  llmPrice,llmUsage:usage,charging:details.metadata?.charging??null,
  eventOrder:events,passed:['Agent override permissions configured','Agent voice/persona/LLM preserved','Genuine signed WebSocket conversation','Exact supplied text in agent audio/transcript','PCM audio retained','No user input or extra agent turns']};
await writeFile(path.join(qa,'agent-verification.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({passed:!probeError,agentId,conversationId,completion,probeError,audioSeconds:report.audioSeconds,llmPrice,alignmentText,eventOrder:events},null,2));
if(probeError)process.exitCode=1;
