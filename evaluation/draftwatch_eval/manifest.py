"""Writes generated files with a SHA-256 manifest, so a rebuild that differs is caught."""

import hashlib
import json
import os


class ManifestMismatch(Exception):
    """A rebuilt file's hash differs from the one recorded in the manifest."""


def _write_atomically(path, data):
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def write_files(out_dir, files, manifest_name):
    """Writes ``files`` ({name: bytes}) into ``out_dir`` and records their hashes in the manifest.

    Files already in the manifest must hash the same; otherwise nothing is written and
    ManifestMismatch names the file and both hashes. New names are added to the manifest.
    Returns the hashes of ``files``.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / manifest_name
    recorded = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    for name, digest in hashes.items():
        if name in recorded and recorded[name] != digest:
            raise ManifestMismatch(
                "%s: rebuilt file has SHA-256 %s, the manifest records %s"
                % (name, digest, recorded[name]))
    for name, data in files.items():
        _write_atomically(out_dir / name, data)
    recorded.update(hashes)
    _write_atomically(manifest_path,
                      (json.dumps(recorded, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    return hashes
