import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';

// Uses isolated QA accounts and one existing Claude closing question.
// Secrets and signed conversation URLs are never included in reports or logs.
const origin=process.env.APP_URL||'http://127.0.0.1:3000';
const qa=path.resolve('.runtime/qa');
await mkdir(qa,{recursive:true});
const ctx=JSON.parse(await readFile(path.join(qa,'browser-context.json'),'utf8'));
const fixture=JSON.parse(await readFile(path.join(qa,'camera-artifact-verification.json'),'utf8'));
const base=`/api/t/${encodeURIComponent(ctx.slug)}/studio`;
const sessionPath=`${base}/sessions/${encodeURIComponent(fixture.activityId)}`;
const headers={Cookie:ctx.seniorCookie,'Content-Type':'application/json'};
async function app(path,options={}) {
  const response=await fetch(origin+path,{headers,...options,signal:AbortSignal.timeout(120000)});
  assert.equal(response.status,200,`App request ${path} returned ${response.status}.`);
  return response;
}
const before=await (await app(sessionPath)).json();
assert.equal(before.phase,'review','The camera fixture must be in review; this test does not start recording.');
const candidates=before.messages.filter(m=>m.role==='assistant'&&m.stage==='debrief');
const question=candidates.at(-1);
assert(question,'The fixture needs a Claude closing question.');
assert(question.text?.trim(),'The Claude question must contain text.');
const input=JSON.stringify({sessionId:fixture.activityId,messageId:question.id});
const responses=await Promise.all([app(base+'/speech',{method:'POST',body:input}),app(base+'/speech',{method:'POST',body:input})]);
for(const response of responses)assert.match(response.headers.get('Content-Type')||'',/^audio\/wav\b/);
const clips=await Promise.all(responses.map(async r=>Buffer.from(await r.arrayBuffer())));
assert(clips[0].length>1000,'No usable Agent speech was received.');
assert.equal(clips[0].subarray(0,4).toString(),'RIFF');
assert.equal(clips[0].subarray(8,12).toString(),'WAVE');
assert.deepEqual(clips[0],clips[1],'Concurrent requests generated different audio instead of sharing one receipt.');
const after=await (await app(sessionPath)).json();
const receipt=after.messages.find(m=>m.id===question.id);
assert(receipt,'The original question was removed.');
assert.equal(receipt.text,question.text,'Speaking changed the saved Claude text.');
assert.equal(receipt.voiceProvider,'ElevenLabs Agent');
assert(receipt.voiceConversationId?.startsWith('conv_'),'The response has no genuine conversation ID.');
assert(receipt.voiceAgentId,'The response has no Agent ID.');

