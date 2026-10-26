#!/usr/bin/env python3
"""Copy capture metadata from a still photo to an image stack or video.

Usage:
    python3 scripts/copy_photo_metadata.py SOURCE_PHOTO TARGET
"""

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


IMAGE_SUFFIXES = {".jpg", ".jpeg", ".tif", ".tiff"}
VIDEO_SUFFIXES = {".mov", ".mp4", ".m4v"}
PHOTO_SUFFIXES = IMAGE_SUFFIXES | {".png", ".heic", ".heif"}

VIDEO_TAGS = (
    ("Make", "UserData:Make"),
    ("Model", "UserData:Model"),
    ("ExposureTime", "XMP-exif:ExposureTime"),
    ("FNumber", "XMP-exif:FNumber"),
    ("ISO", "XMP-exifEX:PhotographicSensitivity"),
    ("FocalLength", "XMP-exif:FocalLength"),
    ("LensModel", "XMP-exifEX:LensModel"),
)


class MetadataError(RuntimeError):
    pass


def exiftool_path() -> str:
    executable = shutil.which("exiftool")
    if executable is None:
        raise MetadataError("ExifTool was not found on PATH")
    return executable


def run_exiftool(executable: str, *arguments: str) -> str:
    result = subprocess.run(
        [executable, *arguments],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0 or "Warning:" in result.stderr:
        detail = result.stderr.strip() or result.stdout.strip()
        raise MetadataError(detail or f"ExifTool exited with status {result.returncode}")
    return result.stdout.strip()


def read_tag(executable: str, path: Path, tag: str) -> str:
    return run_exiftool(executable, "-s3", f"-{tag}", str(path))


def validate_paths(source: Path, target: Path) -> str:
    if not source.is_file():
        raise MetadataError(f"Source photo does not exist: {source}")
    if not target.is_file():
        raise MetadataError(f"Target file does not exist: {target}")
    if source.resolve() == target.resolve():
        raise MetadataError("Source and target must be different files")
    if source.suffix.lower() not in PHOTO_SUFFIXES:
        raise MetadataError(f"Unsupported source photo type: {source.suffix}")

    suffix = target.suffix.lower()
    if suffix in IMAGE_SUFFIXES:
        return "image"
    if suffix in VIDEO_SUFFIXES:
        return "video"
    raise MetadataError(f"Unsupported target type: {target.suffix}")


def copy_image_metadata(executable: str, source: Path, target: Path) -> None:
    run_exiftool(
        executable,
        "-TagsFromFile",
        str(source),
        "-EXIF:all",
        "-IPTC:all",
        "-XMP:all",
        "--EXIF:Software",
        "--XMP-xmp:CreatorTool",
        "-overwrite_original",
        str(target),
    )


def copy_video_metadata(executable: str, source: Path, target: Path) -> None:
    arguments = ["-TagsFromFile", str(source)]
    source_date_tag = (
        "XMP-xmp:CreateDate"
        if read_tag(executable, source, "XMP-xmp:CreateDate")
        else "DateTimeOriginal"
    )
    arguments.extend(
        (
            f"-UserData:DateTimeOriginal<{source_date_tag}",
            f"-XMP-exif:DateTimeOriginal<{source_date_tag}",
        )
    )
    for source_tag, target_tag in VIDEO_TAGS:
        if read_tag(executable, source, source_tag):
            arguments.append(f"-{target_tag}<{source_tag}")
    arguments.extend(["-overwrite_original", str(target)])
    run_exiftool(executable, *arguments)


def verify_copy(
    executable: str,
    source: Path,
    target: Path,
    target_kind: str,
    before: dict[str, str],
) -> None:
    shared_tags = ("Make", "Model", "DateTimeOriginal")
    if target_kind == "image":
        for tag in shared_tags:
            expected = read_tag(executable, source, tag)
            if expected and read_tag(executable, target, tag) != expected:
                raise MetadataError(f"Metadata verification failed for {tag}")
        for tag in ("ImageWidth", "ImageHeight", "Software"):
            if read_tag(executable, target, tag) != before[tag]:
                raise MetadataError(f"Target {tag} unexpectedly changed")
    else:
        source_date_tag = (
            "XMP-xmp:CreateDate"
            if read_tag(executable, source, "XMP-xmp:CreateDate")
            else "DateTimeOriginal"
        )
        video_tags = (
            *VIDEO_TAGS,
            (source_date_tag, "UserData:DateTimeOriginal"),
            (source_date_tag, "XMP-exif:DateTimeOriginal"),
        )
        for source_tag, target_tag in video_tags:
            expected = read_tag(executable, source, source_tag)
            if expected and read_tag(executable, target, target_tag) != expected:
                raise MetadataError(f"Metadata verification failed for {target_tag}")
        for tag in ("QuickTime:CreateDate", "Duration"):
            if read_tag(executable, target, tag) != before[tag]:
                raise MetadataError(f"Video {tag} unexpectedly changed")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Copy capture metadata from a still photo to an image stack or video."
    )
    parser.add_argument("source_photo", type=Path, help="Photo to copy metadata from")
    parser.add_argument("target", type=Path, help="Existing image stack or video to update")
    arguments = parser.parse_args()

    try:
        target_kind = validate_paths(arguments.source_photo, arguments.target)
        executable = exiftool_path()
        preserved_tags = (
            ("ImageWidth", "ImageHeight", "Software")
            if target_kind == "image"
            else ("QuickTime:CreateDate", "Duration")
        )
        before = {
            tag: read_tag(executable, arguments.target, tag)
            for tag in preserved_tags
        }

        with tempfile.TemporaryDirectory(prefix="photo-metadata-backup-") as directory:
            backup = Path(directory) / arguments.target.name
            shutil.copy2(arguments.target, backup)
            try:
                if target_kind == "image":
                    copy_image_metadata(executable, arguments.source_photo, arguments.target)
                else:
                    copy_video_metadata(executable, arguments.source_photo, arguments.target)
                verify_copy(
                    executable,
                    arguments.source_photo,
                    arguments.target,
                    target_kind,
                    before,
                )
            except Exception:
                shutil.copy2(backup, arguments.target)
                raise

        print(f"Copied metadata from {arguments.source_photo} to {arguments.target}")
        return 0
    except (MetadataError, OSError, subprocess.SubprocessError) as error:
        print(f"copy_photo_metadata: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
