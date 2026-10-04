import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';

const qa=path.resolve('.runtime/qa');await mkdir(qa,{recursive:true});
const ctx=JSON.parse(await readFile(path.join(qa,'browser-context.json'),'utf8'));
const origin=process.env.APP_URL||'http://127.0.0.1:3000';
const scenario=process.argv.find(value=>value.startsWith('--scenario='))?.slice(11)||'normal';
const retry=process.argv.includes('--retry');
assert(['normal','token-hang','token-denied','socket-close','session-hang','audio-hang','video-hang','recording-hang','recording-denied'].includes(scenario),'Unknown startup scenario.');
assert(!retry||scenario==='socket-close','Retry verification currently supports the socket-close scenario.');
const require=createRequire(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json'));
const {chromium}=require('playwright');
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream']});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
await context.addCookies([{name:'traina_session',value:ctx.seniorCookie.slice(ctx.seniorCookie.indexOf('=')+1).split(';')[0],url:origin}]);
await context.grantPermissions(['camera','microphone'],{origin});
const page=await context.newPage(),events=[],errors=[],requests=[];
const started=Date.now();let activityId,connectedAt,sandboxAt,recordingAt,startClickAt,startDisabledWithoutScreen,reportError,failureRecovered=false,retryPassed=false,retryClickAt;
page.on('pageerror',error=>errors.push(error.message));
page.on('response',response=>{const url=new URL(response.url());if(url.origin===origin&&url.pathname.startsWith('/api/'))requests.push({method:response.request().method(),path:url.pathname,status:response.status(),afterMs:Date.now()-started});});
page.on('requestfailed',request=>{const url=new URL(request.url());if(url.origin===origin&&url.pathname.startsWith('/api/'))events.push({kind:'requestfailed',path:url.pathname,error:request.failure()?.errorText,afterMs:Date.now()-started});});
if(scenario!=='normal') {
  await page.route('**/studio/sessions/*/scribe-token',route=>scenario==='token-hang'?new Promise(()=>{}):route.fulfill({status:scenario==='token-denied'?503:200,contentType:'application/json',body:JSON.stringify(scenario==='token-denied'?{error:'QA Scribe token denied.'}:{token:'qa-only-test-token'})}));
  if(scenario==='recording-hang'||scenario==='recording-denied')await page.route('**/studio/sessions/*/recordings/open',route=>scenario==='recording-hang'?new Promise(()=>{}):route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'QA recorder open denied.'})}));
}
await page.addInitScript(scenario=>{
  const events=[],streams=[];const at=()=>Math.floor(performance.now()),activation=()=>({active:navigator.userActivation.isActive,everActive:navigator.userActivation.hasBeenActive});
  const getUserMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia=async constraints=>{
    events.push({kind:'getUserMedia',at:at(),activation:activation(),audio:!!constraints.audio,video:!!constraints.video});
    try {const stream=await getUserMedia(constraints);streams.push(stream);events.push({kind:'getUserMediaReady',at:at()});
      for(const track of stream.getTracks())for(const event of ['mute','unmute','ended'])track.addEventListener(event,()=>events.push({kind:'track-'+event,track:track.kind,at:at()}));
      return stream;
    } catch(error){events.push({kind:'getUserMediaError',name:error.name,at:at()});throw error;}
  };
  const resume=AudioContext.prototype.resume;
  AudioContext.prototype.resume=async function(...args){events.push({kind:'audioResume',at:at(),state:this.state,activation:activation()});if(window.__startupQA?.armed&&scenario==='audio-hang')return new Promise(()=>{});const result=await resume.apply(this,args);events.push({kind:'audioResumed',at:at(),state:this.state});return result;};
  const play=HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play=function(...args){if(window.__startupQA?.armed&&scenario==='video-hang')return new Promise(()=>{});return play.apply(this,args);};
  const OriginalSocket=WebSocket;
  if(scenario==='normal')window.WebSocket=class extends OriginalSocket {
    constructor(url,...args){super(url,...args);const target=new URL(url);events.push({kind:'socketCreated',host:target.hostname,path:target.pathname,at:at()});
      this.addEventListener('open',()=>events.push({kind:'socketOpen',at:at()}));
      this.addEventListener('error',()=>events.push({kind:'socketError',at:at()}));
      this.addEventListener('close',event=>events.push({kind:'socketClose',code:event.code,at:at()}));
      this.addEventListener('message',event=>{try{const data=JSON.parse(event.data);events.push({kind:'socketMessage',type:data.message_type||data.type,at:at()});}catch{}});
    }
  };
  else window.WebSocket=class extends EventTarget {
    static CONNECTING=0;static OPEN=1;static CLOSING=2;static CLOSED=3;
    readyState=0;onopen=null;onmessage=null;onerror=null;onclose=null;
    constructor(url){super();events.push({kind:'fakeSocketCreated',at:at()});setTimeout(()=>{
      if(this.readyState===3)return;
      if(scenario==='socket-close'&&!window.__startupQA?.failureDisabled){this.close(1006);return;}
      this.readyState=1;events.push({kind:'fakeSocketOpen',at:at()});this.emit('open',new Event('open'));
      if(scenario!=='session-hang')setTimeout(()=>{if(this.readyState===1)this.emit('message',new MessageEvent('message',{data:JSON.stringify({message_type:'session_started',session_id:'qa-only'})}));},10);
    },20);}
    emit(type,event){this['on'+type]?.(event);this.dispatchEvent(event);}
    send(){}
    close(code=1000){if(this.readyState===3)return;this.readyState=3;events.push({kind:'fakeSocketClose',code,at:at()});this.emit('close',new CloseEvent('close',{code,wasClean:code===1000}));}
  };
  window.__startupQA={armed:false,failureDisabled:false,snapshot:()=>({events:[...events],tracks:streams.flatMap(stream=>stream.getTracks().map(track=>({kind:track.kind,enabled:track.enabled,muted:track.muted,readyState:track.readyState})))})};
},scenario);
let deviceSnapshot,alerts=[];
try {
  await page.goto(origin+'/w/'+ctx.slug);
  await page.getByRole('heading',{name:'Capture the thinking behind the task.'}).waitFor();
  const title='QA Start after explicit Connect '+new Date().toISOString();
  for(const [label,value] of Object.entries({'Activity title':title,'What are you trying to accomplish?':'Verify that recording starts after connecting devices first','Application / tool':'Fictional invoice sandbox','Starting situation and inputs':'Fictional invoice 4471, equipment costing EUR 7,200','Expected result and how to verify it':'Recording badge, advancing clock, screenshots and media uploads'}))await page.getByLabel(label,{exact:false}).fill(value);
  const created=page.waitForResponse(response=>response.url().endsWith('/studio/sessions')&&response.request().method()==='POST');
  await page.getByRole('button',{name:'Create activity',exact:true}).click();
  const response=await created;assert.equal(response.status(),200);activityId=(await response.json()).id;
  await page.getByRole('button',{name:'Connect camera & microphone',exact:true}).click();
  await page.getByText('Camera / Live',{exact:true}).waitFor({timeout:15000});connectedAt=Date.now()-started;
  startDisabledWithoutScreen=await page.getByRole('button',{name:'Start / resume recording',exact:true}).isDisabled();
  await page.getByRole('button',{name:'Fictional sandbox',exact:true}).click();sandboxAt=Date.now()-started;
  const start=page.getByRole('button',{name:'Start / resume recording',exact:true});
  await start.click({trial:true,timeout:15000});await page.evaluate(()=>window.__startupQA.armed=true);startClickAt=Date.now()-started;await start.click();
  if(scenario!=='normal') {
    await page.getByRole('alert').first().waitFor({timeout:30000});
    alerts=await page.getByRole('alert').allTextContents();
    await start.click({trial:true,timeout:12000});
    assert(alerts.some(text=>text.trim()),'Startup failed without a visible explanation.');
    deviceSnapshot=await page.evaluate(()=>window.__startupQA.snapshot());
    assert(deviceSnapshot.tracks.length>=2&&deviceSnapshot.tracks.every(t=>!t.enabled||t.readyState==='ended'),'Failed startup left devices active.');
    assert.equal(requests.filter(r=>r.path.endsWith('/frame')).length,0,'Failed startup sent screen evidence.');
    assert.equal(await page.getByRole('button',{name:'Go off record',exact:true}).count(),0,'Failed startup falsely reports recording.');
    assert.deepEqual(errors,[],'Failed startup caused an uncaught JavaScript error.');
    failureRecovered=true;recordingAt=undefined;
    await page.locator('.capture-content').screenshot({path:path.join(qa,`startup-${scenario}.png`)});
    if(retry) {
      await page.evaluate(()=>window.__startupQA.failureDisabled=true);retryClickAt=Date.now()-started;
      await start.click();await page.getByRole('button',{name:'Go off record',exact:true}).waitFor({timeout:30000});
      recordingAt=Date.now()-started;await page.waitForTimeout(3000);
      deviceSnapshot=await page.evaluate(()=>window.__startupQA.snapshot());
      assert(['video','audio'].every(kind=>deviceSnapshot.tracks.some(track=>track.kind===kind&&track.enabled&&!track.muted&&track.readyState==='live')),'Retry did not enable both devices.');
      assert(requests.filter(response=>response.path.endsWith('/frame')&&response.status===202).length>=2,'Retry did not start screenshot capture.');
      assert.equal(requests.filter(response=>response.path.endsWith('/recordings/open')&&response.status===200).length,2,'Retry did not open both recorders.');
      await page.locator('.capture-content').screenshot({path:path.join(qa,'startup-same-activity-retry.png')});
      await page.getByRole('button',{name:'Go off record',exact:true}).click();await start.click({trial:true,timeout:45000});
      retryPassed=true;
    }
  } else {
  await page.getByRole('button',{name:'Go off record',exact:true}).waitFor({timeout:30000});recordingAt=Date.now()-started;
  await page.waitForTimeout(5000);
  deviceSnapshot=await page.evaluate(()=>window.__startupQA.snapshot());
  assert(deviceSnapshot.tracks.some(t=>t.kind==='video'&&t.enabled&&!t.muted&&t.readyState==='live'));
  assert(deviceSnapshot.tracks.some(t=>t.kind==='audio'&&t.enabled&&!t.muted&&t.readyState==='live'));
  assert(requests.filter(r=>r.path.endsWith('/frame')&&r.status===202).length>=2,'Captures did not start.');
  assert.equal(requests.filter(r=>r.path.endsWith('/recordings/open')&&r.status===200).length,2,'Both recordings did not open.');
  await page.locator('.capture-content').screenshot({path:path.join(qa,'startup-recording.png')});
  await page.locator('.capture-content').locator('..').evaluate(element=>element.scrollIntoView({block:'start',behavior:'instant'}));
  await page.waitForTimeout(100);
  const panel=await page.locator('.capture-content').locator('..').boundingBox();
  const cameraStatus=await page.locator('.camera-preview-section .microphone-status').boundingBox();
  assert(panel&&cameraStatus);
  await page.screenshot({path:path.join(qa,'startup-fixed-recording.png'),clip:{x:panel.x,y:panel.y,width:panel.width,height:cameraStatus.y+cameraStatus.height-panel.y+12}});
  await page.getByRole('button',{name:'Go off record',exact:true}).click();
  await page.getByRole('button',{name:'Start / resume recording',exact:true}).click({trial:true,timeout:45000});
  assert.equal(events.filter(event=>event.kind==='requestfailed'&&/\/recordings(?:\/|$)/.test(event.path)&&event.error?.includes('ERR_ABORTED')).length,0,'Successful recording requests were aborted before reading their acknowledgement.');
  }
} catch(error) {
  reportError=error.message;
  deviceSnapshot=await page.evaluate(()=>window.__startupQA?.snapshot()).catch(()=>undefined);
  alerts=await page.getByRole('alert').allTextContents().catch(()=>[]);
  await page.screenshot({path:path.join(qa,'startup-failure.png'),fullPage:true}).catch(()=>{});
} finally {
  let backendState;
  if(activityId) {const response=await fetch(origin+`/api/t/${ctx.slug}/studio/sessions/${activityId}`,{headers:{Cookie:ctx.seniorCookie}});if(response.ok){const value=await response.json();backendState={activeSeconds:value.activeSeconds,received:value.pipeline?.received,openRecordings:value.openRecordings?.length};assert.equal(backendState.openRecordings,0,'Startup cleanup left an open backend recording.');}}
  const report={at:new Date().toISOString(),scenario,activityId,passed:!reportError,failureRecovered,retryPassed,testDevices:'Chrome simulated camera and microphone; no physical hardware permission verification',backendState,steps:{connectedAt,sandboxAt,startClickAt,recordingAt,retryClickAt,startupMs:recordingAt===undefined?undefined:recordingAt-(retryClickAt??startClickAt),recoveryMs:failureRecovered?Date.now()-started-startClickAt:undefined},startDisabledWithoutScreen,deviceSnapshot,requests,events,pageErrors:errors,alerts,error:reportError};
  const name=scenario==='normal'?'startup-verification.json':`startup-${scenario}-verification.json`;
  await writeFile(path.join(qa,name),JSON.stringify(report,null,2));
  await browser.close();
  console.log(JSON.stringify({passed:report.passed,scenario,activityId,retryPassed,startupMs:report.steps.startupMs,recoveryMs:report.steps.recoveryMs,startDisabledWithoutScreen,alerts,error:reportError,report:'.runtime/qa/'+name},null,2));
}
if(reportError)process.exitCode=1;
