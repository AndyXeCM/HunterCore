#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-"$ROOT_DIR/build/huntercore/bundled-plugins"}"
PLUGINS_DIR="$OUT_DIR/plugins"
WORK_DIR="$ROOT_DIR/build/huntercore/bundled-work"
MANIFEST="$OUT_DIR/bundled-plugins.external.yml"

mkdir -p "$PLUGINS_DIR" "$WORK_DIR"

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

sha512_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 512 "$1" | awk '{print $1}'
  else
    sha512sum "$1" | awk '{print $1}'
  fi
}

curl_to_file() {
  local url="$1"
  local target="$2"
  local attempts="${3:-6}"
  local attempt status

  for attempt in $(seq 1 "$attempts"); do
    set +e
    if [[ -s "$target" ]]; then
      curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 --speed-limit 1024 --speed-time 30 -C - -o "$target" "$url"
    else
      curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 --speed-limit 1024 --speed-time 30 -o "$target" "$url"
    fi
    status="$?"
    set -e

    if [[ "$status" == "0" ]]; then
      return 0
    fi

    if [[ "$status" == "33" ]]; then
      rm -f "$target"
    fi
    sleep "$attempt"
  done

  return "$status"
}

download_file() {
  local url="$1"
  local target="$2"
  local expected_sha="${3:-}"

  if [[ -f "$target" && -n "$expected_sha" && "$(sha256_file "$target")" == "$expected_sha" ]]; then
    return
  fi

  local tmp="$target.tmp"
  curl_to_file "$url" "$tmp"
  if [[ -n "$expected_sha" ]]; then
    local actual_sha
    actual_sha="$(sha256_file "$tmp")"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
      rm -f "$tmp"
      echo "SHA-256 mismatch for $url: expected $expected_sha got $actual_sha" >&2
      exit 1
    fi
  fi
  mv "$tmp" "$target"
}

download_file_sha512() {
  local url="$1"
  local target="$2"
  local expected_sha="$3"

  if [[ -f "$target" && "$(sha512_file "$target")" == "$expected_sha" ]]; then
    return
  fi

  local tmp="$target.tmp"
  curl_to_file "$url" "$tmp"
  local actual_sha
  actual_sha="$(sha512_file "$tmp")"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    rm -f "$tmp"
    echo "SHA-512 mismatch for $url: expected $expected_sha got $actual_sha" >&2
    exit 1
  fi
  mv "$tmp" "$target"
}

download_github_release_asset() {
  local repo="$1"
  local tag="$2"
  local pattern="$3"
  local target="$4"
  local expected_sha="$5"

  if [[ -f "$target" && "$(sha256_file "$target")" == "$expected_sha" ]]; then
    return
  fi

  if command -v gh >/dev/null 2>&1; then
    local tmp_dir="$WORK_DIR/gh-release-${repo//\//-}-$tag"
    rm -rf "$tmp_dir"
    mkdir -p "$tmp_dir"
    gh release download "$tag" --repo "$repo" --pattern "$pattern" --dir "$tmp_dir" --clobber
    local downloaded
    downloaded="$(find "$tmp_dir" -maxdepth 1 -type f -name "$pattern" -print -quit)"
    if [[ -z "$downloaded" ]]; then
      echo "GitHub release asset $repo $tag $pattern was not downloaded." >&2
      exit 1
    fi
    local actual_sha
    actual_sha="$(sha256_file "$downloaded")"
    if [[ "$actual_sha" != "$expected_sha" ]]; then
      echo "SHA-256 mismatch for $repo $tag $pattern: expected $expected_sha got $actual_sha" >&2
      exit 1
    fi
    cp "$downloaded" "$target"
  else
    download_file "https://github.com/$repo/releases/download/$tag/$pattern" "$target" "$expected_sha"
  fi
}

manifest_header() {
  cat > "$MANIFEST" <<'YAML'
plugins:
YAML
}

manifest_entry() {
  local id="$1"
  local name="$2"
  local version="$3"
  local file_name="$4"
  local source="$5"
  local sha
  sha="$(sha256_file "$PLUGINS_DIR/$file_name")"
  cat >> "$MANIFEST" <<YAML
  - id: $id
    name: $name
    version: "$version"
    file: $file_name
    source: "$source"
    resource: META-INF/huntercore/bundled-plugins/$file_name
    sha256: $sha
YAML
}

