// Company workspace helpers (frontend only).
// Real tenant isolation and data storage are enforced by the future Java backend API.

const FREE_PROVIDERS = new Set([
  "gmail.com",
  "googlemail.com",
  "outlook.com",
  "hotmail.com",
  "live.com",
  "yahoo.com",
  "icloud.com",
  "me.com",
  "aol.com",
  "proton.me",
  "protonmail.com",
  "gmx.com",
  "mail.com",
]);

export const WORKSPACE_ROOT_DOMAIN = "traina.app";

export type WorkspaceResult =
  { ok: true; domain: string; slug: string; workspaceUrl: string } | { ok: false; error: string };

export function resolveWorkspace(email: string): WorkspaceResult {
  const value = email.trim().toLowerCase();
  const match = /^[^\s@]+@([a-z0-9-]+(\.[a-z0-9-]+)+)$/.exec(value);
  if (!match) return { ok: false, error: "Enter a valid email address." };
  const domain = match[1] ?? "";
  if (FREE_PROVIDERS.has(domain)) {
    return { ok: false, error: "Please use your company email, not a personal one." };
  }
  const slug = domain.split(".")[0] ?? domain;
  return { ok: true, domain, slug, workspaceUrl: `${slug}.${WORKSPACE_ROOT_DOMAIN}` };
}
