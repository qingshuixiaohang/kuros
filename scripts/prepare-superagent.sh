#!/bin/sh
# New assignments only. Existing worktrees must resume without running this script.
set -eu
: "${DSH_SUPERAGENT_DIR:?Missing ticket directory}"
: "${DSH_SUPERAGENT_NAME:?Missing ticket name}"
repo=${DSH_SUPERAGENT_BASE_REPO:-/opt/dsh/work/kuros}
case "$DSH_SUPERAGENT_NAME" in ''|*[!a-zA-Z0-9_-]*) echo 'Invalid ticket name' >&2; exit 1;; esac
case "$DSH_SUPERAGENT_DIR" in /*) ;; *) echo 'Ticket directory must be absolute' >&2; exit 1;; esac

common=$(git -C "$repo" rev-parse --absolute-git-dir)
# Serialize fetch + ref resolution + creation so concurrent launches get a recorded base.
exec 9>"$common/dsh-prepare.lock"
flock -x 9
if [ -e "$DSH_SUPERAGENT_DIR/.git" ]; then
  echo 'Existing worktree: resume it; refusing to recreate or reset it' >&2
  exit 1
fi
# A failed fetch aborts; origin/main is never silently used as a stale fallback.
git -C "$repo" fetch --no-tags origin '+refs/heads/main:refs/remotes/origin/main'
base=$(git -C "$repo" rev-parse --verify 'refs/remotes/origin/main^{commit}')
git -C "$repo" worktree add -b "agent/$DSH_SUPERAGENT_NAME" "$DSH_SUPERAGENT_DIR" "$base"
metadata=$(git -C "$DSH_SUPERAGENT_DIR" rev-parse --absolute-git-dir)
printf '%s\n' "$base" > "$metadata/dsh-base-commit"
printf 'DSH_BASE_COMMIT=%s\n' "$base"