prepare_luckperms() {
  local artifact_id zip_file extracted jar_path file_name version
  artifact_id="$(gh api 'repos/LuckPerms/LuckPerms/actions/artifacts?per_page=100' \
    --jq '[.artifacts[] | select(.name == "jars" and .expired == false and .workflow_run.head_branch == "master") | .id][0] // ""')"
  if [[ -z "$artifact_id" ]]; then
    echo "Unable to find a non-expired LuckPerms jars artifact on GitHub Actions." >&2
    exit 1
  fi

  zip_file="$WORK_DIR/luckperms-jars-$artifact_id.zip"
  extracted="$WORK_DIR/luckperms-$artifact_id"
  if [[ ! -f "$zip_file" ]]; then
    gh api -H 'Accept: application/vnd.github+json' "/repos/LuckPerms/LuckPerms/actions/artifacts/$artifact_id/zip" > "$zip_file"
  fi
  rm -rf "$extracted"
  mkdir -p "$extracted"
  unzip -q "$zip_file" -d "$extracted"
  jar_path="$(find "$extracted" -path '*/bukkit/loader/build/libs/LuckPerms-Bukkit-*.jar' -type f -print -quit)"
  if [[ -z "$jar_path" ]]; then
    echo "LuckPerms Bukkit jar was not found in artifact $artifact_id." >&2
    exit 1
  fi
  file_name="$(basename "$jar_path")"
  version="${file_name#LuckPerms-Bukkit-}"
  version="${version%.jar}"
  cp "$jar_path" "$PLUGINS_DIR/$file_name"
  manifest_entry "luckperms" "LuckPerms" "$version" "$file_name" "https://github.com/LuckPerms/LuckPerms/actions/artifacts/$artifact_id"
}

prepare_coreprotect() {
  local tag="v23.2"
  local version="23.2"
  local source_dir="$WORK_DIR/CoreProtect-$tag"
  local mvn_cmd

  if [[ ! -d "$source_dir/.git" ]]; then
    rm -rf "$source_dir"
    git clone --depth 1 --branch "$tag" https://github.com/PlayPro/CoreProtect.git "$source_dir"
  fi

  perl -0pi -e 's/public static final String LATEST_VERSION = "26\.1";/public static final String LATEST_VERSION = "26.1.2";/' \
    "$source_dir/src/main/java/net/coreprotect/config/ConfigHandler.java"

  if command -v mvn >/dev/null 2>&1; then
    mvn_cmd="mvn"
  else
    local maven_version="3.9.11"
    local maven_home="$WORK_DIR/apache-maven-$maven_version"
    if [[ ! -x "$maven_home/bin/mvn" ]]; then
      local archive="$WORK_DIR/apache-maven-$maven_version-bin.tar.gz"
      download_file "https://archive.apache.org/dist/maven/maven-3/$maven_version/binaries/apache-maven-$maven_version-bin.tar.gz" "$archive"
      tar -xzf "$archive" -C "$WORK_DIR"
    fi
    mvn_cmd="$maven_home/bin/mvn"
  fi

  (cd "$source_dir" && "$mvn_cmd" -q -DskipTests -Dproject.branch=development package)

  local jar_path="$source_dir/target/CoreProtect-$version.jar"
  if [[ ! -f "$jar_path" ]]; then
    jar_path="$(find "$source_dir/target" -maxdepth 1 -type f -name 'CoreProtect-*.jar' ! -name '*sources*' ! -name 'original-*' -print -quit)"
  fi
  if [[ -z "$jar_path" || ! -f "$jar_path" ]]; then
    echo "CoreProtect jar was not produced by Maven." >&2
    exit 1
  fi

  local file_name="CoreProtect-$version.jar"
  cp "$jar_path" "$PLUGINS_DIR/$file_name"
  manifest_entry "coreprotect" "CoreProtect" "$version" "$file_name" "https://github.com/PlayPro/CoreProtect/tree/$tag"
}

manifest_header

download_github_release_asset \
  "ViaVersion/ViaVersion" \
  "5.9.1" \
  "ViaVersion-5.9.1.jar" \
  "$PLUGINS_DIR/ViaVersion-5.9.1.jar" \
  "12aa83e60af09e83fdbd5f551940df4b5dcb9c481cb92f79bd82861a4c33b5df"
manifest_entry "viaversion" "ViaVersion" "5.9.1" "ViaVersion-5.9.1.jar" "https://github.com/ViaVersion/ViaVersion/releases/tag/5.9.1"

download_github_release_asset \
  "ViaVersion/ViaBackwards" \
  "5.9.1" \
  "ViaBackwards-5.9.1.jar" \
  "$PLUGINS_DIR/ViaBackwards-5.9.1.jar" \
  "676bda03d8d7e252f8b70ffd0e288252e317b015190351c4b9ab8ab8de388d68"
manifest_entry "viabackwards" "ViaBackwards" "5.9.1" "ViaBackwards-5.9.1.jar" "https://github.com/ViaVersion/ViaBackwards/releases/tag/5.9.1"

download_github_release_asset \
  "ViaVersion/ViaRewind" \
  "4.1.1" \
  "ViaRewind-4.1.1.jar" \
  "$PLUGINS_DIR/ViaRewind-4.1.1.jar" \
  "a16bb39d4de147abac4871bf34963378792082c9008a1375766545a2509e0b6b"
