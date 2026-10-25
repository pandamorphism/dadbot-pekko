#!/usr/bin/env bash
# Manage vendored reference repositories under repos/ (shallow git submodules).
#
#   scripts/vendor.sh init                    # after cloning: fetch all submodules at their pinned commits
#   scripts/vendor.sh add <name> <url> <tag>  # e.g. add pekko-management https://github.com/apache/pekko-management.git v1.2.1
#   scripts/vendor.sh pin <name> <tag>        # e.g. pin pekko v1.7.2
#
# The public repo stores only the submodule pointers; the sources are fetched
# with --depth=1 so no upstream history is downloaded. `add` and `pin` stage
# the change; commit it yourself.
set -euo pipefail

cmd=${1:?usage: vendor.sh init | add <name> <url> <tag> | pin <name> <tag>}

cd "$(git rev-parse --show-toplevel)"

# Keep vendored files byte-identical to upstream regardless of global core.autocrlf:
# -c covers the initial checkout (plus long paths on Windows), no_crlf persists it.
no_crlf() { git -C "repos/$1" config core.autocrlf false; git -C "repos/$1" config core.longpaths true; }

checkout_tag() { # <name> <tag>
  git -C "repos/$1" fetch --depth=1 --no-tags origin "refs/tags/$2:refs/tags/$2"
  git -C "repos/$1" -c advice.detachedHead=false checkout -q "$2"
  git add "repos/$1"
}

case "$cmd" in
  init)
    git -c core.autocrlf=false -c core.longpaths=true submodule update --init --depth=1
    git submodule foreach --quiet 'git config core.autocrlf false; git config core.longpaths true'
    ;;
  add)
    name=${2:?name required}; url=${3:?url required}; tag=${4:?tag required}
    git -c core.autocrlf=false -c core.longpaths=true submodule add --depth=1 "$url" "repos/$name"
    git config -f .gitmodules "submodule.repos/$name.shallow" true
    no_crlf "$name"
    checkout_tag "$name" "$tag"
    git add .gitmodules
    ;;
  pin)
    name=${2:?name required}; tag=${3:?tag required}
    checkout_tag "$name" "$tag"
    ;;
  *)
    echo "unknown command: $cmd" >&2; exit 1
    ;;
esac
