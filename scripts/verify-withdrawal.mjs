import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
const qa=path.resolve('.runtime/qa'),ctx=JSON.parse(await readFile(path.join(qa,'browser-context.json'),'utf8')),origin='http://127.0.0.1:3000',url=`/api/t/${ctx.slug}/studio/sessions/${ctx.activityId}`,headers={Cookie:ctx.seniorCookie};
const response=await fetch(origin+url,{headers});assert.equal(response.status,200);const s=await response.json();assert.equal(s.mapConfirmed,false);assert(s.removedRanges.length>0);const denied=await fetch(origin+url,{headers:{Cookie:ctx.learnerCookie}});assert.equal(denied.status,403);
const ff=path.resolve('.runtime/tools/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe');const video=s.recordings.find(r=>r.kind==='video-final'),audio=s.recordings.find(r=>r.kind==='audio-final');assert(video&&audio);
for(const [entry,name]of [[video,'withdrawn.mp4'],[audio,'withdrawn.m4a']]){const r=await fetch(origin+entry.url,{headers});assert.equal(r.status,200);await writeFile(path.join(qa,name),Buffer.from(await r.arrayBuffer()));}
execFileSync(ff,['-hide_banner','-loglevel','error','-y','-ss','2.5','-i',path.join(qa,'withdrawn.mp4'),'-frames:v','1','-vf','scale=16:16','-pix_fmt','gray','-f','rawvideo',path.join(qa,'withdrawn-pixels.gray')],{stdio:'pipe'});
execFileSync(ff,['-hide_banner','-loglevel','error','-y','-ss','2.5','-i',path.join(qa,'withdrawn.m4a'),'-t','0.5','-ac','1','-ar','16000','-f','s16le',path.join(qa,'withdrawn-audio.pcm')],{stdio:'pipe'});
const pixels=await readFile(path.join(qa,'withdrawn-pixels.gray')),samples=await readFile(path.join(qa,'withdrawn-audio.pcm'));assert(pixels.length>0&&samples.length>0);const brightness=[...pixels].reduce((a,b)=>a+b,0)/pixels.length;let amplitude=0;for(let i=0;i<samples.length;i+=2)amplitude+=Math.abs(samples.readInt16LE(i));amplitude/=samples.length/2;assert(brightness<5,'Removed screen remains visible');assert(amplitude<25,'Removed audio remains audible');
await writeFile(path.join(qa,'withdrawal-verification.json'),JSON.stringify({at:new Date().toISOString(),passed:['Data retained after Main restart','Learner cannot read withdrawn activity','Removed video is black','Linked audio is silent'],brightness,amplitude},null,2));console.log('Restart persistence and encoded video/audio withdrawal verified.');
