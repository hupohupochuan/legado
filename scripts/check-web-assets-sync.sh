#!/usr/bin/env sh

set -eu

staged_files=$(git diff --cached --name-only --diff-filter=ACMRD)

if [ -z "$staged_files" ]; then
    exit 0
fi

web_source_changed=false
web_output_input_changed=false

while IFS= read -r file; do
    case "$file" in
        modules/web/pnpm-lock.yaml|\
        modules/web/package-lock.json|\
        modules/web/package.json|\
        modules/web/tsconfig*.json|\
        modules/web/env.d.ts)
            web_source_changed=true
            web_output_input_changed=true
            ;;
        modules/web/src/*|\
        modules/web/public/*|\
        modules/web/index.html|\
        modules/web/favicon.ico|\
        modules/web/vite.config.ts|\
        modules/web/.browserslistrc)
            web_source_changed=true
            web_output_input_changed=true
            ;;
    esac
done <<EOF
$staged_files
EOF

dist_matches_staged_assets() {
    [ -f modules/web/dist/index.html ] &&
        [ -f modules/web/dist/favicon.ico ] &&
        git show :app/src/main/assets/web/index.html |
            cmp -s modules/web/dist/index.html - &&
        git show :app/src/main/assets/web/favicon.ico |
            cmp -s modules/web/dist/favicon.ico -
}

dist_is_current_for_staged_inputs() {
    while IFS= read -r file; do
        case "$file" in
            modules/web/pnpm-lock.yaml|\
            modules/web/package-lock.json|\
            modules/web/package.json|\
            modules/web/tsconfig*.json|\
            modules/web/env.d.ts|\
            modules/web/src/*|\
            modules/web/public/*|\
            modules/web/index.html|\
            modules/web/favicon.ico|\
            modules/web/vite.config.ts|\
            modules/web/.browserslistrc)
                freshness_path=$file
                if [ ! -e "$freshness_path" ]; then
                    freshness_path=${file%/*}
                fi
                if [ "$freshness_path" -nt modules/web/dist/index.html ] ||
                    [ "$freshness_path" -nt modules/web/dist/favicon.ico ]; then
                    return 1
                fi
                ;;
        esac
    done <<EOF
$staged_files
EOF
    return 0
}

if [ "$web_source_changed" = true ]; then
    if [ "$web_output_input_changed" = true ] &&
        dist_matches_staged_assets &&
        dist_is_current_for_staged_inputs; then
        exit 0
    fi

    cat >&2 <<EOF
Blocked commit: Web UI inputs changed without a current, staged APK asset build.

The Android app loads files from:
  app/src/main/assets/web/

When changing modules/web runtime source/config/dependencies, build and sync the
Web assets, then stage changed files under app/src/main/assets/web/.
For dependency, lockfile, type, source, or config changes with byte-identical
output, rebuild first and keep both dist/index.html and dist/favicon.ico newer
than the staged inputs. The dist files are compared with the staged asset blobs,
not only the working-tree copies.
EOF
    exit 1
fi

exit 0
