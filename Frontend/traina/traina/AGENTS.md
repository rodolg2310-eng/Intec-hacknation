<!-- LOVABLE:BEGIN -->
> [!IMPORTANT]
> This project is connected to [Lovable](https://lovable.dev). Avoid rewriting
> published git history — force pushing, or rebasing/amending/squashing commits
> that are already pushed — as it rewrites history on Lovable's side and the
> user will likely lose their project history.
>
> Commits you push to the connected branch sync back to Lovable and show up in
> the editor, so keep the branch in a working state.
<!-- LOVABLE:END -->

- Keep the frontend independent of backend implementation language: future Java services should be consumed through a documented HTTP/JSON API boundary, not imported into client modules, because the service is not connected yet.
- Store only the user's visual theme preference in browser storage; application/session data belongs in the future backend, because browser storage is not a secure or durable database.
- Company tenancy is derived from the business email domain in the frontend only for routing/display (/w/$company); data isolation must be enforced by the backend API, because client-side checks are not secure.
