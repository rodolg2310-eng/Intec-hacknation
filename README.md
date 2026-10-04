# Traina

Capture an expert's screen, camera and reasoning, confirm an evidence-linked Work Map, and let each learner practice privately. Claude handles observation, questions, teaching and evaluation. ElevenLabs Scribe transcribes the microphone, and the configured ElevenLabs Agent speaks Claude's responses through real agent conversations.

## Start with Main

Double-click **Main.cmd**, run **Main.java** in VS Code using “Main · Traina completa”, or run:

```powershell
.\Main.ps1
.\Main.ps1 -NoBrowser
.\Main.ps1 -CheckOnly
.\Main.ps1 -Stop
```

The site is **http://localhost:3000**, with the Java API on 8080. Main loads the root `.env`, prepares Maven and portable FFmpeg, builds changed code, starts the persistent H2 database and waits for both services. It uses a JDK 21+ from `JAVA_HOME` or `PATH`. Node.js 22+ (which includes npm) is required. Docker is optional.

For a public HTTPS deployment with persistent database and media storage, follow [DEPLOYMENT.md](DEPLOYMENT.md). The online Compose stack uses a Linux VM and Caddy; the local `Main.cmd` workflow remains for development.

## Run on another Windows computer

1. Install **JDK 21 or newer** and **Node.js 22 or newer**. Make sure `javac` is on `PATH`, or set `JAVA_HOME` to the JDK folder. The large local Oracle JDK archive is intentionally excluded from GitHub.
2. Clone this repository and open its folder in PowerShell or File Explorer.
3. Copy `.env.example` to `.env`. Add your own Claude and ElevenLabs credentials and Agent ID to `.env`; do not commit or share that file. Each computer needs its own provider credentials and access/credits.
4. Double-click `Main.cmd`, or run `.\Main.ps1` in PowerShell. On the first launch, Main downloads Maven and verified portable FFmpeg and installs the frontend packages. Internet access is needed for setup and AI services.
5. Open **http://localhost:3000**. Stop the local services with `Main.cmd -Stop`.

Accounts, activities, and recordings remain local to each computer in `.runtime/data`; they are not synced through GitHub.

