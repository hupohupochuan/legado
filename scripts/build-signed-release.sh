#!/usr/bin/env sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SIGNING_PROPERTIES="$ROOT_DIR/app/gradle.properties"
OUTPUT_DIR="$ROOT_DIR/app/build/outputs/apk/app/release"
TARGET_DIR="$ROOT_DIR/app/app/release"
EXPECTED_APKS="legado_all.apk legado_arm64.apk legado_armv7.apk legado_x64.apk legado_x86.apk"
V4_VERIFIER="$ROOT_DIR/scripts/VerifyApkV4.java"

fail() {
    printf 'Release APK 生成失败: %s\n' "$1" >&2
    exit 1
}

read_property() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$SIGNING_PROPERTIES" |
        tail -n 1
}

resolve_build_tool() {
    tool_name=$1
    candidate=$(
        find "$ROOT_DIR/.sdk/build-tools" \
            -mindepth 2 -maxdepth 2 -type f -name "$tool_name" -perm -111 \
            2>/dev/null | sort -V | tail -n 1
    )
    if [ -n "$candidate" ]; then
        printf '%s\n' "$candidate"
    else
        command -v "$tool_name" 2>/dev/null || return 1
    fi
}

ensure_source_unchanged() {
    [ "$(git rev-parse --verify HEAD)" = "$initial_head" ] ||
        fail "构建或校验期间 HEAD 已变化，请在最终提交上重新运行。"
    [ -z "$(git status --porcelain --untracked-files=all)" ] ||
        fail "构建或校验期间工作树发生变化，请确认后重新运行。"
}

cleanup() {
    if [ -n "${STAGING_DIR:-}" ] && [ -d "$STAGING_DIR" ]; then
        rm -rf "$STAGING_DIR"
    fi
    if [ "${TARGET_SWAPPED:-false}" = true ] && [ -e "$TARGET_DIR" ]; then
        rm -rf "$TARGET_DIR"
    fi
    if [ -n "${BACKUP_DIR:-}" ] && [ -d "$BACKUP_DIR" ]; then
        mv "$BACKUP_DIR" "$TARGET_DIR"
    fi
}

cd "$ROOT_DIR"

[ "$#" -eq 0 ] || fail "不接受额外 Gradle 参数，避免 --dry-run/-x 复用旧产物。"
if [ -n "$(git status --porcelain --untracked-files=all)" ]; then
    fail "工作树不干净。请先提交本轮修改，确保 APK 的 versionCode 对应最终 HEAD。"
fi
initial_head=$(git rev-parse --verify HEAD)
initial_commit_count=$(git rev-list HEAD --count)

[ -f "$SIGNING_PROPERTIES" ] ||
    fail "缺少 app/gradle.properties，不能使用个人 Release 签名。"
signing_properties_mode=$(stat -c '%a' "$SIGNING_PROPERTIES" 2>/dev/null || true)
case "$signing_properties_mode" in
    400 | 600) ;;
    *) fail "app/gradle.properties 权限应为 600，避免泄露签名口令。" ;;
esac

for property_name in \
    RELEASE_STORE_FILE \
    RELEASE_STORE_PASSWORD \
    RELEASE_KEY_ALIAS \
    RELEASE_KEY_PASSWORD \
    RELEASE_CERT_SHA256; do
    [ -n "$(read_property "$property_name")" ] ||
        fail "app/gradle.properties 缺少 $property_name。"
done

