/* The microphone is the sole input. TTS is mixed only into the recording track. */
class TrainaPCM extends AudioWorkletProcessor {
  constructor() { super(); this.pending = []; this.sum = 0; this.count = 0; }
  process(inputs) {
    const channel = inputs[0]?.[0];
    if (channel) for (let i = 0; i < channel.length; i++) { const v = Math.max(-1, Math.min(1, channel[i])); this.pending.push(v < 0 ? v * 32768 : v * 32767); this.sum += v * v; this.count++; }
    if (this.pending.length >= 4096) { const pcm = new Int16Array(this.pending.splice(0, 4096)); this.port.postMessage({ pcm, rms: Math.sqrt(this.sum / this.count) }, [pcm.buffer]); this.sum = 0; this.count = 0; }
    return true;
  }
}
registerProcessor("traina-pcm", TrainaPCM);
