import { createFileRoute } from "@tanstack/react-router";
import { StudioApp } from "@/components/studio/studio-app";
export const Route = createFileRoute("/w/$company")({
  head: () => ({
    meta: [
      { title: "Your workspace — Traina" },
      {
        name: "description",
        content: "Capture expert judgment, confirm your Work Map and practice with Claude.",
      },
    ],
  }),
  component: Workspace,
});
function Workspace() {
  const { company } = Route.useParams();
  return <StudioApp company={company} />;
}
