#!/usr/bin/env bash
# Build (if needed) and run the SimpleX desktop app from this fork on Linux.
#   ./launch-fork.sh           - build the Haskell core only if it isn't built yet, then run
#   ./launch-fork.sh --rebuild - always rebuild the Haskell core (after changing src/*.hs)
set -e

root_dir="$(cd "$(dirname "$0")" && pwd -P)"
cd "$root_dir"

ARCH="$(uname -m)"
lib="apps/multiplatform/common/src/commonMain/cpp/desktop/libs/linux-$ARCH/libsimplex.so"

if [ "$1" == "--rebuild" ] || [ ! -f "$lib" ]; then
  scripts/desktop/build-lib-linux.sh
fi

cd apps/multiplatform
./gradlew :desktop:run
