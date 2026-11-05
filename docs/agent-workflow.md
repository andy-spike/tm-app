# Agent worktrees

## Starting work in T3 Code

Create a new thread in this project for each bounded task set. The project defaults to worktree mode and runs `pnpm worktree:setup` on creation. Start from local `main` (disable starting from origin for local-only work). Give the branch a task name, such as `agent/offline-search`. Wait for setup to finish successfully before starting tools. A new worktree contains committed files only.

For automation, create a T3 thread with `workspaceStrategy: { type: "worktree", baseRef: "main", branch: "agent/<task>", startFromOrigin: false }`. A delegated agent uses `delegate_task` with worktree isolation. Bind an existing checkout with `existing_worktree`; never point two active agents at the same checkout. A thread stays with its checkout for the whole task. Worktree creation and thread binding belong to T3 Code.

Use a brief like:

> Implement <specific scope>. Acceptance criteria: <observable outcomes>. Work only in your assigned checkout. Run pnpm check, commit task changes, then merge into main using the repository workflow. Report validation and the retained checkout path.

## Each checkout's world

`pnpm worktree:setup` installs from the frozen lockfile into a local node_modules with a private `.world/pnpm-store` and copied package files. It allocates three path-derived ports after checking availability and persists them in ignored `.world/state.json`. Re-running setup preserves ports. Copies of another checkout's state are rejected. Setup requires Node >=24, pnpm 12.4.1 and package registry access; the host supplies these tools.

| Command | Checkout-local service |
| --- | --- |
| `pnpm world:status` | Show checkout path and assigned ports |
| `pnpm mobile` | Metro with this world's backend URLs |
| `pnpm android` | Native build with this world's Metro port |
| `pnpm motis:setup` | Download and import this checkout's MOTIS binary and data |
| `pnpm motis` | Start MOTIS using generated port config |
| `pnpm bundle:build` | Build bundles into this checkout |
| `pnpm bundle:serve` | Serve bundles on this world's port |
| `pnpm world:exec pnpm bundle:compare` | Compare against this world's MOTIS |

Start services in separate T3 terminals bound to the thread's checkout. Setup installs dependencies only; MOTIS downloads/imports and bundle builds are explicit because they can be large. Each checkout must run those steps when its task needs real routing data. No checkout reads a central data directory or uses another checkout's backend. Rebuild local bundles after changing pipeline inputs or code.

Metro receives endpoint variables directly from the runner, taking precedence over Expo dotenv files. For a USB Android device, use `adb -s <serial> reverse tcp:<port> tcp:<port>` for the three ports printed by world:status. A physical phone or emulator is a separately assigned resource: agents must coordinate exclusive use. Installed apps with the same application ID overwrite one another; this setup does not isolate device app storage. For LAN devices, pass explicit host URLs through an adapted runner configuration before testing; localhost URLs are intended for forwarded device ports.

Worktrees isolate app files and mutable runtime state, not the host OS, Git object database, Git refs, SDKs, devices or credentials. Node/pnpm/system build tools remain host prerequisites. Ports are checked at allocation, but are not reserved while stopped: if another process takes one, stop only your own service and choose fresh ports by removing your own `.world/state.json` while all your services are stopped, then rerun setup. Do not copy or symlink node_modules, .world, generated data or secrets from main. Keep credentials scoped to the checkout when required.

## Android device assignment

Two dedicated Pixel 7 / Android 16 (API 36) emulators are provisioned on the local T3 device host: `tm-agent-1` and `tm-agent-2`. Each has independent writable device storage. The installed SDK system image is shared. Reproduce this pool with `pnpm android:emulators:setup` after installing `system-images;android-36;google_apis;x86_64`; existing devices and their data are preserved.

Assign one named emulator in each task brief that requires native testing, for example: `Device: local / tm-agent-1, exclusively assigned for this task.` Assign the second concurrent testing task `tm-agent-2`. Code-only tasks need no device. If both are assigned, wait for an assignment to be released. An agent reports its device name when finishing so the coordinator can release it; retained worktrees do not imply retained device ownership. The assignment is a coordination convention, not an automatic lock.

1. Call T3 `device_list` and then `device_open` with host `local` and the assigned device ID. This boots the emulator and displays it in the thread's Device panel. Use the returned device-bound `agent-device` command for app launches, taps, text, deep links, screenshots and recordings; use `device_screenshot` to inspect the screen. Follow the tool's recovery guidance on failure.
2. List serials with `adb devices -l` and match the assigned AVD using `adb -s <serial> emu avd name`. Build/install only to that explicit serial with `pnpm android --device <serial>`; never allow an automatic choice between concurrently running devices. Use the assigned device-bound command to open `com.anonymous.tmapp` for testing.
3. Read `pnpm world:status`. Forward each of its Metro, MOTIS and bundle ports with `adb -s <serial> reverse tcp:<port> tcp:<port>`. Run backend and Metro commands from the assigned worktree. Check that the app connects to this worktree's services before testing.
4. Keep the same emulator for the task. Preserve other agents' emulator sessions and storage. At handoff, report any app data or permissions the next task should reset; perform resets only on the device assigned to that task.

The pool is created once per machine, rather than once per worktree. T3 Code owns booting and device sessions. The setup command only creates definitions and never boots devices or overwrites an existing AVD. Additional parallel testing tasks need additional emulators and enough host resources.

## Finishing and merging

1. Review the diff, run `pnpm check`, and commit only the assigned task changes with descriptive messages.
2. Integrate one finished branch at a time. Run `wt merge main --no-squash --no-remove --no-commit` from the source checkout. The committed `.config/wt.toml` runs `pnpm check` after rebase, before integration. Allow the project hook when Worktrunk first requests trust; keep hooks enabled.
3. Resolve any rebase conflicts, continue the rebase, and repeat validation and merge until successful. Keep unrelated main-checkout changes out of the integration.
4. Verify `git merge-base --is-ancestor HEAD main` from the source checkout, and verify `git worktree list` still lists it. Report the target, source tip, validation results and retained path.

Worktrunk may rebase source commits onto newer main commits; it preserves individual commits rather than squashing them. The CLI flags retain the branch and checkout. T3/Herdr cleanup actions can remove checkouts independently, so use the CLI workflow and clean up manually only when requested. Serialize merges; independent implementation and validation can run concurrently. Settling a T3 thread does not merge code.
