#!/usr/bin/env bash
# Build (if needed) and run the SimpleX desktop app from this fork on Linux.
#   ./launch-fork.sh           - build the Haskell core only if it isn't built yet, then run
#   ./launch-fork.sh --rebuild - always rebuild the Haskell core (after changing src/*.hs)
#   ./launch-fork.sh --debug   - show app/core logs in this terminal (otherwise all output is dropped)
set -e

root_dir="$(cd "$(dirname "$0")" && pwd -P)"
cd "$root_dir"

rebuild=0
for arg in "$@"; do
  case "$arg" in
    --rebuild) rebuild=1 ;;
    --debug) export SIMPLEX_DEBUG_LOGS=1 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

ARCH="$(uname -m)"
lib="apps/multiplatform/common/src/commonMain/cpp/desktop/libs/linux-$ARCH/libsimplex.so"

if [ "$rebuild" == "1" ] || [ ! -f "$lib" ]; then
  scripts/desktop/build-lib-linux.sh
fi

cd apps/multiplatform
./gradlew :desktop:run
