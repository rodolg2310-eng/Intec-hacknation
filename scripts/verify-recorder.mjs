import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import assert from 'node:assert/strict';

const qa = path.resolve('.runtime/qa');
const ctx = JSON.parse(await readFile(path.join(qa, 'browser-context.json'), 'utf8'));
const origin = process.env.APP_URL || 'http://127.0.0.1:3000';
const base = `/api/t/${ctx.slug}/studio`;
const headers = { Cookie: ctx.seniorCookie, 'Content-Type': 'application/json' };
const require = createRequire(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json'));
const { chromium } = require('playwright');
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
await page.context().addCookies([{ name: 'traina_session', value: ctx.seniorCookie.split('=')[1], url: origin }]);
await page.context().grantPermissions(['camera', 'microphone'], { origin });
const errors = [], requests = { frames: [], cameraFrames: [], transcripts: 0 };
page.on('pageerror', error => errors.push(error.message));
// Count device and speech activity without retaining Scribe tokens or audio contents.
await page.addInitScript(() => {
  const streams = [], playback = [];
  let pcmChunks = 0;
  const getUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia = async constraints => {
    const stream = await getUserMedia(constraints);
    streams.push(stream);
    return stream;
  };
  const send = WebSocket.prototype.send;
  WebSocket.prototype.send = function (payload) {
    if (typeof payload === 'string') {
      try { const message = JSON.parse(payload); if (message.message_type === 'input_audio_chunk' && message.audio_base_64) pcmChunks++; } catch { /* Non-Scribe messages are irrelevant. */ }
    }
    return send.call(this, payload);
  };
  const tracks = () => streams.flatMap(stream => stream.getTracks()).map(track => ({ kind: track.kind, enabled: track.enabled, readyState: track.readyState }));
  const start = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function (...args) {
    playback.push({ tracks: tracks(), pcmChunks });
    return start.apply(this, args);
  };
  window.__trainaCaptureQA = { snapshot: () => ({ tracks: tracks(), pcmChunks, playback: playback.map(entry => ({ ...entry })) }) };
});
page.on('request', request => {
  if (request.method() !== 'POST') return;
  if (request.url().endsWith('/frame')) {
    const body = request.postDataJSON();
    requests.frames.push({ sequence: body.sequence, elapsedSeconds: body.elapsedSeconds, at: Date.now(), camera: typeof body.cameraImage === 'string' && body.cameraImage.startsWith('data:image/jpeg;base64,') });
  } else if (request.url().endsWith('/camera-frame')) {
    const body = request.postDataJSON();
    requests.cameraFrames.push({ sequence: body.sequence, elapsedSeconds: body.elapsedSeconds, at: Date.now(), camera: typeof body.image === 'string' && body.image.startsWith('data:image/jpeg;base64,') });
  } else if (request.url().endsWith('/turn') && request.postDataJSON()?.kind === 'transcript') requests.transcripts++;
});
const snapshot = () => page.evaluate(() => window.__trainaCaptureQA.snapshot());
const bothLive = state => ['audio', 'video'].every(kind => state.tracks.some(track => track.kind === kind && track.readyState === 'live' && track.enabled));
async function currentSession(url) {
  const response = await fetch(origin + url, { headers });
  assert(response.ok, `Session read failed: ${response.status}`);
  return response.json();
}
async function waitForSession(url, predicate, label, maxSeconds = 90) {
  for (let second = 0; second < maxSeconds; second++) {
    const session = await currentSession(url);
    assert(!session.processingError, session.processingError);
    assert(!session.questionError, session.questionError);
    if (predicate(session)) return session;
    await page.waitForTimeout(1000);
  }
  assert.fail(`Timed out waiting for ${label}`);
}
async function post(url, body = {}) {
  const response = await fetch(origin + url, { method: 'POST', headers, body: JSON.stringify(body) });
  if (!response.ok) assert.fail(`QA operation failed: ${response.status} ${await response.text()}`);
  return response.json();
}

try {
  await page.goto(origin + '/w/' + ctx.slug);
  await page.getByRole('heading', { name: 'Capture the thinking behind the task.' }).waitFor();
  for (const [label, value] of Object.entries({
    'Activity title': 'QA continuous camera and microphone',
    'What are you trying to accomplish?': 'Verify camera, microphone, privacy masks and recorded questions',
    'Application / tool': 'Fictional invoice sandbox',
    'Starting situation and inputs': 'Invoice 4471 is unclassified; fictional equipment costs EUR 7,200',
    'Expected result and how to verify it': 'Screen and camera evidence, microphone transcription and synchronized recordings'
  })) await page.getByLabel(label, { exact: false }).fill(value);
  const createdPromise = page.waitForResponse(response => response.url().endsWith('/studio/sessions') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Create activity', exact: true }).click();
  const created = await (await createdPromise).json();
  assert(created.id, JSON.stringify(created));
  const url = base + '/sessions/' + created.id;
  await page.getByRole('button', { name: 'Fictional sandbox', exact: true }).click();
  await page.getByRole('button', { name: 'Add privacy masks', exact: true }).click();
  const bounds = await page.getByLabel('Masked screen preview', { exact: true }).boundingBox();
  assert(bounds);
  await page.mouse.move(bounds.x + bounds.width * .1, bounds.y + bounds.height * .1);
  await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width * .35, bounds.y + bounds.height * .3);
  await page.mouse.up();
  await page.getByRole('button', { name: 'Finish masking', exact: true }).click();
  await page.getByRole('button', { name: 'Connect camera & microphone', exact: true }).click();
  await page.getByRole('button', { name: 'Add camera masks', exact: true }).click();
  const cameraBounds = await page.getByLabel('Masked camera preview', { exact: true }).boundingBox();
  assert(cameraBounds);
  await page.mouse.move(cameraBounds.x + cameraBounds.width * .1, cameraBounds.y + cameraBounds.height * .1);
  await page.mouse.down();
  await page.mouse.move(cameraBounds.x + cameraBounds.width * .35, cameraBounds.y + cameraBounds.height * .3);
  await page.mouse.up();
  await page.getByRole('button', { name: 'Finish camera masking', exact: true }).click();
  // Slow HTTP replies must not slow either screen sampling or microphone input.
  await page.route('**/studio/sessions/*/frame', async route => {
    const response = await route.fetch();
    await new Promise(resolve => setTimeout(resolve, 7000));
    await route.fulfill({ response });
  });
  await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click();
  await page.getByRole('button', { name: 'Go off record', exact: true }).waitFor();
  await page.waitForTimeout(12500);
  await page.screenshot({ path: path.join(qa, 'senior-recording.png'), fullPage: true });
  const active = await currentSession(url), activeDevices = await snapshot();
  assert(active.pipeline.received >= 5, JSON.stringify(active.pipeline));
  assert.equal(active.openRecordings.length, 2);
  assert(bothLive(activeDevices), 'Both camera and microphone must remain enabled throughout capture.');
  assert(activeDevices.pcmChunks > 10, 'The microphone must continuously send PCM to Scribe.');
  assert(requests.frames.length >= 5 && requests.frames.every(frame => frame.camera), 'Every sampled screen frame must include camera evidence.');
  for (let index = 1; index < requests.frames.length; index++) {
    assert.equal(requests.frames[index].sequence, requests.frames[index - 1].sequence + 1);
    assert(requests.frames[index].at - requests.frames[index - 1].at <= 2500, 'Camera and screen must be sampled every two seconds even with delayed uploads.');
  }
  await page.getByRole('button', { name: 'Go off record', exact: true }).click();
  await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click({ trial: true, timeout: 60000 });
  const stoppedDevices = await snapshot(), stoppedCounts = { screen: requests.frames.length, camera: requests.cameraFrames.length, transcripts: requests.transcripts };
  assert(stoppedDevices.tracks.filter(track => track.readyState === 'live').every(track => !track.enabled), 'Off record must disable physical camera and microphone tracks.');
  const paused = await currentSession(url);
  assert.equal(paused.openRecordings.length, 0);
  await page.waitForTimeout(3000);
  const remainedStopped = await snapshot(), remainedPaused = await currentSession(url);
  assert.equal(requests.frames.length, stoppedCounts.screen);
  assert.equal(requests.cameraFrames.length, stoppedCounts.camera);
  assert.equal(requests.transcripts, stoppedCounts.transcripts);
  assert.equal(remainedStopped.pcmChunks, stoppedDevices.pcmChunks);
  assert.equal(remainedPaused.activeSeconds, paused.activeSeconds);
  console.log('Capture camera/microphone cadence and Off record passed.');

  await page.getByRole('button', { name: 'Finish activity & debrief', exact: true }).click();
  const debrief = await waitForSession(url, session => session.phase === 'debrief', 'debrief');
  assert(debrief.recordings.some(recording => recording.kind === 'video-final'));
  assert(debrief.recordings.some(recording => recording.kind === 'audio-final'));
  await page.getByRole('button', { name: 'Go off record', exact: true }).waitFor({ timeout: 60000 });
  const debriefBeginning = await snapshot(), captureFrameCount = requests.frames.length;
  await page.waitForTimeout(11500);
  const debriefDevices = await snapshot(), debriefActive = await currentSession(url);
  assert(bothLive(debriefDevices), 'Camera and microphone must continue during the closing interview.');
  assert(debriefDevices.pcmChunks > debriefBeginning.pcmChunks + 10);
  assert.equal(debriefActive.openRecordings.length, 2, 'The closing interview must preserve camera video and microphone audio.');
  assert.equal(requests.frames.length, captureFrameCount, 'Debrief must sample the camera without collecting new screen images.');
  assert(requests.cameraFrames.length >= 4 && requests.cameraFrames.every(frame => frame.camera), 'Debrief must send camera evidence every two seconds.');
  for (let index = 1; index < requests.cameraFrames.length; index++) {
    assert.equal(requests.cameraFrames[index].sequence, requests.cameraFrames[index - 1].sequence + 1);
    assert(requests.cameraFrames[index].at - requests.cameraFrames[index - 1].at <= 2500);
    assert(requests.cameraFrames[index].elapsedSeconds >= requests.cameraFrames[index - 1].elapsedSeconds);
  }
  // Questions are played through the mixed recording while microphone input stays active.
  await waitForSession(url, session => Boolean(session.pendingQuestionId), 'a closing question');
  const playback = page.getByRole('button', { name: 'Play Claude response', exact: true }).last();
  await playback.waitFor();
  const beforeQuestion = await snapshot();
  await playback.click();
  await page.waitForFunction(before => window.__trainaCaptureQA.snapshot().playback.length > before, beforeQuestion.playback.length, { timeout: 60000 });
  const duringQuestion = await snapshot();
  assert(bothLive(duringQuestion.playback.at(-1)), 'Playing Claude questions must leave the camera and microphone enabled.');
  await page.waitForTimeout(2500);
  assert((await snapshot()).pcmChunks > duringQuestion.pcmChunks, 'Microphone streaming must continue while Claude speaks.');
  await page.getByRole('button', { name: 'Go off record', exact: true }).click();
  await page.getByRole('button', { name: 'Resume debrief camera & microphone', exact: true }).click({ trial: true, timeout: 60000 });
  const stoppedDebrief = await snapshot(), stoppedCameraCount = requests.cameraFrames.length;
  await page.waitForTimeout(2500);
  assert.equal(requests.cameraFrames.length, stoppedCameraCount);
  assert.equal((await snapshot()).pcmChunks, stoppedDebrief.pcmChunks);
  assert(stoppedDebrief.tracks.filter(track => track.readyState === 'live').every(track => !track.enabled));
  console.log('Debrief camera/microphone, spoken questions and Off record passed.');

  await page.getByRole('button', { name: 'Resume debrief camera & microphone', exact: true }).click();
  await page.getByRole('button', { name: 'Go off record', exact: true }).waitFor();
  await page.waitForTimeout(2500);
  assert(requests.cameraFrames.length > stoppedCameraCount, 'Resuming the interview must restore fresh camera evidence before further questions.');
  assert(bothLive(await snapshot()));
  // Finish the QA interview so both original capture and closing video are finalized.
  const answers = [
    'This is a fictional QA activity. I chose capital expenditure because invoice 4471 is equipment costing EUR 7,200. Equipment over EUR 5,000 belongs to capex, and I verified asset number ASSET-102 before posting.',
    'I stop and ask the controller when an asset number is missing or the supplier is unknown. I check the source invoice, correct account 0400, and cost center before committing the booking.',
    'The verification is to compare the saved booking with invoice 4471, asset ASSET-102 and EUR 7,200. A mismatch requires holding the invoice for correction; the camera in this QA run is a browser test device.'
  ];
  for (const answer of answers) {
    const pending = await waitForSession(url, session => session.phase === 'debrief' && Boolean(session.pendingQuestionId), 'next distinct closing question');
    const message = pending.messages.find(item => item.id === pending.pendingQuestionId);
    assert(message.cameraFrameId && Number.isFinite(message.cameraSeconds), 'Every new closing question must carry its camera evidence reference.');
    assert(message.cameraSeconds >= active.activeSeconds, 'Closing questions must use camera evidence from the closing interview.');
    await post(url + `/questions/${message.id}/delivered`, { activeSeconds: message.elapsedSeconds || pending.mediaSeconds });
    await post(url + '/turn', { kind: 'message', text: answer, language: 'en', elapsedSeconds: pending.mediaSeconds });
  }
  const final = await waitForSession(url, session => session.phase === 'review' && session.openRecordings.length === 0 && session.recordings.filter(recording => recording.kind === 'video-final').length >= 3 && session.recordings.filter(recording => recording.kind === 'audio-final').length >= 3, 'capture and debrief media consolidation');
  assert.equal(final.debriefAnswers, 3);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: path.join(qa, 'camera-debrief-review.png'), fullPage: true });
  // Disposal must release hardware, rather than only suppressing outgoing samples.
  await page.getByRole('button', { name: 'My activities', exact: true }).click();
  const alternate = await currentSession(base + '/sessions/' + ctx.activityId);
  await page.getByRole('button').filter({ has: page.getByRole('heading', { name: alternate.title, exact: true }) }).click();
  await page.waitForFunction(() => window.__trainaCaptureQA.snapshot().tracks.every(track => track.readyState === 'ended'), null, { timeout: 15000 });
  await page.close();

  const captureVideo = final.recordings.find(recording => recording.kind === 'video-final' && recording.startSeconds < 1);
  const closingVideo = final.recordings.find(recording => recording.kind === 'video-final' && recording.startSeconds > 1);
  assert(captureVideo && closingVideo);
  const ff = path.resolve('.runtime/tools/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe');
  for (const [name, recording] of [['browser-video', captureVideo], ['browser-debrief-video', closingVideo]]) {
    const response = await fetch(origin + recording.url, { headers });
    assert(response.ok);
    await writeFile(path.join(qa, name + '.mp4'), Buffer.from(await response.arrayBuffer()));
  }
  execFileSync(ff, ['-hide_banner', '-loglevel', 'error', '-y', '-ss', '2', '-i', path.join(qa, 'browser-video.mp4'), '-frames:v', '1', '-vf', 'crop=2:2:200:100', '-f', 'rawvideo', '-pix_fmt', 'rgb24', path.join(qa, 'masked-pixel.rgb')], { stdio: 'pipe' });
  const pixel = await readFile(path.join(qa, 'masked-pixel.rgb'));
  assert(pixel.length > 0);
  assert(pixel[0] < 35 && pixel[1] < 40 && pixel[2] < 55, [...pixel].join(','));
  for (const [name, filter] of [['browser-video', 'crop=320:180:1000:0,scale=32:18'], ['browser-debrief-video', 'scale=32:24']]) {
    const output = path.join(qa, name + '-camera.rgb');
    execFileSync(ff, ['-hide_banner', '-loglevel', 'error', '-y', '-ss', '2', '-i', path.join(qa, name + '.mp4'), '-frames:v', '1', '-vf', filter, '-f', 'rawvideo', '-pix_fmt', 'rgb24', output], { stdio: 'pipe' });
    const camera = await readFile(output);
    assert(camera.length > 0 && Math.max(...camera) - Math.min(...camera) > 60, `Camera pixels are missing from ${name}.`);
  }
  for (const [name, filter] of [['browser-video', 'crop=2:2:1064:48'], ['browser-debrief-video', 'crop=2:2:128:96']]) {
    const output = path.join(qa, name + '-camera-mask.rgb');
    execFileSync(ff, ['-hide_banner', '-loglevel', 'error', '-y', '-ss', '2', '-i', path.join(qa, name + '.mp4'), '-frames:v', '1', '-vf', filter, '-f', 'rawvideo', '-pix_fmt', 'rgb24', output], { stdio: 'pipe' });
    const cameraMask = await readFile(output);
    assert(cameraMask.length > 0 && cameraMask[0] < 35 && cameraMask[1] < 40 && cameraMask[2] < 55, `Camera privacy mask is missing from ${name}.`);
  }
  await writeFile(path.join(qa, 'recorder-verification.json'), JSON.stringify({
    at: new Date().toISOString(), activityId: created.id, screenshots: active.pipeline.received,
    captureCameraFrames: requests.frames.length, debriefCameraFrames: requests.cameraFrames.length,
    activeSeconds: paused.activeSeconds, pcmChunks: debriefDevices.pcmChunks,
    passed: ['Physical camera and microphone enabled throughout capture and debrief', 'Screen and camera sampled every two seconds during slow HTTP replies', 'Microphone PCM continues while Claude questions play', 'Off record disables camera and microphone with no new samples', 'Privacy masks in encoded screen and camera video', 'Camera pixels in capture and closing video', 'Capture and debrief audio/video retained in playback manifests', 'Three distinct closing questions with camera references and review', 'Changing activity disposes camera and microphone tracks'], pageErrors: errors
  }, null, 2));
  console.log('Continuous camera, microphone, questions, encoded recordings and Off record verified.');
} finally {
  await browser.close();
}
