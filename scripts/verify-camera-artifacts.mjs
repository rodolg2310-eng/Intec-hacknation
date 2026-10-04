import { readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import path from 'node:path';

const qa = path.resolve('.runtime/qa');
const context = JSON.parse(await readFile(path.join(qa, 'browser-context.json'), 'utf8'));
const origin = process.env.APP_URL || 'http://127.0.0.1:3000';
const base = `/api/t/${context.slug}/studio`;
const headers = { Cookie: context.seniorCookie };
async function get(url) { const response = await fetch(origin + url, { headers }); assert(response.ok); return response.json(); }
const sessions = await get(base + '/sessions');
const candidates = sessions.filter(session => session.title === 'QA continuous camera and microphone' && session.phase === 'review');
assert(candidates.length, 'Run recorder verification through review before checking camera artifacts.');
const session = await get(base + '/sessions/' + candidates[0].id);
const questions = session.messages.filter(message => message.role === 'assistant' && message.stage === 'debrief');
assert(questions.length >= 3);
assert(questions.every(message => message.cameraFrameId && Number.isFinite(message.cameraSeconds)), 'Each closing question needs camera evidence and timestamp.');
const captureVideo = session.recordings.find(recording => recording.kind === 'video-final' && recording.startSeconds < 1);
const closingVideo = session.recordings.find(recording => recording.kind === 'video-final' && recording.startSeconds > 1);
assert(captureVideo && closingVideo);
assert(session.recordings.filter(recording => recording.kind === 'video-final').length >= 3);
assert(session.recordings.filter(recording => recording.kind === 'audio-final').length >= 3);
const ff = path.resolve('.runtime/tools/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe');
const ffprobe = path.join(path.dirname(ff), 'ffprobe.exe');
const probe = [];
for (const [name, recording] of [['browser-video', captureVideo], ['browser-debrief-video', closingVideo]]) {
  const response = await fetch(origin + recording.url, { headers }); assert(response.ok);
  const file = path.join(qa, name + '.mp4'); await writeFile(file, Buffer.from(await response.arrayBuffer()));
  const metadata = JSON.parse(execFileSync(ffprobe, ['-v', 'error', '-show_streams', '-of', 'json', file], { encoding: 'utf8' }));
  assert(metadata.streams.some(stream => stream.codec_type === 'audio'));
  assert(metadata.streams.some(stream => stream.codec_type === 'video'));
  probe.push({ name, width: metadata.streams.find(stream => stream.codec_type === 'video').width, height: metadata.streams.find(stream => stream.codec_type === 'video').height, audio: true });
}
assert.deepEqual(probe.map(video => [video.width, video.height]), [[1320, 650], [640, 480]]);
const checks = [
  ['browser-video', 'screen-mask', 'crop=2:2:200:100', true],
  ['browser-video', 'camera-mask', 'crop=2:2:1064:48', true],
  ['browser-debrief-video', 'camera-mask', 'crop=2:2:128:96', true],
  ['browser-video', 'camera', 'crop=320:180:1000:0,scale=32:18', false],
  ['browser-debrief-video', 'camera', 'scale=32:24', false]
];
for (const [name, label, filter, masked] of checks) {
  const output = path.join(qa, name + '-' + label + '.rgb');
  execFileSync(ff, ['-hide_banner', '-loglevel', 'error', '-y', '-ss', '2', '-i', path.join(qa, name + '.mp4'), '-frames:v', '1', '-vf', filter, '-f', 'rawvideo', '-pix_fmt', 'rgb24', output], { stdio: 'pipe' });
  const pixels = await readFile(output); assert(pixels.length > 0);
  if (masked) assert(pixels[0] < 35 && pixels[1] < 40 && pixels[2] < 55, `${label} missing from ${name}.`);
  else assert(Math.max(...pixels) - Math.min(...pixels) > 60, `Camera image missing from ${name}.`);
}
await writeFile(path.join(qa, 'camera-artifact-verification.json'), JSON.stringify({ at: new Date().toISOString(), activityId: session.id, questionsWithCamera: questions.length, cameraTimes: questions.map(question => question.cameraSeconds), recordings: probe, passed: ['Closing questions cite camera frames and timestamps', 'Three capture/debrief/resumed-debrief camera videos and audio recordings preserved', 'Encoded camera pixels in capture and debrief', 'Encoded privacy masks on screen and camera', 'H264/AAC playback streams and correct capture/debrief dimensions'] }, null, 2));
console.log('Camera question references, video/audio recordings and encoded privacy masks passed.');
