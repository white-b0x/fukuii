# Git conventions

Rules for git operations beyond what `worktree-protocol.md` already covers (worktree
lifecycle, `wt/<id>` naming, merge discipline for worktrees). Ported and adapted from
Nethermind's own `git.md` (`.claude/repo-references/clients/nethermind/.agents/rules/git.md`)
— a directly comparable production Ethereum client hitting the same agentic-git footguns.

## Rules

- **Force-push requires explicit confirmation.** Never `git push --force`/`--force-with-lease`
  without asking the user first, even on a branch you created this session — this is already
  covered by the global git-safety rules, restated here so it's discoverable alongside the
  rest of fukuii's git conventions rather than only in the harness-level instructions.
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
- **Pushing or deleting ANY branch on the canonical `upstream` remote — even a throwaway
  scratch branch created solely to trigger a `workflow_dispatch` verification run — requires
  flagging it to the operator BEFORE doing it, not reporting it after the fact.** Branch
  push/delete on a shared remote is visible to every collaborator (Actions tab, branch list,
  notifications) the same way a real feature branch is; "it was just for verification and I
  cleaned it up" does not make it invisible while it existed. **Incident (REPO-07,
  2026-07-05):** an implementing agent needed to verify a `_hive-sim.yml` change via
  `workflow_dispatch` and, without asking first, created+pushed a new branch to
  `chippr-robotics/fukuii`, ran the workflow, then deleted the branch — the operator noticed
  the deletion before the agent's own explanation arrived and (reasonably) worried it might
  have been a separate, deliberately-untouched orphaned branch instead. It wasn't, but the
  alarm was avoidable. If a verification run needs a real ref, prefer dispatching against an
  existing branch (`main`/`develop`/the current working branch) rather than inventing a new
  one; if a new branch is genuinely required, say so and get a go-ahead first, the same as any
  other visible-to-others action.