Portable FFmpeg is pinned to **9.0.2**, downloaded from [Gyan's Windows builds](https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip), and checked against SHA-256 `60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba` before extraction. It stays inside `.runtime/tools`.

## Roles and invitations

| Role | Access |
| --- | --- |
| Master | Manage members, roles, seven-day invitations and draft assignments. |
| Senior | Record, review and publish their assigned activities. |
| Learner | Read published activities, replay evidence, ask and practice privately. |

Creating a workspace creates its only Master. A Master enters an email and Senior/Learner role, creates an invitation and shares its registration link. No SMTP is needed. The recipient sets their name and password. The email and role come from the invitation, which expires after seven days and can be used once or revoked. The Master cannot be removed or demoted. Existing accounts become Masters; preserved activities can be assigned to a Senior. Legacy published activities require their new Senior's review, and activities without audio are marked accordingly.

## Senior workflow

1. Enter a title, objective, application, initial state and expected outcome. Optionally add prerequisites, rules and risks. Senior identity comes from the signed-in account.
2. Share an application or use the fictional invoice sandbox. Connect the camera and microphone to preview them locally, draw screen and camera privacy masks, then start recording. Chrome/Edge need screen, camera and microphone permission. Both devices are required; denied permissions or interrupted devices pause recording with an error. Masks cover both snapshots and video before upload.
3. Screenshots are sampled every two seconds independently of Claude, accepted with HTTP 202 and processed in sequence from durable jobs. Exact consecutive duplicate images are skipped. The UI reports captured evidence, buffered uploads, analysis delay and connection state.
4. Screen video includes a separate camera panel, and microphone audio uploads incrementally. Camera snapshots accompany screen snapshots every two seconds. Scribe receives the microphone only, with echo cancellation, a temporary token and 1.5-second silence detection. Claude receives transcribed answers and recent camera context to ask about visible task materials; camera images do not establish screen decisions or infer emotion or attention. Claude's speech is mixed into the retained recording while the camera and microphone remain active. The active capture limit is one hour; pauses and **Off record** do not count. Off record disables camera and microphone tracks and stops recording, sampling and new microphone transcription. Leaving the activity releases both devices.
5. Claude presents one question at a time, targets 3–5 presented questions per ten-minute interval, uses pauses/key moments and asks for a pause when needed to reach the minimum. After four minutes of continuous speech it uses the next key moment. Deferred questions become closing doubts. Interval badges show actual delivery, including incomplete intervals after connection failures.
6. Finish the activity. All screenshot jobs and media uploads must finish before the debrief. Camera and microphone resume for the debrief, including during Claude's spoken questions; fresh camera snapshots continue every two seconds and camera video and audio are retained. Answer at least three new follow-ups, correct the Work Map and explicitly confirm the teach-back to publish a version. Debrief video and audio must finish uploading before publication.

Saved descriptive text is English. Spoken questions use the detected conversation language. Translated expert quotations are labeled and link to their original audio/video evidence. The summary follows **Example step / Screen moment / Decision / Reason / Guardrails**, with shaded labels and a blue edge.

**Startup:** Start shows the current preparation stage. Camera/audio preparation, Scribe acceptance and recording requests have bounded waits; failures release the loading state, pause both devices and allow a retry. Live transcription is ready only after Scribe accepts the session. Empty recording attempts are cancelled without removing received evidence.

**Recovery:** uploaded fragments and screenshots persist on the server. Interrupted processing resumes after restart. Retry failed uploads while the original browser tab is open. If the tab was lost, recover the received fragments from the activity; the recording remains explicitly marked incomplete. Bytes that never reached the server cannot be recovered. Missing fragments or failed processing block a successful finish.

**Withdraw evidence:** first go off record. Removing a screen moment deletes its image and linked text/TTS, blacks out its video and mutes linked audio ranges, withdraws published versions and requires a new debrief/review. Removed time remains black/silent to preserve the timing of other citations. Reopening a map for ordinary edits preserves previous versions and learner attempts.

## Learner workflow

Open a published activity, replay the full recording or jump to a cited screen/audio moment, and inspect its Work Map. Claude creates a new three-choice case. The backend requires a validated correct decision before saving. The practice summary uses the same five-row format and shows what was learned and what needs practice. Questions, feedback and progress belong to the learner account and publication version.

## Configuration and data

The existing root `.env` is retained. The frontend receives no provider keys.

| Variable | Purpose |
| --- | --- |
| `ANTHROPIC_API_KEY` | All Claude calls. |
| `ANTHROPIC_MODEL` | Defaults to `claude-sonnet-4-6`. |
| `ELEVENLABS_API_KEY` | Scribe temporary tokens and authenticated agent conversations. |
| `ELEVENLABS_VOICE_ID` | Retained standalone TTS configuration; the Agent's configured voice is used for spoken responses. |
| `ELEVENLABS_TTS_MODEL` | Retained standalone TTS configuration; spoken responses use the Agent's TTS configuration. |
| `ELEVENLABS_AGENT_ID` | Required ElevenLabs Agent used to voice saved Claude responses. |

**Master → Connections → Verify connections** checks Claude, Scribe and the configured ElevenLabs Agent. The product has no calls to OpenAI, ChatGPT or Codex.

### Agent voice and dashboard usage

With **Voice on**, each presented question opens a real ElevenLabs Agent conversation. **Listen** also uses the Agent. The backend overrides the conversation's first message with the exact saved Claude utterance, collects the returned audio and closes the conversation after that utterance. Claude controls the content; microphone audio goes to Scribe, and no user message is sent to the Agent's LLM. The Agent must allow first-message overrides. Missing configuration or Agent access errors are shown instead of substituting another voice provider.

The assistant message stores `voiceProvider`, `voiceConversationId` and `voiceAgentId` after successful generation. Expand **Voice / ElevenLabs Agent** beneath the response to see the conversation ID and compare it with the ElevenLabs conversation history. Each first spoken generation creates a short conversation; replay uses saved audio. Agent conversations are separate from Scribe usage, and voice-disabled text questions create no Agent conversation. Earlier standalone TTS recordings retain their original provenance.

Persistent data lives in `.runtime/data/traina.mv.db` and `.runtime/data/media`. Logs are in `.runtime/logs`. A pre-migration backup is saved in `.runtime/backups`. Preserve `.runtime/data` to retain accounts and evidence. Application data is stored on the server; the browser keeps only the HttpOnly session cookie and visual theme preference.

The API checks workspace and role on every Studio/team operation, including images, media ranges and SSE reconnection. Session cookies cannot bypass role checks through legacy integration endpoints. Passwords use salted PBKDF2. Invitations and sessions store token hashes.

## API overview

| Endpoint | Action |
| --- | --- |
| `/api/auth/register`, `/login`, `/me`, `/logout` | Account/session. |
| `/api/auth/invitation`, `/accept-invitation` | Preview/accept invitation. |
| `/api/t/{slug}/team/members`, `/invitations` | Master administration. |
| `/api/t/{slug}/studio/status?verify=true` | Claude, Scribe and Agent access verification; Agent ID and voice mode. |
| `.../studio/sessions` | Role-filtered list/create. |
| `.../sessions/{id}/frame` | Durable screenshot with optional `cameraImage`, HTTP 202. |
| `.../sessions/{id}/camera-frame` | Recent debrief camera image, independent `sequence` and media `elapsedSeconds`, HTTP 202. Recover the next sequence from `cameraContext.nextSequence`. |
| `.../sessions/{id}/stream` | SSE snapshots and reconnection state. |
| `.../sessions/{id}/heartbeat`, `/turn` | Active clock, pause/voice state, conversation. |
| `.../sessions/{id}/questions/{questionId}/delivered`, `/defer` | Actual question delivery and pending doubt. |
| `.../sessions/{id}/scribe-token` | Temporary microphone transcription token. |
| `.../sessions/{id}/recordings/open`, `/recordings`, `/recordings/{recordingId}/close` | Incremental audiovisual upload. |
| `.../sessions/{id}/recordings/{recordingId}/cancel` | Cancel an empty recording attempt; received evidence cannot be cancelled. |
| `.../sessions/{id}/recordings/recover` | Recover received fragments, mark incomplete. |
| `.../sessions/{id}/recordings/{mediaId}` | Authenticated playback with byte ranges. |
| `.../sessions/{id}/finish`, `/retry`, `/confirm`, `/review` | Finalization, debrief, publication and revision. |
| `.../sessions/{id}/events/{eventId}` (DELETE) | Withdraw evidence. |
| `.../sessions/{id}/practice`, `/practice/check`, `/practice/save` | Private learner practice. |
| `.../studio/speech` | Agent-generated audio for an accessible, saved Claude response; records the real Agent conversation ID on its message. Replays return cached audio. |

The frontend talks to Java through the `/api` HTTP proxy. H2 uses compatible entity schema updates; PostgreSQL uses Flyway V1–V3 with tenant RLS. `docker compose up --build` supplies PostgreSQL and FFmpeg in the API container. Docker/PostgreSQL require a separate environment check when Docker is available.

## Verification

```powershell
.\Main.ps1 -CheckOnly
.\Main.ps1 -NoBrowser
node scripts/verify.mjs
node scripts/verify.mjs --live
node scripts/verify-browser.mjs
```

`--live` consumes normal Claude and ElevenLabs usage. Verification creates isolated QA accounts and fictional evidence. Reports and screenshots are saved in `.runtime/qa`; provider keys are never printed. Browser verification uses the bundled Playwright runtime. Real screen selection and a physical microphone still require the person's browser permission and interaction.

The challenge PDF is treated as project reference data. `Backend/LogIn` retains preliminary files outside the compiled application; active authentication is in `com.apprentice.auth`.
