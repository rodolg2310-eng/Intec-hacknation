import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const origin=process.env.APP_URL||'http://127.0.0.1:3000', live=process.argv.includes('--live');
const qa=path.resolve('.runtime/qa'); await mkdir(qa,{recursive:true});
const passed=[], password=randomUUID()+'A!';
function pass(name){passed.push(name);console.log('OK · '+name);}
async function req(url,cookie,body,status=200,method=body===undefined?'GET':'POST'){
 const response=await fetch(origin+url,{method,headers:{'Content-Type':'application/json',Cookie:cookie||''},...(body===undefined?{}:{body:JSON.stringify(body)})});
 const value=await response.json();assert.equal(response.status,status,`${method} ${url}: ${JSON.stringify(value)}`);return {value,cookie:response.headers.get('set-cookie')?.split(';')[0]||cookie};
}
const master=await req('/api/auth/register','',{email:`qa-master-${randomUUID()}@traina.test`,name:'QA Master',workspace:'QA role and recording verification',password});
const slug=master.value.slug,base=`/api/t/${slug}/studio`,team=`/api/t/${slug}/team`;
async function join(role,name){const invite=await req(team+'/invitations',master.cookie,{email:`qa-${randomUUID()}@traina.test`,role});const token=invite.value.path.split('=')[1];const user=await req('/api/auth/accept-invitation','',{token,name,password});assert.equal(user.value.role,role);await req('/api/auth/accept-invitation','',{token,name,password},409);return user;}
const senior=await join('SENIOR','QA Senior'),learner=await join('LEARNER','QA Learner One'),learner2=await join('LEARNER','QA Learner Two');
assert.equal(master.value.role,'MASTER');await req(base+'/sessions',master.cookie,{},403);await req(base+'/sessions',learner.cookie,{},403);await req(team+'/invitations',senior.cookie,{email:'blocked@example.com',role:'SENIOR'},403);pass('Single-use invitations and roles enforced');
const sample=(await req(base+'/example',senior.cookie,{})).value,samplePath=base+'/sessions/'+sample.id;
await req(samplePath,master.cookie,undefined,403);await req(`/api/t/${slug}/workmaps`,learner.cookie,undefined,403);await req(samplePath+'/practice',senior.cookie,{},403);await req(samplePath+'/practice/save',learner.cookie,{choiceId:'a'},409);
const outsider=await req('/api/auth/demo','',{});await req(samplePath,outsider.cookie,undefined,401);await req(`/api/t/${outsider.value.slug}/studio/sessions/${sample.id}`,outsider.cookie,undefined,404);pass('Direct API and known-ID access rejected');
const screen=await fetch(origin+sample.events[0].screenUrl,{headers:{Cookie:senior.cookie}});assert.equal(screen.status,200);const jpeg=Buffer.from(await screen.arrayBuffer());assert(jpeg.length>1000);await writeFile(path.join(qa,'screen.jpg'),jpeg);
const activity=(await req(base+'/sessions',senior.cookie,{title:'QA invoice classification',objective:'Verify and classify invoice 4471',application:'Fictional invoice workspace',initialState:'An unverified equipment invoice for EUR 7,200',expectedOutcome:'Book to capex only with a valid asset number',rules:'Above EUR 5,000 equipment is capex; without an asset number, stop.'})).value;
const activityPath=base+'/sessions/'+activity.id;
await req(activityPath,learner.cookie,undefined,403);await req(activityPath+'/frame',senior.cookie,{image:'data:image/jpeg;base64,'+jpeg.toString('base64'),sequence:0,elapsedSeconds:2},202);await req(activityPath+'/frame',senior.cookie,{image:'data:image/jpeg;base64,'+jpeg.toString('base64'),sequence:0,elapsedSeconds:2},202);assert.equal((await req(activityPath,senior.cookie)).value.pipeline.received,1);pass('Durable asynchronous 202 screenshot receipt is idempotent');
if(live){
 const status=(await req(base+'/status?verify=true',master.cookie)).value;await writeFile(path.join(qa,'providers.json'),JSON.stringify(status,null,2));assert(status.claude.verified,status.claude.error);assert(status.agent.verified,status.agent.error);assert(status.scribe.verified,status.scribe.error);pass('Real Claude, ElevenLabs Agent and Scribe token permissions');
 const speech=await fetch(origin+base+'/speech',{method:'POST',headers:{Cookie:senior.cookie,'Content-Type':'application/json'},body:JSON.stringify({sessionId:sample.id,messageId:sample.messages.find(m=>m.role==='assistant').id})});assert.equal(speech.status,200);const agentAudio=Buffer.from(await speech.arrayBuffer());assert(agentAudio.length>1000);await writeFile(path.join(qa,'claude.wav'),agentAudio);pass('Real Agent voice of a saved Claude response');
 const ffmpeg=path.resolve('.runtime/tools/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe');
 function ff(args){execFileSync(ffmpeg,['-hide_banner','-loglevel','error','-y',...args],{stdio:'pipe'});}
 ff(['-i',path.join(qa,'claude.wav'),'-ac','1','-ar','16000','-f','s16le',path.join(qa,'scribe.pcm')]);
 const pcm=await readFile(path.join(qa,'scribe.pcm')),token=(await req(activityPath+'/scribe-token',senior.cookie,{})).value.token;
 const query=new URLSearchParams({token,model_id:'scribe_v2_realtime',audio_format:'pcm_16000',commit_strategy:'vad',vad_silence_threshold_secs:'1.5',include_timestamps:'true',include_language_detection:'true'});
 const ws=new WebSocket('wss://api.elevenlabs.io/v1/speech-to-text/realtime?'+query);
 const transcript=await new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>{ws.close();reject(Error('Scribe did not return a committed transcript within 45 seconds.'));},45000);
  ws.addEventListener('error',()=>{clearTimeout(timer);reject(Error('Scribe WebSocket failed.'));});
  ws.addEventListener('message',({data})=>{const e=JSON.parse(data);if(e.message_type==='committed_transcript_with_timestamps'&&e.text?.trim()){clearTimeout(timer);ws.close();resolve({text:e.text,language:e.language_code,words:e.words?.length});}else if(e.error||e.message_type.includes('error')){clearTimeout(timer);ws.close();reject(Error('Scribe: '+(e.error||e.message_type)));}});
  ws.addEventListener('open',async()=>{for(let offset=0;offset<pcm.length;offset+=8192){if(ws.readyState!==WebSocket.OPEN)return;ws.send(JSON.stringify({message_type:'input_audio_chunk',audio_base_64:pcm.subarray(offset,offset+8192).toString('base64'),sample_rate:16000}));await new Promise(r=>setTimeout(r,256));}ws.send(JSON.stringify({message_type:'input_audio_chunk',audio_base_64:Buffer.alloc(64000).toString('base64'),sample_rate:16000,commit:true}));});
 });assert(transcript.text.length>4);await writeFile(path.join(qa,'scribe-result.json'),JSON.stringify(transcript,null,2));pass('Real Scribe transcription, language detection and timestamps');
 ff(['-loop','1','-i',path.join(qa,'screen.jpg'),'-f','lavfi','-i','sine=frequency=330:sample_rate=16000','-t','4','-vf','scale=640:396','-r','5','-c:v','libvpx','-b:v','150k','-c:a','libopus',path.join(qa,'video.webm')]);
 ff(['-f','lavfi','-i','sine=frequency=330:sample_rate=16000','-t','4','-c:a','libopus',path.join(qa,'audio.webm')]);
 async function upload(kind,start=0){const id=randomUUID();await req(activityPath+'/recordings/open',senior.cookie,{recordingId:id,kind,startSeconds:start});const bytes=await readFile(path.join(qa,kind+'.webm'));const form=new FormData();for(const [k,v]of Object.entries({recordingId:id,sequence:0,startSeconds:start,endSeconds:start+4,kind}))form.append(k,String(v));form.append('file',new Blob([bytes]),'fragment.webm');const r=await fetch(origin+activityPath+'/recordings',{method:'POST',headers:{Cookie:senior.cookie},body:form});assert.equal(r.status,200,await r.text());await req(activityPath+`/recordings/${id}/close`,senior.cookie,{count:1,endSeconds:start+4});}
 await upload('video');await upload('audio');
 async function wait(test,label){for(let i=0;i<90;i++){const s=(await req(activityPath,senior.cookie)).value;if(s.processingError)throw Error(s.processingError);if(test(s))return s;await new Promise(r=>setTimeout(r,1000));}throw Error('Timed out: '+label);}
 await wait(s=>s.pipeline.pending===0&&s.events.length>0,'screen analysis');
 await req(activityPath+'/turn',senior.cookie,{kind:'message',text:'La factura es de equipo por 7,200 euros. El límite para capex es 5,000 euros. Nunca registro capex sin número de activo. Si el proveedor es desconocido, paro y pregunto al controller.',language:'auto',elapsedSeconds:3,startSeconds:0,endSeconds:4});
 await req(activityPath+'/finish',senior.cookie,{},202);let current=await wait(s=>s.phase==='debrief'&&s.pendingQuestionId,'debrief');
 const answers=[
  'Siempre verifico el número de activo y la identidad del proveedor antes de guardar. La ausencia de cualquiera bloquea el registro y escalo al controller.',
  'Si el importe es exactamente 5,000 euros necesito confirmar la clasificación con el controller; no asumo que aplicar más de 5,000 incluya el límite exacto.',
  'Comparo importe, proveedor y número de factura con la evidencia original. Si hay discrepancia o duplicado no guardo, registro la duda y pido confirmación al controller.'
 ];
 for(const text of answers){current=await wait(s=>s.phase==='debrief'&&!!s.pendingQuestionId,'new follow-up');const q=current.messages.find(m=>m.id===current.pendingQuestionId);await req(activityPath+`/questions/${q.id}/delivered`,senior.cookie,{activeSeconds:q.elapsedSeconds||current.activeSeconds});current=(await req(activityPath+'/turn',senior.cookie,{kind:'message',text,language:'auto',elapsedSeconds:4})).value;}
 current=await wait(s=>s.phase==='review','teach-back');assert.equal(current.debriefAnswers,3);assert.equal(new Set(current.messages.filter(m=>m.stage==='debrief'&&m.role==='assistant').map(m=>m.text)).size,3);assert(current.map.steps.every(s=>current.events.some(e=>e.id===s.event_id)));assert(current.messages.some(m=>m.role==='user'&&m.translated));assert(current.map.steps.some(s=>s.translated));
 const video=current.recordings.find(r=>r.kind==='video-final'),audio=current.recordings.find(r=>r.kind==='audio-final');assert(video&&audio);const range=await fetch(origin+video.url,{headers:{Cookie:senior.cookie,Range:'bytes=0-99'}});assert.equal(range.status,206);assert.equal((await range.arrayBuffer()).byteLength,100);await req(video.url,master.cookie,undefined,403);pass('Audio/video consolidation, authenticated ranges, three new follow-ups and translated evidence');
 current=(await req(activityPath+'/confirm',senior.cookie,{map:current.map})).value;assert.equal(current.phase,'confirmed');assert.equal(current.publicationVersion,1);pass('Explicit teach-back confirmation publishes a version');
 async function practice(user){let s=(await req(activityPath+'/practice',user.cookie,{})).value;assert.equal(s.practice.choices.length,3);for(const c of s.practice.choices){s=(await req(activityPath+'/practice/check',user.cookie,{choiceId:c.id})).value;if(s.validation.allowed){return (await req(activityPath+'/practice/save',user.cookie,{choiceId:c.id})).value;}}throw Error('No validated practice choice.');}
 const saved=await practice(learner);assert.equal(saved.completedPractices,1);const untouched=(await req(activityPath,learner2.cookie)).value;assert(!untouched.practice&&!untouched.validation);await practice(learner2);assert.equal((await req(activityPath,learner.cookie)).value.completedPractices,1);pass('Two learners have separate attempts, validation and progress');
 const first=current.events[0];current=(await req(activityPath+'/events/'+first.id,senior.cookie,undefined,200,'DELETE')).value;assert.equal(current.mapConfirmed,false);assert(!current.map);await req(activityPath,learner.cookie,undefined,403);await req(first.screenUrl,senior.cookie,undefined,404);pass('Evidence removal withdraws publication and screenshot access');
}
await writeFile(path.join(qa,'browser-context.json'),JSON.stringify({slug,masterCookie:master.cookie,seniorCookie:senior.cookie,learnerCookie:learner.cookie,learner2Cookie:learner2.cookie,sampleId:sample.id,activityId:activity.id},null,2));
await writeFile(path.join(qa,'verification.json'),JSON.stringify({at:new Date().toISOString(),live,passed,workspace:slug},null,2));
console.log(`Completed ${passed.length} checks. Report: .runtime/qa/verification.json`);
