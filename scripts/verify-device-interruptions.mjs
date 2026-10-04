import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';

const qa = path.resolve('.runtime/qa');
const context = JSON.parse(await readFile(path.join(qa, 'browser-context.json'), 'utf8'));
const origin = process.env.APP_URL || 'http://127.0.0.1:3000';
const require = createRequire(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json'));
const { chromium } = require('playwright');
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
await page.context().addCookies([{ name: 'traina_session', value: context.seniorCookie.split('=')[1], url: origin }]);
await page.context().grantPermissions(['camera', 'microphone'], { origin });
const errors = [], frameRequests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/frame')) frameRequests.push(Date.now()); });
await page.addInitScript(() => {
  const streams = [];
  let pcmChunks = 0;
  const getUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia = async constraints => { const stream = await getUserMedia(constraints); streams.push(stream); return stream; };
  const send = WebSocket.prototype.send;
  WebSocket.prototype.send = function (payload) {
    if (typeof payload === 'string') {
      try { const parsed = JSON.parse(payload); if (parsed.message_type === 'input_audio_chunk' && parsed.audio_base_64) pcmChunks++; } catch { /* Other messages carry no test evidence. */ }
    }
    return send.call(this, payload);
  };
  window.__trainaDeviceQA = {
    snapshot: () => ({ pcmChunks, streams: streams.length, tracks: streams.flatMap(stream => stream.getTracks()).map(track => ({ kind: track.kind, enabled: track.enabled, readyState: track.readyState })) }),
    interrupt: kind => {
      const track = streams.flatMap(stream => stream.getTracks()).reverse().find(track => track.kind === kind && track.enabled && track.readyState === 'live');
      if (!track) throw new Error('No active test track to interrupt.');
      // stop() does not emit ended by itself; emit the browser event that accompanies device loss.
      track.stop(); track.dispatchEvent(new Event('ended'));
    }
  };
});
const snapshot = () => page.evaluate(() => window.__trainaDeviceQA.snapshot());
const bothLive = state => ['audio', 'video'].every(kind => state.tracks.some(track => track.kind === kind && track.readyState === 'live' && track.enabled));
try {
  await page.goto(origin + '/w/' + context.slug);
  await page.getByRole('heading', { name: 'Capture the thinking behind the task.' }).waitFor();
  for (const [label, value] of Object.entries({
    'Activity title': 'QA camera and microphone interruptions',
    'What are you trying to accomplish?': 'Verify device loss pauses both devices and supports reconnecting',
    'Application / tool': 'Fictional invoice sandbox',
    'Starting situation and inputs': 'A fictional browser activity with test camera and test microphone',
    'Expected result and how to verify it': 'No new evidence after device loss; both devices reconnect on resume'
  })) await page.getByLabel(label, { exact: false }).fill(value);
  const creation = page.waitForResponse(response => response.url().endsWith('/studio/sessions') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Create activity', exact: true }).click();
  const activity = await (await creation).json();
  assert(activity.id);
  await page.getByRole('button', { name: 'Fictional sandbox', exact: true }).click();
  await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click();
  await page.getByRole('button', { name: 'Go off record', exact: true }).waitFor();
  await page.waitForTimeout(3500);
  assert(bothLive(await snapshot()));
  await page.getByRole('button', { name: 'My activities', exact: true }).click();
  await page.getByText('Go off record and complete or retry uploads before leaving this activity.', { exact: true }).waitFor();
  assert(bothLive(await snapshot()), 'A blocked navigation must preserve the active recording owner.');
  assert(await page.getByRole('button', { name: 'Go off record', exact: true }).isVisible());
  const tested = [];
  for (const kind of ['video', 'audio']) {
    const before = await snapshot();
    await page.evaluate(kind => window.__trainaDeviceQA.interrupt(kind), kind);
    await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click({ trial: true, timeout: 60000 });
    const paused = await snapshot(), pausedFrames = frameRequests.length;
    assert(paused.tracks.filter(track => track.readyState === 'live').every(track => !track.enabled), `${kind} loss must pause both devices.`);
    await page.waitForTimeout(2500);
    assert.equal(frameRequests.length, pausedFrames);
    assert.equal((await snapshot()).pcmChunks, paused.pcmChunks);
    await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click();
    await page.getByRole('button', { name: 'Go off record', exact: true }).waitFor();
    await page.waitForTimeout(3500);
    const restored = await snapshot();
    assert(restored.streams > before.streams, `Resume must reacquire camera and microphone after ${kind} loss.`);
    assert(bothLive(restored));
    assert(restored.pcmChunks > paused.pcmChunks + 5);
    assert(frameRequests.length > pausedFrames);
    tested.push({ device: kind === 'video' ? 'camera' : 'microphone', pausedBoth: true, suppressedNewSamples: true, reacquiredBoth: true });
    console.log(`${kind === 'video' ? 'Camera' : 'Microphone'} interruption and resume passed.`);
  }
  const framePattern = '**/studio/sessions/*/frame';
  await page.route(framePattern, route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'QA simulated upload interruption' }) }));
  await page.waitForResponse(response => response.url().endsWith('/frame') && response.status() === 503);
  await page.getByRole('button', { name: 'Go off record', exact: true }).click();
  await page.getByRole('button', { name: 'Retry uploads', exact: true }).click({ trial: true, timeout: 60000 });
  await page.getByRole('button', { name: 'My activities', exact: true }).click();
  await page.getByText('Go off record and complete or retry uploads before leaving this activity.', { exact: true }).waitFor();
  assert(await page.getByRole('button', { name: 'Retry uploads', exact: true }).isVisible(), 'Pending uploads must keep the activity and retry buffer mounted.');
  await page.unroute(framePattern);
  await page.getByRole('button', { name: 'Retry uploads', exact: true }).click();
  await page.getByRole('button', { name: 'Start / resume recording', exact: true }).click({ trial: true, timeout: 60000 });
  await page.getByRole('button', { name: 'My activities', exact: true }).click();
  await page.waitForFunction(() => window.__trainaDeviceQA.snapshot().tracks.every(track => track.readyState === 'ended'), null, { timeout: 15000 });
  assert.deepEqual(errors, []);
  await writeFile(path.join(qa, 'device-interruption-verification.json'), JSON.stringify({ at: new Date().toISOString(), activityId: activity.id, method: 'Browser test camera and microphone with simulated hardware ended events', tested, activeNavigationBlocked: true, pendingUploadNavigationBlocked: true, retriedUploadsBeforeLeaving: true, disposalEndsTracks: true, pageErrors: errors }, null, 2));
  console.log('Camera/microphone interruption, reconnect and disposal verified.');
} finally {
  await browser.close();
}