manifest_entry "viarewind" "ViaRewind" "4.1.1" "ViaRewind-4.1.1.jar" "https://github.com/ViaVersion/ViaRewind/releases/tag/4.1.1"

download_github_release_asset \
  "BlueMap-Minecraft/BlueMap" \
  "v5.20" \
  "bluemap-5.20-paper.jar" \
  "$PLUGINS_DIR/bluemap-5.20-paper.jar" \
  "2f656ba47320daff60d270b0601e9dd3de23ccbff41dd27f065972f973c1bf83"
manifest_entry "bluemap" "BlueMap" "5.20" "bluemap-5.20-paper.jar" "https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.20"

download_file_sha512 \
  "https://cdn.modrinth.com/data/fALzjamp/versions/MdY6JATr/Chunky-Bukkit-1.5.3.jar" \
  "$PLUGINS_DIR/Chunky-Bukkit-1.5.3.jar" \
  "43ffecc6e6a734b752da41575bbb316526c124c3f878942437d5133c377bfbd9b78bda975520dc074d7158c15dade58a444ccd0fd8d8a25d165b6fc450140422"
manifest_entry "chunky" "Chunky" "1.5.3" "Chunky-Bukkit-1.5.3.jar" "https://modrinth.com/plugin/chunky/version/1.5.3"

download_file_sha512 \
  "https://cdn.modrinth.com/data/lKEzGugV/versions/UmbIiI5H/PlaceholderAPI-2.12.2.jar" \
  "$PLUGINS_DIR/PlaceholderAPI-2.12.2.jar" \
  "94addf996ba45e16dbded3fcaf05e8b442212ce0d577f7edc42b743ad9532c1e24115263976126d36f27c0868ab1c03c40c2d13947985124b92dabca4527dddb"
manifest_entry "placeholderapi" "PlaceholderAPI" "2.12.2" "PlaceholderAPI-2.12.2.jar" "https://modrinth.com/plugin/placeholderapi/version/2.12.2"

download_github_release_asset \
  "MilkBowl/Vault" \
  "1.7.3" \
  "Vault.jar" \
  "$PLUGINS_DIR/Vault-1.7.3.jar" \
  "a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d"
manifest_entry "vault" "Vault" "1.7.3" "Vault-1.7.3.jar" "https://github.com/MilkBowl/Vault/releases/tag/1.7.3"

download_file \
  "https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar" \
  "$PLUGINS_DIR/ProtocolLib-5.4.0.jar" \
  "ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf"
manifest_entry "protocollib" "ProtocolLib" "5.4.0" "ProtocolLib-5.4.0.jar" "https://github.com/dmulloy2/ProtocolLib/releases/tag/5.4.0"

download_file_sha512 \
  "https://cdn.modrinth.com/data/1u6JkXh5/versions/yDUBafTJ/worldedit-bukkit-7.4.3.jar" \
  "$PLUGINS_DIR/worldedit-bukkit-7.4.3.jar" \
  "43d2f8865a06d63c71d2b8bc0ded66c2c7f5e413db1a64bc2f665db3bf25ec28ae40789884af4bdaf40740a6632196494d2897fa77675f0babfaf79f18400853"
manifest_entry "worldedit" "WorldEdit" "7.4.3" "worldedit-bukkit-7.4.3.jar" "https://modrinth.com/plugin/worldedit/version/7.4.3"

download_file_sha512 \
  "https://cdn.modrinth.com/data/DKY9btbd/versions/pI4UHLJL/worldguard-bukkit-7.0.17.jar" \
  "$PLUGINS_DIR/worldguard-bukkit-7.0.17.jar" \
  "5da539cf8618079f9e7f5a0ab578c62d31ebd88e35b39ca047a2f9115e397e0d4dd781a94ca373e93333fce3371f7eaf503cd0b6abb6363085e3f4968ea51e4b"
manifest_entry "worldguard" "WorldGuard" "7.0.17" "worldguard-bukkit-7.0.17.jar" "https://modrinth.com/plugin/worldguard/version/7.0.17"

download_github_release_asset \
  "Multiverse/Multiverse-Core" \
  "5.7.0" \
  "multiverse-core-5.7.0.jar" \
  "$PLUGINS_DIR/multiverse-core-5.7.0.jar" \
  "1efa6a66a790b928b376d9addf8b90cb9ec6dd11e35558db6acd472f0c7c9ece"
manifest_entry "multiverse-core" "Multiverse-Core" "5.7.0" "multiverse-core-5.7.0.jar" "https://github.com/Multiverse/Multiverse-Core/releases/tag/5.7.0"

prepare_luckperms
prepare_coreprotect

echo "Prepared bundled plugin jars in $PLUGINS_DIR"
