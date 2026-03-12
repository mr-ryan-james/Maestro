---
name: reconcile-upstream-into-fork
description: Reconcile changes from the original Maestro repository into the fork while treating the fork as canonical. Use when fetching original/upstream changes, comparing divergence, merging or cherry-picking upstream commits into the fork, or resolving sync conflicts. Strongly prefer preserving fork behavior and dropping incoming upstream hunks when conflicts are ambiguous; only replace fork code with upstream code when there is a clear, concrete gain such as a verified bug fix, security fix, compatibility fix, or materially better implementation.
---

# Reconcile Upstream Into Fork

Treat the fork as the product and the source of truth.

The original Maestro repo is an input feed, not the authority. Pull useful ideas from upstream into the fork. Do not reshape the fork to match upstream unless explicitly asked.

## Core stance

- Start from the fork branch, not the original repo branch.
- Preserve fork behavior by default.
- On ambiguous conflicts, keep fork code and throw away the incoming upstream delta.
- Take upstream code over fork code only when the gain is obvious and concrete.
- Prefer manually porting a small upstream fix into fork structure over taking an upstream file wholesale.
- Treat upstream docs, release churn, CI churn, formatting churn, and generic refactors as low priority unless they clearly help the fork.

## Remote model

Confirm remotes before acting:

```bash
git remote -v
git branch -vv
```

Expected Maestro setup today:

- `origin` = the fork
- `upstream` = the original Maestro repo

If remotes are named differently, identify:

- `FORK_REMOTE`
- `UPSTREAM_REMOTE`
- `FORK_BRANCH` usually `main`
- `UPSTREAM_BRANCH` usually `main`

Do not assume names. Verify them.

## Default workflow

1. Fetch both sides and snapshot the current fork tip.

```bash
git fetch --all --prune
git switch main
git pull --ff-only origin main
git branch "backup/pre-upstream-reconcile-$(date +%Y-%m-%d)"
```

2. Review divergence before touching history.

```bash
git log --left-right --cherry-pick --oneline origin/main...upstream/main
git rev-list --left-right --count origin/main...upstream/main
git range-diff origin/main...main upstream/main...upstream/main
```

Use these to separate:

- upstream commits worth taking
- upstream commits already superseded by fork work
- upstream commits that would degrade fork behavior

3. Create a temporary integration branch from the fork tip.

```bash
git switch -c "sync/upstream-$(date +%Y-%m-%d)" origin/main
```

4. Choose the intake strategy.

Prefer this order:

- manual port of specific upstream changes
- selective `git cherry-pick` of clearly valuable upstream commits
- `git merge --no-ff --no-commit upstream/main` only when the upstream delta is broad but still worth reviewing in one pass

Do not blindly merge just because upstream moved.

## Conflict policy

This is the most important rule in the skill.

When a conflict is not trivially resolvable, keep the fork's code intact unless upstream offers a very clear gain.

In practice:

- If the fork and upstream disagree and the better answer is not obvious in under a minute, keep fork code.
- If upstream would remove or weaken fork-only behavior, keep fork code.
- If upstream introduces a new abstraction that would force the fork to conform to upstream design, usually keep fork code and port only the useful logic.
- If upstream changes a file only cosmetically while the fork has functional customization, keep fork code.

During merge or cherry-pick conflict resolution:

```bash
git checkout --ours -- path/to/file
git add path/to/file
```

Use `--ours` as the default resolution for ambiguous files. In this workflow, `ours` is the fork integration branch and `theirs` is the incoming upstream change.

Only keep `theirs` or a large upstream hunk if you can state the win clearly, for example:

- fixes a real crash
- fixes a security issue
- restores compatibility with current Java, Gradle, Xcode, Android SDK, or MCP behavior
- adds test coverage that protects behavior the fork still wants
- fixes a bug in an area the fork has not materially customized

If you cannot defend the upstream change clearly, throw it away.

## Preferred resolution style

When upstream has something useful but the file also contains important fork work:

1. keep the fork version of the file
2. read the upstream hunk
3. manually transplant only the valuable logic
4. preserve fork naming, abstractions, flags, and behavior

Prefer "port the idea" over "take the file".

## Abort rules

Abort and change strategy when the current approach is producing more noise than signal.

Abort a merge if:

- conflict count is high and most conflicts are fork-owned areas
- upstream includes lots of release, docs, CI, or refactor noise
- the merge is obscuring which upstream deltas are actually valuable

Commands:

```bash
git merge --abort
git cherry-pick --abort
```

Then switch to selective cherry-picks or manual ports.

## Maestro-specific verification

After taking upstream changes into the fork, verify the areas most likely to break this repo's custom value.

Compile the touched core modules first:

```bash
./gradlew :maestro-cli:compileKotlin :maestro-client:compileKotlin :maestro-ios-driver:compileKotlin -x test
```

If CLI packaging or runtime behavior changed:

```bash
./gradlew :maestro-cli:installDist -x test
```

If the active launcher matters on the local machine, verify it points at the rebuilt fork install:

```bash
which maestro
maestro --version
maestro --help | rg 'driver-host-port'
maestro test --help | rg 'run-label|run-owner|force-stale-kill'
```

For this fork, pay extra attention to:

- `--driver-host-port`
- `--run-label`
- `--run-owner`
- `--force-stale-kill`
- built-in MCP tools under `maestro-cli/src/main/java/maestro/cli/mcp/`
- iOS runner shutdown and ownership-safe cleanup

If an upstream change threatens those fork features, the fork wins unless the upstream replacement is clearly superior.

## Finish and publish

After verification:

```bash
git status
git commit -m "Merge upstream/main into fork main"   # or a more specific message
git push origin HEAD:main
```

Push to the fork remote. Do not push to the original repo.

## Decision filter

Keep upstream when it is clearly one of these:

- real bug fix
- security fix
- compatibility fix
- valuable test improvement
- important performance improvement with low risk

Usually reject upstream when it is one of these:

- style churn
- rename churn
- refactor churn with no concrete fork benefit
- CI or release process changes the fork does not need
- docs or changelog updates that do not help fork users
- design changes that erase fork-specific behavior

## Final reminder

The fork is expected to outgrow the original repo.

Do not treat upstream parity as the goal.
Treat upstream as a source of ideas and fixes to intake selectively.
When there is serious doubt, keep the fork's code and discard the incoming upstream delta.
