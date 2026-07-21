#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
workspace=$(mktemp -d)
trap 'rm -rf "${workspace}"' EXIT

for build in first second; do
  checkout="${workspace}/${build}"
  mkdir -p "${checkout}"
  git -C "${repo_root}" archive HEAD | tar -x -C "${checkout}"
  (
    cd "${checkout}"
    bash ./gradlew -p samples/application clean build \
      --no-build-cache \
      --dependency-verification=strict \
      --stacktrace
  )
done

first_build="${workspace}/first/samples/application/build"
second_build="${workspace}/second/samples/application/build"
diff --recursive --brief "${first_build}/libs" "${second_build}/libs"
diff --recursive --brief "${first_build}/strguard" "${second_build}/strguard"

echo "Protected artifact hashes:"
find "${first_build}/libs" "${first_build}/strguard" -type f -print0 \
  | sort -z \
  | xargs -0 sha256sum
