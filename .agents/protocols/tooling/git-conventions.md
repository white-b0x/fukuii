# Git conventions

Rules for git operations beyond what `worktree-protocol.md` already covers (worktree
lifecycle, `wt/<id>` naming, merge discipline for worktrees). Ported and adapted from
Nethermind's own `git.md` (`.claude/repo-references/clients/nethermind/.agents/rules/git.md`)
— a directly comparable production Ethereum client hitting the same agentic-git footguns.

## Rules

- **Pushing a branch, deleting a branch, and force-pushing all require explicit confirmation
  first — on any remote, including your own fork, no exception for a throwaway scratch
  branch.** These are destructive/visible-to-others actions per the global git-safety rules;
  restated here so it's discoverable alongside the rest of fukuii's git conventions. Ask before
  doing it, not after.
- **Never silently drop code in a merge.** When resolving a merge conflict, both sides'
  changes must survive unless one is a genuine duplicate or superseded by the other. If it's
  unclear which side to keep — or whether a conflict hides a real semantic disagreement, not
  just a textual one — stop and consult the user interactively rather than guessing. This is
  the same "irreversible = 10× thought" principle from `AGENTS.md`'s working discipline
  applied specifically to merge conflicts.
- **Branch naming outside the worktree system.** `wt/<id>` is reserved for worktree-lifecycle
  branches (`worktree-protocol.md`) — don't reuse that prefix for a regular feature branch
  worked on directly (no worktree). For direct-branch work, use a conventional prefix:
  `feat/`, `fix/`, `perf/`, `refactor/`, or `test/`, followed by a short kebab-case
  description (e.g. `fix/snap-sync-pivot-race`).
- **Never `git add .`/`git add -A`.** Already stated in `AGENTS.md`'s Conventions section —
  restated here because it's a git-specific instance of the same discipline: know exactly
  what's in every commit.
