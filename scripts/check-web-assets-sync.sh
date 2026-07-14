#!/usr/bin/env sh

set -eu

staged_files=$(git diff --cached --name-only --diff-filter=ACMR)

if [ -z "$staged_files" ]; then
    exit 0
fi

web_source_changed=false
web_assets_changed=false
web_non_lock_source_changed=false

while IFS= read -r file; do
    case "$file" in
        modules/web/pnpm-lock.yaml|\
        modules/web/package-lock.json)
            web_source_changed=true
            ;;
        modules/web/src/*|\
        modules/web/public/*|\
        modules/web/index.html|\
        modules/web/favicon.ico|\
        modules/web/package.json|\
        modules/web/vite.config.ts|\
        modules/web/tsconfig*.json|\
        modules/web/env.d.ts|\
        modules/web/.browserslistrc)
            web_source_changed=true
            web_non_lock_source_changed=true
            ;;
    esac

    case "$file" in
        app/src/main/assets/web/*)
            web_assets_changed=true
            ;;
    esac
done <<EOF
$staged_files
EOF

if [ "$web_source_changed" = true ] && [ "$web_assets_changed" = false ]; then
    if [ "$web_non_lock_source_changed" = false ] && \
        [ -f modules/web/dist/index.html ] && \
        cmp -s modules/web/dist/index.html app/src/main/assets/web/index.html; then
        exit 0
    fi

    cat >&2 <<EOF
Blocked commit: Web UI source changed without staged APK assets.

The Android app loads files from:
  app/src/main/assets/web/

When changing modules/web runtime source/config, build and sync the Web assets,
then stage the changed files under app/src/main/assets/web/.
For a lockfile-only change with unchanged output, build first so dist/index.html
can be compared with the APK asset.
EOF
    exit 1
fi

exit 0
