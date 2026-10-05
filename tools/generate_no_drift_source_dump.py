#!/usr/bin/env python3
import argparse
import hashlib
import os
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

EXCLUDED_DIR_NAMES = {
    ".git",
    "node_modules",
    ".expo",
    "dist",
    "build",
    "coverage",
    ".next",
    ".cache",
    ".turbo",
}

EXCLUDED_DIR_PREFIXES = {
    ".yarn/cache",
    "android/.gradle",
    "android/app/build",
    "ios/build",
}

# Embed full contents only for small, non-secret, non-binary text files.
MAX_EMBED_BYTES = 2_000_000  # 2 MB per file

# Secret-ish files should appear in manifest with hashes, but contents must be redacted.
SECRET_BASENAMES = {
    "google-services.json",
    "GoogleService-Info.plist",
}
SECRET_SUFFIXES = {
    ".jks",
    ".keystore",
    ".p8",
    ".pem",
    ".key",
    ".mobileprovision",
    ".p12",
}

TEXT_NAME_ALLOWLIST = {
    "package.json",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "bun.lockb",
    "eas.json",
    "app.json",
    "app.config.js",
    "app.config.ts",
    "babel.config.js",
    "metro.config.js",
    "tsconfig.json",
    "gradle.properties",
    "Podfile",
    "Gemfile",
    "Dockerfile",
    ".gitignore",
    ".npmrc",
    ".yarnrc",
    ".yarnrc.yml",
}

TEXT_EXT_ALLOWLIST = {
    ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
    ".json", ".md", ".yml", ".yaml",
    ".xml", ".plist", ".pbxproj",
    ".gradle", ".properties",
    ".kt", ".java", ".swift", ".m", ".mm",
    ".c", ".h", ".cpp", ".hpp",
    ".sh", ".rb", ".py", ".sql", ".txt",
    ".env.example", ".env.sample",
}

def now_utc():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def run_git(root: Path, *args: str) -> str:
    try:
        out = subprocess.check_output(
            ["git", *args],
            cwd=str(root),
            stderr=subprocess.STDOUT,
            text=True,
        )
        return out.strip()
    except Exception as e:
        return f"UNAVAILABLE: {e}"