store_file=$(read_property RELEASE_STORE_FILE)
case "$store_file" in
    /*) keystore_path=$store_file ;;
    *) keystore_path="$ROOT_DIR/app/$store_file" ;;
esac
[ -f "$keystore_path" ] || fail "Release keystore 不存在: $keystore_path"
keystore_mode=$(stat -c '%a' "$keystore_path" 2>/dev/null || true)
case "$keystore_mode" in
    400 | 600) ;;
    *) fail "Release keystore 权限应为 600，避免其他本机用户读取。" ;;
esac

expected_cert=$(read_property RELEASE_CERT_SHA256 | tr -d '[:space:]:' | tr 'A-F' 'a-f')
case "$expected_cert" in
    '' | *[!0-9a-f]*) fail "RELEASE_CERT_SHA256 格式错误。" ;;
esac
[ "${#expected_cert}" -eq 64 ] || fail "RELEASE_CERT_SHA256 必须是 64 位 SHA-256。"

APKSIGNER=$(resolve_build_tool apksigner) || fail "未找到 Android build-tools/apksigner。"
AAPT=$(resolve_build_tool aapt) || fail "未找到 Android build-tools/aapt。"
APKSIG_JAR="$(dirname -- "$APKSIGNER")/lib/apksigner.jar"
[ -f "$APKSIG_JAR" ] || fail "未找到 apksig 库: $APKSIG_JAR"
[ -f "$V4_VERIFIER" ] || fail "未找到 v4 签名校验器: $V4_VERIFIER"
JAVA=$(command -v java) || fail "未找到 Java 运行环境。"

rm -rf "$OUTPUT_DIR"
./gradlew :app:assembleRelease --no-daemon

ensure_source_unchanged

[ -d "$OUTPUT_DIR" ] || fail "未生成 Release 输出目录: $OUTPUT_DIR"
[ -f "$OUTPUT_DIR/output-metadata.json" ] || fail "缺少 output-metadata.json。"

actual_apk_count=$(
    find "$OUTPUT_DIR" -maxdepth 1 -type f -name 'legado_*.apk' | wc -l | tr -d '[:space:]'
)
[ "$actual_apk_count" -eq 5 ] || fail "预期生成 5 个 APK，实际为 $actual_apk_count 个。"

release_version_code=""
release_version_name=""
for apk_name in $EXPECTED_APKS; do
    apk_path="$OUTPUT_DIR/$apk_name"
    [ -f "$apk_path" ] || fail "缺少 $apk_name。"
    [ -f "$apk_path.idsig" ] || fail "缺少 $apk_name.idsig，不能整批同步 v4 签名旁车文件。"

    "$APKSIGNER" verify --Werr "$apk_path" >/dev/null
    "$JAVA" --class-path "$APKSIG_JAR" "$V4_VERIFIER" "$apk_path" "$apk_path.idsig"
    actual_cert=$(
        "$APKSIGNER" verify --print-certs "$apk_path" |
            sed -n 's/.*certificate SHA-256 digest: //p' |
            head -n 1 |
            tr -d '[:space:]:' |
            tr 'A-F' 'a-f'
    )
    [ "$actual_cert" = "$expected_cert" ] ||
        fail "$apk_name 的签名证书不是配置的个人 Release 证书。"

    badging=$("$AAPT" dump badging "$apk_path")
    package_line=$(printf '%s\n' "$badging" | sed -n '1p')
    case "$package_line" in
        *"name='shutiao.reader.release'"*) ;;
        *) fail "$apk_name 的 applicationId 不是 shutiao.reader.release。" ;;
    esac
    case "$badging" in
        *application-debuggable*) fail "$apk_name 仍带 debuggable 标记。" ;;
    esac

    apk_version_code=$(
        printf '%s\n' "$package_line" |
            sed -n "s/.*versionCode='\([^']*\)'.*/\1/p"
    )
    apk_version_name=$(
        printf '%s\n' "$package_line" |
            sed -n "s/.*versionName='\([^']*\)'.*/\1/p"
    )
    [ -n "$apk_version_code" ] || fail "无法读取 $apk_name 的 versionCode。"
    [ -n "$apk_version_name" ] || fail "无法读取 $apk_name 的 versionName。"

    if [ -z "$release_version_code" ]; then
        release_version_code=$apk_version_code
        release_version_name=$apk_version_name
    else
        [ "$apk_version_code" = "$release_version_code" ] ||
            fail "五个 APK 的 versionCode 不一致。"
        [ "$apk_version_name" = "$release_version_name" ] ||
            fail "五个 APK 的 versionName 不一致。"
    fi

    grep -Fq "\"outputFile\": \"$apk_name\"" "$OUTPUT_DIR/output-metadata.json" ||
        fail "output-metadata.json 未登记 $apk_name。"
