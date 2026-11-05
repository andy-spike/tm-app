# Working in this repository

Use one T3 Code thread, one branch, and one worktree for each bounded task set. Keep all edits and runtime state in that checkout. Before starting a task or merging, read [the worktree workflow](docs/agent-workflow.md). Read nested AGENTS.md files for the directories you edit.

Run `pnpm worktree:setup` in a new checkout and wait for success before starting services. Use the root package scripts for mobile, Android, MOTIS and bundle serving; they load checkout-local ports. Use `pnpm world:exec` for commands that need those endpoint variables. Keep downloaded inputs, generated bundles, dependencies and caches local to the checkout.

Validate with `pnpm check` before completing a task. Commit only task changes using your own descriptive messages. When the assigned task includes integration, merge into local `main` with `wt merge main --no-squash --no-remove --no-commit` from the source worktree, retaining validation hooks. Resolve conflicts and hook failures, then verify `main` contains the resulting source tip and the source checkout remains. Preserve commits, the branch and checkout for manual cleanup. Report checks, target branch and retained path.

Explain the resulting behavior and validation clearly. Before browser automation, read `/home/andy-spike/.agents/browser-policy.md`; use agent-browser by default and follow that policy's T3 fallback rules.
