# Online deployment (free-tier VM)

The app uses browser screen, camera and microphone permissions, so the public site must use HTTPS. It also saves recordings and screenshots on disk. Do not deploy it to an ephemeral free web service: its media files disappear after restarts or idle shutdowns.

## Recommended setup

Use one Linux VM with persistent disk, Docker Compose, and a DNS name pointing to the VM. The current Compose stack runs the Node frontend, Java 21 API, PostgreSQL, FFmpeg processing, and Caddy HTTPS proxy. Postgres and media use persistent Docker volumes. The frontend proxies `/api` internally, so the browser only needs the public HTTPS site; provider keys stay on the API server.

Oracle Cloud Infrastructure currently documents Always Free Ampere compute for up to 1,500 OCPU-hours and 9,000 GB-hours monthly (2 OCPUs and 12 GB RAM for an Always Free tenancy) and 200 GB total block-volume storage. Create resources only in the tenancy's home region and stay within the Always Free limits. Instance capacity can be unavailable in some regions. Signup usually requires a phone number and payment card for identity verification; the card is not charged unless the account is upgraded. See [Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) and [Free Tier details](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm).

## Before launch

1. Push the repository to GitHub, then create an Ubuntu ARM VM in the Oracle home region using no more than 2 OCPUs and 12 GB RAM. Keep total boot and block storage within 200 GB.
2. Open TCP ports **80** and **443** in both the VM firewall and Oracle's network security list. Assign a public IP.
3. Point a DNS A record you control to that IP. Set the exact hostname as `DOMAIN` below. Caddy automatically requests and renews its HTTPS certificate; DNS must resolve publicly and ports 80/443 must reach the VM.
4. Install Docker Engine and the Docker Compose plugin on the VM; clone this public repository there.
5. Copy `.env.online.example` to `.env.online`. Set `DOMAIN`, a unique strong `POSTGRES_PASSWORD`, a separate random `ADMIN_API_KEY`, and the Claude and ElevenLabs keys/Agent ID. Keep this file only on the VM; never commit it.
6. Start the app from the repository root:

   ```bash
   docker compose -p traina-online --env-file .env.online -f compose.online.yaml up -d --build
   ```

7. Check startup and provider connectivity:

   ```bash
   docker compose -p traina-online --env-file .env.online -f compose.online.yaml ps
   docker compose -p traina-online --env-file .env.online -f compose.online.yaml logs --tail=200 api web proxy
   curl --fail "https://$(sed -n 's/^DOMAIN=//p' .env.online)/api/health"
   ```

   Then open `https://<DOMAIN>`, register the first workspace Master, and use **Master → Connections → Verify connections** to check Claude, Scribe and the ElevenLabs Agent.

## Updates and backups

For an update, pull the intended GitHub commit and rerun the same `docker compose -p traina-online --env-file .env.online -f compose.online.yaml up -d --build` command. Do not run `down -v`: that deletes database, media and certificate volumes. Before updates, back up PostgreSQL and `/app/data` (the `apprentice-db` and `apprentice-media` Docker volumes) to a separate location. A VM is online independently of either user's PC; it is not an SLA or an automatic offsite backup.

## Current deployment gate

The deployment files are published in the GitHub repository. OCI instance provisioning is being completed in the Oracle console; this repository cannot confirm its state or connect to it. A domain/DNS name, SSH access to the VM, and provider credentials are still needed for the final HTTPS launch. Enter the intended server credentials in the private `.env.online` on the VM. The local `.env` remains ignored by Git.

This online deployment guide uses Docker Compose, so install Docker Engine and the Compose plugin on the VM before running its commands. Docker is not required for the local Windows `Main.cmd` workflow; its absence on the development PC does not prevent local use. The Compose configuration and ARM image build still need their first validation on the target VM. Local backend/frontend checks do not prove the remote services or provider credentials work.
