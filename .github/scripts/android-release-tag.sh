#!/usr/bin/env bash
set -euo pipefail

tag=$1
commit=$2
api="repos/$GITHUB_REPOSITORY/git"
if gh api "$api/refs" --method POST -f "ref=refs/tags/$tag" -f "sha=$commit" --silent; then
  exit 0
fi

# A retry can reuse a tag only when it still points at the tested commit.
object=$(gh api "$api/ref/tags/$tag" --jq '.object | .type + " " + .sha')
while [[ "$object" == tag\ * ]]; do
  object=$(gh api "$api/tags/${object#tag }" --jq '.object | .type + " " + .sha')
done
if [[ "$object" != "commit $commit" ]]; then
  echo "Tag $tag belongs to a different commit. Choose a new version." >&2
  exit 1
fi
