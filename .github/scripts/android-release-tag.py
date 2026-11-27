"""Create the release tag or verify that an earlier attempt tagged the same commit."""

import argparse
import json
import os
import subprocess


def ensure_tag(repository, tag, commit):
    api = f"repos/{repository}/git"
    created = subprocess.run([
        "gh", "api", f"{api}/refs", "--method", "POST",
        "-f", f"ref=refs/tags/{tag}", "-f", f"sha={commit}", "--silent",
    ])
    if created.returncode == 0:
        return

    endpoint = f"{api}/ref/tags/{tag}"
    while True:
        response = subprocess.check_output(["gh", "api", endpoint])
        target = json.loads(response)["object"]
        if target["type"] != "tag":
            break
        endpoint = f"{api}/tags/{target['sha']}"

    if target["type"] != "commit" or target["sha"] != commit:
        raise SystemExit(f"Tag {tag} belongs to a different commit. Choose a new version.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("commit")
    args = parser.parse_args()
    ensure_tag(os.environ["GITHUB_REPOSITORY"], args.tag, args.commit)
