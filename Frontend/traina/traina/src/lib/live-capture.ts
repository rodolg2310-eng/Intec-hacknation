import { post, studioPath, type StudioSession } from "./api";
import { drawMasks, type Mask } from "./capture-privacy";

export type CaptureState = { recording: boolean; offRecord: boolean; activeSeconds: number; mediaSeconds: number; uploads: number; speaking: boolean; speech: string; connection: string; camera: string; microphone: string; startup?: string };
type Job = { run: () => Promise<void>; error?: unknown; promise?: Promise<void> };
type Recorder = { recorder: MediaRecorder; id: string; count: number; start: number; kind: "video" | "audio"; completion: Promise<void> };
/** Sampling and recording never wait for Claude. Only received bytes leave the retry buffer. */
export class LiveCapture {
  readonly canvas = document.createElement("canvas");
  readonly source = document.createElement("video");
  readonly cameraSource = document.createElement("video");
  readonly cameraCanvas = document.createElement("canvas");
  private readonly recordedCanvas = document.createElement("canvas");
  state: CaptureState = { recording: false, offRecord: false, activeSeconds: 0, mediaSeconds: 0, uploads: 0, speaking: false, speech: "", connection: "Transcription not connected", camera: "Not connected", microphone: "Not connected" };
  masks: Mask[] = [];
  cameraMasks: Mask[] = [];
  preview: HTMLCanvasElement | undefined;
  cameraPreview: HTMLCanvasElement | undefined;
  session: StudioSession;
  private screen?: MediaStream;
  private mic: MediaStream | undefined;
  private devices: MediaStream | undefined;
  private context: AudioContext | undefined;
  private mix: MediaStreamAudioDestinationNode | undefined;
  private pcm: AudioWorkletNode | undefined;
  private socket: WebSocket | undefined;
  private timer?: ReturnType<typeof setInterval>;
  private recorders: Recorder[] = [];
  private jobs = new Set<Job>();
  private transcriptJobs = new Set<Promise<unknown>>();
  private sequence = 0;
  private lastTick = performance.now();
  private lastFrame = 0;
  private cameraSequence = 0;
  private lastHeart = 0;
  private lastSpeech = performance.now();
  private continuousStart = 0;
  private speechOffset = 0;
  private closed = false;
  private audioSource: AudioBufferSourceNode | undefined;
  private latestCommit = "";
  private waitingToClose: Recorder[] = [];
  private pausing: Promise<void> | undefined;
  private starting: Promise<void> | undefined;
  private connectingDevices: Promise<void> | undefined;
  private deviceGeneration = 0;
  constructor(private slug: string, session: StudioSession, private onState: (s: CaptureState) => void, private onError: (e: unknown) => void, private onLimit: () => void) {
    this.session = session;
    this.state.activeSeconds = session.activeSeconds || 0;
    this.state.mediaSeconds = session.mediaSeconds || session.activeSeconds || 0;
    this.sequence = session.pipeline?.nextSequence ?? session.pipeline?.received ?? 0;
    this.cameraSequence = session.cameraContext?.nextSequence ?? 0;
    this.source.muted = true; this.source.playsInline = true;
    this.cameraSource.muted = true; this.cameraSource.playsInline = true;
    this.cameraCanvas.width = 640; this.cameraCanvas.height = 480;
  }
  private path(suffix: string) { return studioPath(this.slug, `/sessions/${this.session.id}${suffix}`); }
  canLeave() { return !this.state.recording && !this.starting && !this.connectingDevices && !this.pausing && !this.jobs.size && !this.waitingToClose.length; }
  private async waitFor<T>(work: Promise<T>, message: string, milliseconds = 10000): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try { return await Promise.race([work, new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new Error(message)), milliseconds); })]); }
    finally { if (timer) clearTimeout(timer); }
  }
  private async recordingRequest<T>(suffix: string, body: unknown = {}) {
    const controller = new AbortController();
    try { return await this.waitFor(post<T>(this.path(suffix), body, { signal: controller.signal }), "The recording server did not respond. Check your connection and try again.", 15000); }
    finally { controller.abort(); }
  }
  private async uploadFragment(form: FormData) {
    const controller = new AbortController();
    try {
      await this.waitFor((async () => {
        const response = await fetch(this.path("/recordings"), { method: "POST", credentials: "same-origin", body: form, signal: controller.signal });
        const receipt = await response.json();
        if (!response.ok) throw new Error(receipt.error || "Recording upload failed. Retry before finishing.");
      })(), "Recording upload timed out. Your fragment is buffered; retry uploads before finishing.", 15000);
    } finally { controller.abort(); }
  }
  private startup(message: string) { this.state.startup = message; this.emit(); }
  private async waitForDevices() {
    if (this.closed || !this.devices || this.devices.getTracks().some(t => t.readyState !== "live" || !t.enabled)) {
      throw new Error("Camera or microphone became unavailable. Reconnect both devices to continue.");
    }
  }
  private emit() { this.state.uploads = this.jobs.size + this.waitingToClose.length; this.onState({ ...this.state }); }
  private releaseScreen() { this.screen?.getTracks().forEach(t => { t.onended = null; t.stop(); }); this.source.srcObject = null; }
  async selectScreen() {
    if (!navigator.mediaDevices?.getDisplayMedia) throw new Error("Open Chrome or Edge on localhost to share your screen.");
    const screen = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 10 }, audio: false });
    this.releaseScreen(); this.screen = screen;
    this.source.srcObject = this.screen; await this.source.play();
    this.canvas.width = Math.max(2, Math.floor(Math.min(this.source.videoWidth || 1280, 1280) / 2) * 2);
    this.canvas.height = Math.max(2, Math.round((this.source.videoHeight || 720) * this.canvas.width / (this.source.videoWidth || 1280) / 2) * 2);
    this.screen.getVideoTracks()[0]!.onended = () => { void this.pause(true); this.onError(new Error("Screen sharing ended. Your uploaded evidence is saved; select a screen to continue.")); };
    this.startTimer(); this.render();
  }
  async sandbox() {
    this.releaseScreen(); this.canvas.width = 1000; this.canvas.height = 650; this.screen = this.canvas.captureStream(10); this.startTimer(); this.render();
  }
  sandboxDecision = "4711 / Opex";
  private render() {
    const c = this.canvas.getContext("2d")!;
    if (this.source.videoWidth) c.drawImage(this.source, 0, 0, this.canvas.width, this.canvas.height);
    else { c.fillStyle = "#f6f7f2"; c.fillRect(0, 0, 1000, 650); c.fillStyle = "#244f3c"; c.fillRect(0, 0, 1000, 75); c.fillStyle = "white"; c.font = "bold 26px sans-serif"; c.fillText("Invoice workspace / Fictional data", 35, 48); c.fillStyle = "#23372c"; c.font = "bold 34px sans-serif"; c.fillText("Invoice 4471", 40, 160); c.font = "24px sans-serif"; ["Supplier: Equipment North", "Amount: EUR 7,200", "Asset number: ASSET-102", "Cost center: " + this.sandboxDecision, "Expected result: verified invoice booking"].forEach((text, i) => c.fillText(text, 40, 245 + i * 60)); }
    drawMasks(c, this.canvas.width, this.canvas.height, this.masks);
    if (this.preview) { if (this.preview.width !== this.canvas.width) this.preview.width = this.canvas.width; if (this.preview.height !== this.canvas.height) this.preview.height = this.canvas.height; this.preview.getContext("2d")!.drawImage(this.canvas, 0, 0); }
    const camera = this.cameraCanvas.getContext("2d")!;
    camera.fillStyle = "#111827"; camera.fillRect(0, 0, 640, 480);
    if (!this.state.offRecord && this.cameraSource.videoWidth) camera.drawImage(this.cameraSource, 0, 0, 640, 480);
    drawMasks(camera, 640, 480, this.cameraMasks);
    if (this.cameraPreview) { this.cameraPreview.width = 640; this.cameraPreview.height = 480; this.cameraPreview.getContext("2d")!.drawImage(this.cameraCanvas, 0, 0); }
    const recording = this.recordedCanvas.getContext("2d")!;
    if (this.session.phase === "capture") {
      if (this.recordedCanvas.width !== this.canvas.width + 320) this.recordedCanvas.width = this.canvas.width + 320;
      if (this.recordedCanvas.height !== this.canvas.height) this.recordedCanvas.height = this.canvas.height;
      recording.fillStyle = "#111827"; recording.fillRect(0, 0, this.recordedCanvas.width, this.recordedCanvas.height);
      recording.drawImage(this.canvas, 0, 0); recording.drawImage(this.cameraCanvas, this.canvas.width, 0, 320, 240);
      recording.fillStyle = "white"; recording.font = "16px sans-serif"; recording.fillText("Senior camera", this.canvas.width + 16, 268);
    } else {
      if (this.recordedCanvas.width !== 640) this.recordedCanvas.width = 640;
      if (this.recordedCanvas.height !== 480) this.recordedCanvas.height = 480;
      recording.drawImage(this.cameraCanvas, 0, 0);
    }
  }
  private startTimer() { if (!this.timer) this.timer = setInterval(() => this.tick(), 100); }
  private tick() {
    if (this.closed) return;
    const now = performance.now(), delta = Math.max(0, (now - this.lastTick) / 1000); this.lastTick = now;
    this.render();
    if (this.state.recording && !this.state.offRecord) {
      this.state.mediaSeconds += delta;
      if (this.session.phase === "capture") this.state.activeSeconds = Math.min(3600, this.state.activeSeconds + delta);
      if (now - this.lastFrame >= 2000) {
        this.lastFrame = now; const cameraImage = this.cameraCanvas.toDataURL("image/jpeg", .6);
        if (this.session.phase === "capture") { const sequence = this.sequence++, image = this.canvas.toDataURL("image/jpeg", .65), elapsedSeconds = Math.floor(this.state.activeSeconds); this.enqueue(async () => { await this.recordingRequest("/frame", { image, cameraImage, sequence, elapsedSeconds, offRecord: false }); }); }
        else if (this.session.phase === "debrief") { const sequence = this.cameraSequence++, elapsedSeconds = Math.floor(this.state.mediaSeconds); this.enqueue(async () => { await this.recordingRequest("/camera-frame", { image: cameraImage, sequence, elapsedSeconds, offRecord: false }); }); }
      }
      if (now - this.lastHeart >= 2000) { this.lastHeart = now; void this.recordingRequest("/heartbeat", { activeSeconds: Math.floor(this.state.activeSeconds), mediaSeconds: this.state.mediaSeconds, speaking: this.state.speaking, idleMs: Math.floor(now - this.lastSpeech), continuousMs: this.continuousStart ? Math.floor(now - this.continuousStart) : 0, offRecord: false }).catch(this.onError); }
      if (this.session.phase === "capture" && this.state.activeSeconds >= 3600) { void this.pause(true); this.onLimit(); }
      if (this.jobs.size > 18) { void this.pause(true); this.onError(new Error("Uploads are behind. Recording paused with a bounded retry buffer; retry uploads before continuing.")); }
    }
    if (now - this.lastSpeech >= 1500) { this.state.speaking = false; this.continuousStart = 0; }
    this.emit();
  }
  private enqueue(run: () => Promise<void>) { const job: Job = { run }; this.jobs.add(job); this.runJob(job); }
  private runJob(job: Job) { job.error = undefined; job.promise = job.run().then(() => { this.jobs.delete(job); }).catch((e) => { job.error = e; this.onError(e); }).finally(() => this.emit()); }
  async retryUploads() { for (const job of this.jobs) if (job.error) this.runJob(job); await this.flush(); for (const group of this.waitingToClose) { if (!group.count) await this.recordingRequest(`/recordings/${group.id}/cancel`); else await this.recordingRequest(`/recordings/${group.id}/close`, { count: group.count, endSeconds: this.state.mediaSeconds }); } this.waitingToClose = []; this.emit(); }
  private async flush() { await Promise.all([...this.jobs].map(j => j.promise)); const failed = [...this.jobs].find(j => j.error); if (failed) throw failed.error; }
  async connectDevices() {
    if (this.connectingDevices) return this.connectingDevices;
    this.connectingDevices = this.connectDevicesNow();
    try { await this.connectingDevices; } finally { this.connectingDevices = undefined; }
  }
  private async connectDevicesNow() {
    if (this.closed) throw new Error("This recording has been closed.");
    if (this.devices?.getTracks().every(t => t.readyState === "live") && this.context?.state !== "closed" && this.context && this.mix && this.pcm) { await this.enableDevices(); return; }
    if (!navigator.mediaDevices?.getUserMedia) throw new Error("Open Chrome or Edge on localhost and allow camera and microphone access.");
    this.devices?.getTracks().forEach(t => t.stop());
    if (this.context) await this.context.close();
    this.context = undefined; this.mix = undefined; this.pcm = undefined;
    const generation = ++this.deviceGeneration;
    try {
    try { this.devices = await this.waitFor(navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 10, max: 15 }, facingMode: "user" } }).then(stream => { if (this.closed || generation !== this.deviceGeneration) { stream.getTracks().forEach(t => t.stop()); throw new Error("Camera permission request expired. Try again."); } return stream; }), "Camera and microphone permission is still pending. Allow both devices in your browser, then try again.", 30000); }
    catch (error) { this.state.camera = "Permission required"; this.state.microphone = "Permission required"; throw error instanceof Error && error.name === "Error" ? error : new Error("Camera and microphone are required for recording and questions. Allow both in your browser, then try again."); }
    if (!this.devices) throw new Error("Camera and microphone were not connected. Try again.");
    if (this.closed) { this.devices.getTracks().forEach(t => t.stop()); throw new Error("This recording has been closed."); }
    const cameraTracks = this.devices.getVideoTracks(), micTracks = this.devices.getAudioTracks();
    if (!cameraTracks.length || !micTracks.length) { this.devices.getTracks().forEach(t => t.stop()); this.devices = undefined; throw new Error("Connect both a camera and a microphone before recording."); }
    this.cameraSource.srcObject = new MediaStream(cameraTracks); this.mic = new MediaStream(micTracks);
    for (const track of this.devices.getTracks()) {
      const interrupted = () => { if (!this.closed && this.state.recording && !this.state.offRecord) { void this.pause(true).catch(this.onError); this.onError(new Error(`${track.kind === "video" ? "Camera" : "Microphone"} was interrupted. Recording is paused; reconnect both devices to continue.`)); } };
      track.onended = interrupted; track.onmute = interrupted;
    }
    this.context = new AudioContext({ sampleRate: 16000 }); await this.waitFor(this.context.resume(), "Browser audio did not start. Click Start again; allow microphone and sound access.");
    this.mix = this.context.createMediaStreamDestination();
    const input = this.context.createMediaStreamSource(this.mic); input.connect(this.mix);
    await this.waitFor(this.context.audioWorklet.addModule("/pcm-worklet.js"), "Microphone processing did not load. Check the connection and try again."); this.pcm = new AudioWorkletNode(this.context, "traina-pcm"); input.connect(this.pcm);
    const silent = this.context.createGain(); silent.gain.value = 0; this.pcm.connect(silent).connect(this.context.destination);
    this.pcm.port.onmessage = ({ data }: MessageEvent<{ pcm: Int16Array; rms: number }>) => {
      if (!this.state.recording || this.state.offRecord || this.closed) return;
      const now = performance.now(); if (data.rms > .018) { if (!this.continuousStart) this.continuousStart = now; this.lastSpeech = now; this.state.speaking = true; }
      if (this.socket?.readyState === WebSocket.OPEN) { const bytes = new Uint8Array(data.pcm.buffer); let binary = ""; for (const b of bytes) binary += String.fromCharCode(b); this.socket.send(JSON.stringify({ message_type: "input_audio_chunk", audio_base_64: btoa(binary), sample_rate: 16000 })); }
      this.tick(); // Audio clock keeps sampling when the page is behind the shared application.
    };
    await this.enableDevices(); this.startTimer(); this.render();
    } catch (error) {
      this.deviceGeneration++; this.devices?.getTracks().forEach(t => t.stop()); this.devices = undefined; this.mic = undefined; this.cameraSource.srcObject = null;
      const context = this.context; this.context = undefined; this.mix = undefined; this.pcm = undefined; if (context && context.state !== "closed") void context.close().catch(() => {});
      if (this.state.camera !== "Permission required") { this.state.camera = "Not connected"; this.state.microphone = "Not connected"; } this.emit(); throw error;
    }
  }
  private async enableDevices() {
    this.devices?.getTracks().forEach(t => { t.enabled = true; });
    await this.waitFor(this.cameraSource.play(), "The camera preview did not start. Check that the camera is available and try again.");
    this.state.camera = "Live"; this.state.microphone = "Live"; this.emit();
  }
  private disableDevices() {
    this.devices?.getTracks().forEach(t => { t.enabled = false; });
    this.cameraSource.pause();
    this.state.camera = this.devices ? "Paused" : "Not connected"; this.state.microphone = this.devices ? "Paused" : "Not connected";
  }
  private async connectScribe() {
    this.state.connection = "Connecting live transcription…"; this.emit();
    const { token } = await this.recordingRequest<{ token: string }>("/scribe-token");
    if (!token) throw new Error("The server did not return a transcription token. Check ElevenLabs permissions and credits.");
    const query = new URLSearchParams({ token, model_id: "scribe_v2_realtime", audio_format: "pcm_16000", commit_strategy: "vad", vad_silence_threshold_secs: "1.5", include_timestamps: "true", include_language_detection: "true" });
    const ws = new WebSocket("wss://api.elevenlabs.io/v1/speech-to-text/realtime?" + query); this.socket = ws; this.speechOffset = this.state.mediaSeconds;
    await new Promise<void>((resolve, reject) => {
    let ready = false, failed = false;
    const timer = setTimeout(() => fail(new Error("Live transcription did not connect within 12 seconds. Check your network and ElevenLabs access, then try Start again.")), 12000);
    const fail = (error: Error) => { if (failed) return; failed = true; clearTimeout(timer); if (this.socket === ws) this.socket = undefined; ws.close(); this.state.connection = "Transcription unavailable"; this.emit(); if (!ready) reject(error); else { void this.pause(true).catch(this.onError); this.onError(error); } };
    ws.onerror = () => fail(new Error("Could not connect to ElevenLabs Scribe. Check your network, permissions and credits."));
    ws.onclose = () => { if (this.socket !== ws) return; fail(new Error("Live transcription disconnected. Recording is paused; try Start again to reconnect.")); };
    ws.onmessage = ({ data }) => {
      let event: { message_type: string; session_id?: string; text?: string; language_code?: string; words?: { start?: number; end?: number }[]; error?: string };
      try { event = JSON.parse(data); } catch { fail(new Error("ElevenLabs sent an invalid transcription response. Try again.")); return; }
      if (event.message_type === "session_started") { if (failed || ready) return; if (!event.session_id) { fail(new Error("ElevenLabs did not initialize the transcription session. Try again.")); return; } ready = true; clearTimeout(timer); this.latestCommit = ""; this.state.connection = "Listening / language detected automatically"; this.emit(); resolve(); return; }
      if (event.message_type === "commit_throttled") return;
      if (event.error || /^(error|auth_error|quota_exceeded|input_error|invalid_request|unaccepted_terms|rate_limited|queue_overflow|resource_exhausted|session_time_limit_exceeded|chunk_size_exceeded|insufficient_audio_activity)$/.test(event.message_type)) { fail(new Error(`ElevenLabs Scribe could not continue (${event.message_type}). Check permissions, credits and your connection, then try again.`)); return; }
      if (!ready || failed) return;
      if (event.message_type === "partial_transcript") this.state.speech = event.text || "";
      if (event.message_type === "committed_transcript_with_timestamps" && event.text?.trim()) {
        const key = JSON.stringify([event.text, event.words?.[0]?.start, event.words?.at(-1)?.end]); if (key === this.latestCommit) return; this.latestCommit = key;
        const startSeconds = this.speechOffset + (event.words?.[0]?.start || 0), endSeconds = this.speechOffset + (event.words?.at(-1)?.end || Math.max(0, this.state.mediaSeconds - this.speechOffset));
        const clientId = crypto.randomUUID(), text = event.text, language = event.language_code || "auto", elapsedSeconds = Math.floor(this.state.mediaSeconds); this.enqueue(async () => { await post(this.path("/turn"), { kind: "transcript", clientId, text, language, startSeconds, endSeconds, elapsedSeconds }); }); this.state.speech = "";
      }
      this.emit();
    };
    });
  }
  private async startRecorder(stream: MediaStream, kind: "video" | "audio") {
    const mimeType = kind === "video" ? "video/webm;codecs=vp8,opus" : "audio/webm;codecs=opus";
    if (!MediaRecorder.isTypeSupported(mimeType)) throw new Error("This browser cannot record the required format. Use Chrome or Edge.");
    const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 900000, audioBitsPerSecond: 64000 }), id = crypto.randomUUID(), start = this.state.mediaSeconds;
    try { await this.recordingRequest("/recordings/open", { recordingId: id, kind, startSeconds: start }); }
    catch (error) { stream.getVideoTracks().forEach(t => t.stop()); throw error; }
    const group = { recorder, id, count: 0, start, kind, completion: Promise.resolve() } as Recorder;
    let previous = start;
    recorder.ondataavailable = ({ data }) => { if (!data.size) return; const sequence = group.count++, end = this.state.mediaSeconds, begin = previous; previous = end; this.enqueue(async () => { const form = new FormData(); form.append("recordingId", id); form.append("sequence", String(sequence)); form.append("startSeconds", String(begin)); form.append("endSeconds", String(end)); form.append("kind", kind); form.append("file", data, "fragment.webm"); await this.uploadFragment(form); }); };
    group.completion = new Promise((resolve, reject) => { recorder.onstop = () => { recorder.stream.getVideoTracks().forEach(t => t.stop()); void (async () => { await this.flush(); if (!group.count) await this.recordingRequest(`/recordings/${id}/cancel`); else await this.recordingRequest(`/recordings/${id}/close`, { count: group.count, endSeconds: this.state.mediaSeconds }); })().then(resolve, reject); }; recorder.onerror = () => reject(new Error("The browser could not record this activity.")); });
    void group.completion.catch(this.onError); this.recorders.push(group); recorder.start(10000); setTimeout(() => { if (recorder.state === "recording") recorder.requestData(); }, 250);
  }
  async begin() {
    if (this.state.recording) return;
    if (this.starting) return this.starting;
    this.starting = this.beginNow();
    try { await this.starting; } finally { this.starting = undefined; this.state.startup = ""; this.emit(); }
  }
  private async beginNow() {
    if (this.pausing) await this.pausing;
    if (!this.screen && this.session.phase === "capture") throw new Error("Choose a screen or the fictional sandbox first.");
    try {
    this.startup("Starting camera and microphone…"); await this.connectDevices();
    this.startup("Starting browser audio…"); await this.waitFor(this.context!.resume(), "Browser audio did not start. Click Start again; allow microphone and sound access.");
    this.startup("Connecting ElevenLabs live transcription…"); await this.connectScribe(); this.startTimer();
    this.startup("Waiting for camera and microphone…"); await this.waitForDevices();
    this.state.offRecord = false; this.render();
    this.startup("Opening audio and video recording…");
    const masked = this.recordedCanvas.captureStream(10); const stream = new MediaStream([...masked.getVideoTracks(), ...this.mix!.stream.getAudioTracks()]); await this.startRecorder(stream, "video");
    await this.startRecorder(this.mix!.stream, "audio");
    if (this.closed || this.devices?.getTracks().some(t => t.readyState !== "live" || !t.enabled)) throw new Error("Camera or microphone became unavailable. Reconnect both devices to continue.");
    this.startup("Saving recording start…"); await this.recordingRequest("/heartbeat", { activeSeconds: Math.floor(this.state.activeSeconds), mediaSeconds: this.state.mediaSeconds, speaking: false, idleMs: 0, continuousMs: 0, offRecord: false });
    if (this.socket?.readyState !== WebSocket.OPEN) throw new Error("Live transcription disconnected during startup. Try Start again to reconnect.");
    this.state.recording = true; this.lastTick = performance.now(); this.lastFrame = performance.now() - 2000; this.lastHeart = performance.now(); this.emit(); } catch (error) { await this.pause(true).catch(this.onError); throw error; }
  }
  async pause(offRecord = true) {
    if (this.pausing) return this.pausing;
    this.pausing = this.pauseNow(offRecord);
    try { await this.pausing; } finally { this.pausing = undefined; }
  }
  private async pauseNow(offRecord: boolean) {
    const wasRecording = this.state.recording;
    this.state.recording = false; this.state.offRecord = offRecord; this.state.speaking = false; this.continuousStart = 0;
    const groups = this.recorders.splice(0); this.waitingToClose.push(...groups); for (const group of groups) if (group.recorder.state !== "inactive") group.recorder.stop();
    this.disableDevices(); this.audioSource?.stop(); this.audioSource = undefined; this.state.speech = ""; this.state.connection = "Transcription paused"; this.render(); this.emit();
    const socket = this.socket; this.socket = undefined; if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ message_type: "input_audio_chunk", audio_base_64: "", commit: true, sample_rate: 16000 }));
    if (wasRecording || groups.length) await this.recordingRequest("/heartbeat", { activeSeconds: Math.floor(this.state.activeSeconds), mediaSeconds: this.state.mediaSeconds, speaking: false, idleMs: 0, continuousMs: 0, offRecord: true }).catch(this.onError);
    // Allow the final committed transcript to arrive before closing and entering the debrief.
    if (socket) await new Promise<void>(resolve => setTimeout(resolve, 1800)); socket?.close();
    await Promise.all(groups.map(g => g.completion)); this.waitingToClose = this.waitingToClose.filter(g => !groups.includes(g)); await Promise.all(this.transcriptJobs); await this.flush(); this.emit();
  }
  async finish() { await this.pause(true); await post(this.path("/finish")); }
  async say(blob: Blob) {
    this.audioSource?.stop();
    if (this.context) { await this.context.resume(); const buffer = await this.context.decodeAudioData(await blob.arrayBuffer()); const source = this.context.createBufferSource(); source.buffer = buffer; source.connect(this.context.destination); if (this.mix) source.connect(this.mix); this.audioSource = source; source.start(); }
    else { const url = URL.createObjectURL(blob), audio = new Audio(url); audio.onended = () => URL.revokeObjectURL(url); await audio.play(); }
  }
  async dispose() { this.closed = true; this.disableDevices(); await this.pause(true).catch(this.onError); if (this.timer) clearInterval(this.timer); this.audioSource?.stop(); this.screen?.getTracks().forEach(t => t.stop()); this.devices?.getTracks().forEach(t => t.stop()); await this.context?.close(); }
}
