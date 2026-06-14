#!/usr/bin/env sh

set -eu

staged_files=$(git diff --cached --name-only --diff-filter=ACMR)

if [ -z "$staged_files" ]; then
    exit 0
fi

blocked_files=""

while IFS= read -r file; do
    case "$file" in
        .sdk|.sdk/*|\
        .gradle|.gradle/*|\
        .idea|.idea/*|\
        .kotlin|.kotlin/*|\
        .vscode|.vscode/*|\
        .proxyai|.proxyai/*|\
        .omo|.omo/*|\
        AGENTS.md|\
        opencode.json|\
        local.properties|\
        app/gradle.properties|\
        app/signing|app/signing/*|\
        app/app|app/app/*|\
        release签名|release签名/*|\
        *.jks|*.keystore|*.p12|*.pem)
            blocked_files="${blocked_files}
${file}"
            ;;
    esac
done <<EOF
$staged_files
EOF

if [ -n "$blocked_files" ]; then
    cat >&2 <<EOF
Blocked commit: local environment, signing, or AI-only files are staged.

Remove them from the index and keep the local files:
  git restore --staged <path>

Blocked paths:${blocked_files}
EOF
    exit 1
fi

exit 0