done

# versionCode 当前由 app/build.gradle 中的 10000 + Git 提交数生成。
expected_version_code=$((10000 + initial_commit_count))
[ "$release_version_code" -eq "$expected_version_code" ] ||
    fail "APK versionCode=$release_version_code，与当前 HEAD 预期的 $expected_version_code 不一致。"
grep -Fq "\"versionCode\": $release_version_code" "$OUTPUT_DIR/output-metadata.json" ||
    fail "output-metadata.json 的 versionCode 与 APK 不一致。"
grep -Fq "\"versionName\": \"$release_version_name\"" "$OUTPUT_DIR/output-metadata.json" ||
    fail "output-metadata.json 的 versionName 与 APK 不一致。"

mkdir -p "$(dirname -- "$TARGET_DIR")"
STAGING_DIR=$(mktemp -d "$(dirname -- "$TARGET_DIR")/.release.new.XXXXXX")
BACKUP_DIR=""
TARGET_SWAPPED=false
trap cleanup 0 1 2 15

cp -R "$OUTPUT_DIR/." "$STAGING_DIR/"
(
    cd "$STAGING_DIR"
    sha256sum $EXPECTED_APKS >SHA256SUMS
)
ensure_source_unchanged

if [ -e "$TARGET_DIR" ]; then
    backup_candidate="${TARGET_DIR}.old.$$"
    [ ! -e "$backup_candidate" ] || fail "临时备份目录已存在: $backup_candidate"
    BACKUP_DIR=$backup_candidate
    mv "$TARGET_DIR" "$BACKUP_DIR"
fi

TARGET_SWAPPED=true
if ! mv "$STAGING_DIR" "$TARGET_DIR"; then
    fail "无法把本次完整产物切换到 $TARGET_DIR。"
fi
STAGING_DIR=""

for apk_name in $EXPECTED_APKS; do
    cmp -s "$OUTPUT_DIR/$apk_name" "$TARGET_DIR/$apk_name" ||
        fail "$apk_name 同步后字节不一致。"
    cmp -s "$OUTPUT_DIR/$apk_name.idsig" "$TARGET_DIR/$apk_name.idsig" ||
        fail "$apk_name.idsig 同步后字节不一致。"
    "$APKSIGNER" verify --Werr "$TARGET_DIR/$apk_name" >/dev/null
done
cmp -s "$OUTPUT_DIR/output-metadata.json" "$TARGET_DIR/output-metadata.json" ||
    fail "output-metadata.json 同步后字节不一致。"
ensure_source_unchanged

# 从这里起，新目录已经完整验证；先停用回滚，再清理旧备份。
# 若清理期间被中断，最多残留备份目录，不会删除已验证的新目录。
trap - 0 1 2 15
TARGET_SWAPPED=false
if [ -n "$BACKUP_DIR" ]; then
    rm -rf "$BACKUP_DIR"
    BACKUP_DIR=""
fi

arm64_sha256=$(sha256sum "$TARGET_DIR/legado_arm64.apk" | awk '{print $1}')
printf '\n签名 Release APK 已生成并验证。\n'
printf '版本: versionCode=%s, versionName=%s\n' "$release_version_code" "$release_version_name"
printf '证书 SHA-256: %s\n' "$expected_cert"
printf 'arm64 APK: %s\n' "$TARGET_DIR/legado_arm64.apk"
printf 'arm64 SHA-256: %s\n' "$arm64_sha256"