const env=Object.fromEntries((await readFile('.env','utf8')).replace(/^\uFEFF/,'').split(/\r?\n/)
  .map(line=>line.match(/^\s*([^#=]+)=(.*)$/)).filter(Boolean)
  .map(([,name,value])=>[name.trim(),value.trim().replace(/^(['"])(.*)\1$/,'$2')]));
assert(env.ELEVENLABS_API_KEY,'ElevenLabs API key is missing.');
assert.equal(receipt.voiceAgentId,env.ELEVENLABS_AGENT_ID,'The app used a different Agent.');
let conversation;
for(let attempt=0;attempt<20;attempt++) {
  const response=await fetch('https://api.elevenlabs.io/v1/convai/conversations/'+encodeURIComponent(receipt.voiceConversationId),{
    headers:{'xi-api-key':env.ELEVENLABS_API_KEY},signal:AbortSignal.timeout(30000)
  });
  assert.equal(response.status,200,`Conversation verification returned HTTP ${response.status}.`);
  conversation=await response.json();
  if(conversation.status==='done'||conversation.status==='failed')break;
  await new Promise(resolve=>setTimeout(resolve,2000));
}
assert.equal(conversation.status,'done','The voice conversation has not finished successfully.');
assert.equal(conversation.agent_id,receipt.voiceAgentId);
const turns=conversation.transcript||[];
assert.equal(turns.filter(t=>t.role==='agent').length,1,'Unexpected additional Agent turns.');
assert.equal(turns.find(t=>t.role==='agent').message,question.text,'The Agent did not speak the exact English Claude question.');
assert.equal(turns.filter(t=>t.role==='user').length,0,'User input unexpectedly reached the voice Agent.');
const charging=conversation.metadata?.charging;
assert(charging,'Conversation charging metadata is missing.');
assert.equal(charging.llm_price,0,'An unintended Agent LLM generated content.');
assert.equal(charging.llm_charge,0,'The Agent charged an unintended LLM call.');
const usage=charging.llm_usage;
assert(usage,'Conversation LLM usage metadata is missing.');
for(const [category,entry] of Object.entries(usage)) {
  assert.deepEqual(entry.model_usage||{}, {},`Unexpected model usage in ${category}.`);
  assert.deepEqual(entry.detailed_model_usage||[], [],`Unexpected detailed model usage in ${category}.`);
}
assert.equal(charging.asr_usage?.total_audio_input_seconds,0,'Unexpected microphone audio reached the Agent.');
assert.equal(charging.asr_usage?.total_transcription_calls,0,'The Agent unexpectedly transcribed microphone audio.');
await writeFile(path.join(qa,'app-agent-api-verification.json'),JSON.stringify({at:new Date().toISOString(),
  activityId:fixture.activityId,messageId:question.id,agentId:receipt.voiceAgentId,conversationId:receipt.voiceConversationId,
  originalTextUnchanged:true,concurrentAudioIdentical:true,audioBytes:clips[0].length,
  wasPreviouslyGenerated:!!question.voiceConversationId,conversationStatus:conversation.status,
  llmPrice:charging.llm_price,llmUsage:usage,agentTurns:1,userTurns:0,microphoneAudioSeconds:0},null,2));

const require=createRequire(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json'));
const {chromium}=require('playwright');
const browser=await chromium.launch({channel:'chrome',headless:true});
const errors=[];
const cookie=value=>({name:'traina_session',value:value.slice(value.indexOf('=')+1).split(';')[0],url:origin});
try {
  const seniorContext=await browser.newContext({viewport:{width:1440,height:1000}});
  await seniorContext.addCookies([cookie(ctx.seniorCookie)]);
  const page=await seniorContext.newPage();
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto(origin+'/w/'+encodeURIComponent(ctx.slug));
  await page.getByRole('heading',{name:'Capture the thinking behind the task.'}).waitFor();
  const escaped=before.title.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  const opened=page.waitForResponse(response=>response.url().endsWith(sessionPath)&&response.request().method()==='GET');
  await page.getByRole('button',{name:new RegExp('Awaiting confirmation '+escaped)}).first().click();
  assert.equal((await opened).status(),200,'The selected activity did not open.');
  await page.getByRole('heading',{name:before.title,exact:true,level:1}).waitFor();
  const disclosure=page.locator('.chat-message.assistant details').filter({hasText:receipt.voiceConversationId});
  await disclosure.locator('summary').waitFor();
  assert.equal(await disclosure.locator('summary').innerText(),'Voice / ElevenLabs Agent');
  await disclosure.locator('summary').click();
  assert(await disclosure.getByText(receipt.voiceConversationId,{exact:true}).isVisible(),'The conversation ID is not visible.');
  assert(await disclosure.getByText(receipt.voiceAgentId,{exact:true}).isVisible(),'The Agent ID is not visible.');
  await disclosure.scrollIntoViewIfNeeded();
  await page.locator('.interview-panel').screenshot({path:path.join(qa,'agent-voice-receipt.png')});
  const masterContext=await browser.newContext({viewport:{width:1440,height:1000}});
  await masterContext.addCookies([cookie(ctx.masterCookie)]);
  const master=await masterContext.newPage();
  master.on('pageerror',e=>errors.push(e.message));
  await master.goto(origin+'/w/'+encodeURIComponent(ctx.slug));
  await master.getByRole('button',{name:'Connections',exact:true}).click();
  await master.getByRole('button',{name:'Verify connections',exact:true}).click();
  await master.getByText('ElevenLabs Agent',{exact:true}).waitFor();
  await master.getByText('Claude-controlled voice',{exact:true}).waitFor();
  assert(await master.getByText(receipt.voiceAgentId,{exact:true}).isVisible());
  await master.screenshot({path:path.join(qa,'agent-connections.png'),fullPage:true});
} finally {await browser.close();}
assert.deepEqual(errors,[],'Browser JavaScript errors occurred.');
const report={at:new Date().toISOString(),activityId:fixture.activityId,messageId:question.id,
  agentId:receipt.voiceAgentId,conversationId:receipt.voiceConversationId,spokenText:question.text,
  audioBytes:clips[0].length,conversationStatus:conversation.status,
  llmPrice:charging.llm_price,llmCharge:charging.llm_charge,llmUsage:usage,
  voiceCredits:charging.call_charge,agentAudioSeconds:charging.tts_usage?.total_audio_output_seconds,
  pageErrors:errors,screenshots:['agent-voice-receipt.png','agent-connections.png'],
  passed:['Real HTTP speech endpoint uses configured Agent','Concurrent calls share exact WAV audio and one receipt',
    'Original Claude text unchanged','Genuine dashboard conversation matches message receipt',
    'One exact Agent utterance; no user input, ASR input or additional LLM usage',
    'Senior chat exposes authentic voice receipt','Master Connections verifies and labels the Agent']};
await writeFile(path.join(qa,'app-agent-verification.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({passed:true,conversationId:report.conversationId,audioBytes:report.audioBytes,llmPrice:report.llmPrice,report:'.runtime/qa/app-agent-verification.json'},null,2));