def rel_posix(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()

def path_matches_prefixes(rel_path: str, prefixes) -> bool:
    for p in prefixes:
        if rel_path == p or rel_path.startswith(p + "/"):
            return True
    return False

def should_exclude_dir(rel_dir: str):
    name = PurePosixPath(rel_dir).name
    if name in EXCLUDED_DIR_NAMES:
        return f"dir-name:{name}"
    for prefix in EXCLUDED_DIR_PREFIXES:
        if rel_dir == prefix or rel_dir.startswith(prefix + "/"):
            return f"dir-prefix:{prefix}"
    return None

def is_secret_path(rel_path: str) -> bool:
    base = PurePosixPath(rel_path).name
    lower = base.lower()
    if base in SECRET_BASENAMES:
        return True
    if any(lower.endswith(sfx.lower()) for sfx in SECRET_SUFFIXES):
        return True
    if lower == ".env":
        return True
    if lower.startswith(".env.") and "example" not in lower and "sample" not in lower:
        return True
    return False

def sniff_binary_bytes(data: bytes) -> bool:
    if not data:
        return False
    if b"\x00" in data:
        return True
    sample = data[:8192]
    textish = 0
    for b in sample:
        if b in (9, 10, 13, 27):
            textish += 1
        elif 32 <= b <= 126:
            textish += 1
        elif b >= 128:
            textish += 1  # allow utf-8 bytes without marking binary
    ratio = textish / max(len(sample), 1)
    return ratio < 0.70

def is_text_candidate(path: Path) -> bool:
    name = path.name
    if name in TEXT_NAME_ALLOWLIST:
        return True
    suffixes = path.suffixes
    if suffixes:
        joined = "".join(suffixes[-2:])  # catches .env.example
        if joined in TEXT_EXT_ALLOWLIST:
            return True
        if path.suffix in TEXT_EXT_ALLOWLIST:
            return True
    return False

def load_small_bytes(path: Path, size_limit: int = 8192) -> bytes:
    with path.open("rb") as f:
        return f.read(size_limit)

def file_kind_and_policy(path: Path, rel_path: str, size: int):
    if is_secret_path(rel_path):
        return ("secret", "manifest-only-redacted")
    sample = load_small_bytes(path, 8192)
    binary = sniff_binary_bytes(sample)
    if binary:
        return ("binary", "manifest-only-binary")
    if size > MAX_EMBED_BYTES:
        return ("text", "manifest-only-large-text")
    if is_text_candidate(path) or True:
        return ("text", "embedded-text")
    return ("text", "manifest-only-text")

def normalize_zip_member_names(names):
    clean = []
    for n in names:
        n = n.replace("\\", "/").strip("/")
        if not n:
            continue
        clean.append(n)
    if not clean:
        return []
    parts_list = [PurePosixPath(n).parts for n in clean]
    first_segments = {parts[0] for parts in parts_list if parts}
    strip_first = len(first_segments) == 1 and all(len(parts) > 1 for parts in parts_list)
    if not strip_first:
        return clean
    return ["/".join(PurePosixPath(n).parts[1:]) for n in clean]

def normalize_zip_path_for_compare(rel_path: str):
    rel_path = rel_path.strip("/")
    if not rel_path:
        return None
    parent = str(PurePosixPath(rel_path).parent).replace("\\", "/")
    if parent == ".":
        parent = ""
    # exclude by directory path
    if parent:
        if should_exclude_dir(parent):
            return None
        for prefix in EXCLUDED_DIR_PREFIXES:
            if rel_path == prefix or rel_path.startswith(prefix + "/"):
                return None
    name = PurePosixPath(rel_path).name
    if name in EXCLUDED_DIR_NAMES:
        return None
    for prefix in EXCLUDED_DIR_PREFIXES:
        if rel_path == prefix or rel_path.startswith(prefix + "/"):
            return None
    return rel_path

def tree_fingerprint(rows):
    h = hashlib.sha256()
    for row in rows:
        line = f"{row['path']}\t{row['sha256']}\t{row['size_bytes']}\n"
        h.update(line.encode("utf-8"))
    return h.hexdigest()

def collect_tree(root: Path):
    manifest = []
    exclusions = []
    excluded_dir_hits = 0

    for current_root, dirnames, filenames in os.walk(root, topdown=True):
        current_root_path = Path(current_root)
        rel_dir = "" if current_root_path == root else rel_posix(current_root_path, root)

        keep_dirs = []
        for d in sorted(dirnames):
            child_rel = d if not rel_dir else f"{rel_dir}/{d}"
            reason = should_exclude_dir(child_rel)
            if reason:
                exclusions.append({
                    "path": child_rel,
                    "reason": reason,
                    "kind": "directory-pruned",
                })
                excluded_dir_hits += 1
            else:
                keep_dirs.append(d)
        dirnames[:] = keep_dirs

        for fname in sorted(filenames):
            path = current_root_path / fname
            rel_path = rel_posix(path, root)
            try:
                size = path.stat().st_size
                sha = sha256_file(path)
                kind, policy = file_kind_and_policy(path, rel_path, size)
                manifest.append({
                    "path": rel_path,
                    "sha256": sha,
                    "size_bytes": size,
                    "kind": kind,
                    "content_policy": policy,
                })
            except Exception as e:
                manifest.append({
                    "path": rel_path,
                    "sha256": f"ERROR:{e}",
                    "size_bytes": -1,
                    "kind": "error",
                    "content_policy": "manifest-only-error",
                })

    manifest.sort(key=lambda x: x["path"])
    exclusions.sort(key=lambda x: x["path"])
    return manifest, exclusions, excluded_dir_hits

def compare_with_zip(zip_path: Path):
    rows = []
    with zipfile.ZipFile(zip_path, "r") as zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        names = normalize_zip_member_names(names)
        for raw_name in names:
            rel = normalize_zip_path_for_compare(raw_name)
            if not rel:
                continue
            info = zf.getinfo(raw_name if raw_name in zf.namelist() else next(
                (n for n in zf.namelist() if n.replace("\\", "/").strip("/").endswith(raw_name)), raw_name
            ))
            data = zf.read(info.filename)
            rows.append({
                "path": rel,
                "sha256": sha256_bytes(data),
                "size_bytes": len(data),
            })
    rows.sort(key=lambda x: x["path"])
    return rows

def write_manifest(path: Path, rows):
    with path.open("w", encoding="utf-8") as f:
        f.write("path\tsha256\tsize_bytes\tkind\tcontent_policy\n")
        for row in rows:
            f.write(
                f"{row['path']}\t{row['sha256']}\t{row['size_bytes']}\t"
                f"{row.get('kind','')}\t{row.get('content_policy','')}\n"
            )

def write_exclusions(path: Path, rows):
    with path.open("w", encoding="utf-8") as f:
        f.write("path\treason\tkind\n")
        for row in rows:
            f.write(f"{row['path']}\t{row['reason']}\t{row['kind']}\n")

def safe_read_text(path: Path) -> str:
    with path.open("rb") as f:
        data = f.read()
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("utf-8", errors="replace")

def write_dump(
    dump_path: Path,
    root: Path,
    manifest,
    exclusions,
    git_commit: str,
    git_status: str,
    snapshot_status: str,
    root_fingerprint: str,
    zip_path_value: str,
    zip_fingerprint: str,
):
    embedded_count = 0
    redacted_count = 0

    with dump_path.open("w", encoding="utf-8") as f:
        f.write("NO_DRIFT_SOURCE_DUMP_VERSION: 1\n")
        f.write(f"GENERATED_AT_UTC: {now_utc()}\n")
        f.write(f"ROOT: {root.resolve().as_posix()}\n")
        f.write(f"SNAPSHOT_STATUS: {snapshot_status}\n")
        f.write(f"ZIP_PATH: {zip_path_value}\n")
        f.write(f"ROOT_TREE_FINGERPRINT: {root_fingerprint}\n")
        f.write(f"ZIP_TREE_FINGERPRINT: {zip_fingerprint}\n")
        f.write(f"GIT_COMMIT: {git_commit}\n")
        f.write("GIT_STATUS_BEGIN\n")
        f.write((git_status or "") + "\n")
        f.write("GIT_STATUS_END\n")
        f.write("EXCLUDED_DIRECTORY_RULES_BEGIN\n")
        for name in sorted(EXCLUDED_DIR_NAMES):
            f.write(f"DIR_NAME_EXCLUDE: {name}\n")
        for prefix in sorted(EXCLUDED_DIR_PREFIXES):
            f.write(f"DIR_PREFIX_EXCLUDE: {prefix}\n")
        f.write("EXCLUDED_DIRECTORY_RULES_END\n")
        f.write(f"INCLUDED_FILE_COUNT: {len(manifest)}\n")
        f.write(f"EXCLUSION_RECORD_COUNT: {len(exclusions)}\n")
        f.write("FILE_MANIFEST_BEGIN\n")
        f.write("path\tsha256\tsize_bytes\tkind\tcontent_policy\n")
        for row in manifest:
            f.write(
                f"{row['path']}\t{row['sha256']}\t{row['size_bytes']}\t"
                f"{row['kind']}\t{row['content_policy']}\n"
            )
        f.write("FILE_MANIFEST_END\n")

        for row in manifest:
            f.write("\n")
            f.write(f"FILE: {row['path']}\n")
            f.write(f"SHA256: {row['sha256']}\n")
            f.write(f"SIZE_BYTES: {row['size_bytes']}\n")
            f.write(f"KIND: {row['kind']}\n")
            f.write(f"CONTENT_POLICY: {row['content_policy']}\n")
            f.write("---\n")

            file_path = root / row["path"]

            if row["content_policy"] == "embedded-text":
                embedded_count += 1
                try:
                    f.write(safe_read_text(file_path))
                    if not safe_read_text(file_path).endswith("\n"):
                        f.write("\n")
                except Exception as e:
                    f.write(f"[READ_ERROR] {e}\n")
            elif row["content_policy"] == "manifest-only-redacted":
                redacted_count += 1
                f.write("[REDACTED_SECRET_CONTENT]\n")
            else:
                f.write("[CONTENT_NOT_EMBEDDED]\n")

    return embedded_count, redacted_count

def write_compare(compare_path: Path, root_rows, zip_rows):
    root_map = {r["path"]: r for r in root_rows}
    zip_map = {r["path"]: r for r in zip_rows}

    only_in_root = sorted(set(root_map) - set(zip_map))
    only_in_zip = sorted(set(zip_map) - set(root_map))
    hash_mismatch = sorted(
        p for p in set(root_map) & set(zip_map)
        if root_map[p]["sha256"] != zip_map[p]["sha256"]
    )
    size_mismatch = sorted(
        p for p in set(root_map) & set(zip_map)
        if root_map[p]["size_bytes"] != zip_map[p]["size_bytes"]
    )

    status = "ZIP-VERIFIED"
    if only_in_root or only_in_zip or hash_mismatch:
        status = "DIVERGED"

    root_fp = tree_fingerprint(root_rows)
    zip_fp = tree_fingerprint(zip_rows)

    with compare_path.open("w", encoding="utf-8") as f:
        f.write(f"STATUS: {status}\n")
        f.write(f"GENERATED_AT_UTC: {now_utc()}\n")
        f.write(f"ROOT_TREE_FINGERPRINT: {root_fp}\n")
        f.write(f"ZIP_TREE_FINGERPRINT: {zip_fp}\n")
        f.write(f"ROOT_FILE_COUNT: {len(root_rows)}\n")
        f.write(f"ZIP_FILE_COUNT: {len(zip_rows)}\n")
        f.write(f"ONLY_IN_ROOT_COUNT: {len(only_in_root)}\n")
        f.write(f"ONLY_IN_ZIP_COUNT: {len(only_in_zip)}\n")
        f.write(f"HASH_MISMATCH_COUNT: {len(hash_mismatch)}\n")
        f.write(f"SIZE_MISMATCH_COUNT: {len(size_mismatch)}\n")

        def write_list(title, items):
            f.write(f"{title}_BEGIN\n")
            for item in items:
                f.write(f"{item}\n")
            f.write(f"{title}_END\n")

        write_list("ONLY_IN_ROOT", only_in_root)
        write_list("ONLY_IN_ZIP", only_in_zip)
        write_list("HASH_MISMATCH", hash_mismatch)
        write_list("SIZE_MISMATCH", size_mismatch)

    return status, root_fp, zip_fp, len(only_in_root), len(only_in_zip), len(hash_mismatch), len(size_mismatch)

def write_proof(
    proof_path: Path,
    root: Path,
    zip_path: str,
    snapshot_status: str,
    included_files: int,
    embedded_files: int,
    redacted_files: int,
    excluded_dir_hits: int,
    root_fingerprint: str,
    git_commit: str,
    git_status: str,
    compare_summary=None,
):
    with proof_path.open("w", encoding="utf-8") as f:
        f.write("NO_DRIFT_PROOF_VERSION: 1\n")
        f.write(f"GENERATED_AT_UTC: {now_utc()}\n")
        f.write(f"ROOT: {root.resolve().as_posix()}\n")
        f.write(f"ZIP_PATH: {zip_path}\n")
        f.write(f"SNAPSHOT_STATUS: {snapshot_status}\n")
        f.write(f"ROOT_TREE_FINGERPRINT: {root_fingerprint}\n")
        f.write(f"GIT_COMMIT: {git_commit}\n")
        f.write("GIT_STATUS_BEGIN\n")
        f.write((git_status or "") + "\n")
        f.write("GIT_STATUS_END\n")
        f.write(f"INCLUDED_FILES: {included_files}\n")
        f.write(f"EMBEDDED_FILES: {embedded_files}\n")
        f.write(f"REDACTED_FILES: {redacted_files}\n")
        f.write(f"EXCLUDED_DIR_HITS: {excluded_dir_hits}\n")
        if compare_summary:
            f.write(f"COMPARE_STATUS: {compare_summary['status']}\n")
            f.write(f"ZIP_TREE_FINGERPRINT: {compare_summary['zip_fingerprint']}\n")
            f.write(f"ONLY_IN_ROOT_COUNT: {compare_summary['only_in_root_count']}\n")
            f.write(f"ONLY_IN_ZIP_COUNT: {compare_summary['only_in_zip_count']}\n")
            f.write(f"HASH_MISMATCH_COUNT: {compare_summary['hash_mismatch_count']}\n")
            f.write(f"SIZE_MISMATCH_COUNT: {compare_summary['size_mismatch_count']}\n")
        else:
            f.write("COMPARE_STATUS: NOT-ZIP-VERIFIED\n")

def main():
    parser = argparse.ArgumentParser(description="Generate a no-drift source dump with manifest, hashes, and optional zip verification.")
    parser.add_argument("--root", default=".", help="Project root to snapshot")
    parser.add_argument("--zip", default="", help="Optional path to the exact source zip to compare against")
    parser.add_argument("--outdir", default="no_drift_dump", help="Output directory")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    outdir = Path(args.outdir).resolve()
    outdir.mkdir(parents=True, exist_ok=True)

    manifest_path = outdir / "NO_DRIFT_SOURCE_MANIFEST.tsv"
    exclusions_path = outdir / "NO_DRIFT_EXCLUSIONS.tsv"
    dump_path = outdir / "NO_DRIFT_SOURCE_DUMP.txt"
    proof_path = outdir / "NO_DRIFT_PROOF.txt"
    compare_path = outdir / "NO_DRIFT_COMPARE.txt"

    git_commit = run_git(root, "rev-parse", "HEAD")
    git_status = run_git(root, "status", "--short", "--untracked-files=all")

    manifest, exclusions, excluded_dir_hits = collect_tree(root)
    write_manifest(manifest_path, manifest)
    write_exclusions(exclusions_path, exclusions)

    snapshot_status = "NOT-ZIP-VERIFIED"
    zip_fingerprint = ""
    compare_summary = None
    root_fingerprint = tree_fingerprint(manifest)

    if args.zip:
        zip_path = Path(args.zip).resolve()
        if not zip_path.exists():
            print(f"ERROR: zip path does not exist: {zip_path}", file=sys.stderr)
            sys.exit(2)
        zip_rows = compare_with_zip(zip_path)
        status, root_fp2, zip_fp, only_in_root_count, only_in_zip_count, hash_mismatch_count, size_mismatch_count = write_compare(compare_path, manifest, zip_rows)
        snapshot_status = status
        root_fingerprint = root_fp2
        zip_fingerprint = zip_fp
        compare_summary = {
            "status": status,
            "zip_fingerprint": zip_fp,
            "only_in_root_count": only_in_root_count,
            "only_in_zip_count": only_in_zip_count,
            "hash_mismatch_count": hash_mismatch_count,
            "size_mismatch_count": size_mismatch_count,
        }

    embedded_files, redacted_files = write_dump(
        dump_path=dump_path,
        root=root,
        manifest=manifest,
        exclusions=exclusions,
        git_commit=git_commit,
        git_status=git_status,
        snapshot_status=snapshot_status,
        root_fingerprint=root_fingerprint,
        zip_path_value=str(Path(args.zip).resolve()) if args.zip else "",
        zip_fingerprint=zip_fingerprint,
    )

    write_proof(
        proof_path=proof_path,
        root=root,
        zip_path=str(Path(args.zip).resolve()) if args.zip else "",
        snapshot_status=snapshot_status,
        included_files=len(manifest),
        embedded_files=embedded_files,
        redacted_files=redacted_files,
        excluded_dir_hits=excluded_dir_hits,
        root_fingerprint=root_fingerprint,
        git_commit=git_commit,
        git_status=git_status,
        compare_summary=compare_summary,
    )

    print(f"WROTE: {dump_path}")
    print(f"WROTE: {manifest_path}")
    print(f"WROTE: {exclusions_path}")
    print(f"WROTE: {proof_path}")
    if args.zip:
        print(f"WROTE: {compare_path}")
    print(f"SNAPSHOT_STATUS: {snapshot_status}")
    print(f"INCLUDED_FILES: {len(manifest)}")
    print(f"EMBEDDED_FILES: {embedded_files}")
    print(f"REDACTED_FILES: {redacted_files}")
    print(f"EXCLUDED_DIR_HITS: {excluded_dir_hits}")
    print(f"ROOT_TREE_FINGERPRINT: {root_fingerprint}")
    if args.zip:
        print(f"ZIP_TREE_FINGERPRINT: {zip_fingerprint}")

if __name__ == "__main__":
    main()
