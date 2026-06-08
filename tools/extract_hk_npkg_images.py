#!/usr/bin/env python3
import argparse
import json
import os
import struct
import zlib
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def read_prnm_sections(data):
    if data[:4] != b"PRNM":
        raise ValueError("not a PRNM package")
    count = struct.unpack_from("<I", data, 8)[0]
    sections = []
    table_off = 0x20
    for index in range(count):
        off = table_off + index * 12
        checksum, section_offset, section_size = struct.unpack_from("<III", data, off)
        magic = data[section_offset:section_offset + 8].decode("ascii", errors="replace")
        sections.append(
            {
                "index": index,
                "checksum": checksum,
                "offset": section_offset,
                "size": section_size,
                "magic": magic,
            }
        )
    return sections


def find_zlib_offset(blob):
    for off in range(0, min(len(blob), 80)):
        if blob[off:off + 2] in (b"\x78\x9c", b"\x78\xda", b"\x78\x01"):
            return off
    return -1


def decode_image_blob(blob):
    if len(blob) < 16:
        return None
    width, height = struct.unpack_from("<HH", blob, 0)
    if width <= 0 or height <= 0 or width > 4096 or height > 4096:
        return None
    zoff = find_zlib_offset(blob)
    if zoff < 0:
        return None
    try:
        raw = zlib.decompress(blob[zoff:])
    except zlib.error:
        return None
    expected = width * height * 4
    if len(raw) != expected:
        return None

    mode = "RGBA"
    image = Image.frombytes(mode, (width, height), raw)
    return {
        "width": width,
        "height": height,
        "zlib_offset": zoff,
        "raw_size": len(raw),
        "image": image,
    }


def iter_rinm_images(data, section):
    base = section["offset"]
    if data[base:base + 8] != b"RINM0001":
        return
    count = struct.unpack_from("<I", data, base + 0x1C)[0]
    meta_start = base + 0x20
    data_index_start = meta_start + count * 24
    payload_base_rel = 0x20 + count * 24 + count * 12

    for idx in range(count):
        meta_off = meta_start + idx * 24
        name_hash, type_bytes, meta_a, meta_b, meta_c, resource_hash = struct.unpack_from("<I4sIIII", data, meta_off)
        data_off = data_index_start + idx * 12
        data_hash, rel_offset, size = struct.unpack_from("<III", data, data_off)
        if data_hash != resource_hash:
            # Keep parsing; some firmware builds can reuse hashes, but report the mismatch.
            pass
        abs_offset = base + rel_offset
        blob = data[abs_offset:abs_offset + size]
        decoded = decode_image_blob(blob)
        if not decoded:
            continue
        yield {
            "index": idx,
            "name_hash": f"{name_hash:08x}",
            "resource_hash": f"{resource_hash:08x}",
            "data_hash": f"{data_hash:08x}",
            "type": type_bytes.rstrip(b"\x00").decode("ascii", errors="replace"),
            "meta_a": meta_a,
            "meta_b": meta_b,
            "meta_c": meta_c,
            "rel_offset": rel_offset,
            "abs_offset": abs_offset,
            "size": size,
            "payload_base_rel": payload_base_rel,
            **{k: v for k, v in decoded.items() if k != "image"},
            "image": decoded["image"],
        }


def alpha_bbox(image):
    if image.mode != "RGBA":
        return None
    return image.getchannel("A").getbbox()


def make_contact_sheet(items, out_path, title, cell=112, cols=8):
    if not items:
        return
    rows = (len(items) + cols - 1) // cols
    header_h = 26
    sheet = Image.new("RGBA", (cols * cell, rows * cell + header_h), (18, 24, 32, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 6), title, fill=(230, 238, 246, 255))
    font = ImageFont.load_default()
    for pos, item in enumerate(items):
        x = (pos % cols) * cell
        y = header_h + (pos // cols) * cell
        draw.rectangle((x, y, x + cell - 1, y + cell - 1), outline=(56, 68, 82, 255))
        img = item["image"]
        bbox = alpha_bbox(img)
        crop = img.crop(bbox) if bbox else img
        crop.thumbnail((cell - 18, cell - 34), Image.Resampling.LANCZOS)
        px = x + (cell - crop.width) // 2
        py = y + 8 + (cell - 34 - crop.height) // 2
        checker = Image.new("RGBA", crop.size, (34, 41, 49, 255))
        sheet.alpha_composite(checker, (px, py))
        sheet.alpha_composite(crop, (px, py))
        draw.text((x + 5, y + cell - 22), f"{item['index']:04d}", fill=(220, 230, 238, 255), font=font)
        draw.text((x + 42, y + cell - 22), f"{item['width']}x{item['height']}", fill=(150, 165, 178, 255), font=font)
        draw.text((x + 5, y + cell - 11), item["resource_hash"][:8], fill=(118, 196, 255, 255), font=font)
    sheet.convert("RGB").save(out_path, quality=92)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", help="path to oem_std5_*.npkg")
    parser.add_argument("--out", required=True, help="output directory")
    parser.add_argument("--limit", type=int, default=0, help="optional extraction limit")
    args = parser.parse_args()

    package = Path(args.package)
    out_dir = Path(args.out)
    png_dir = out_dir / "png"
    png_dir.mkdir(parents=True, exist_ok=True)

    data = package.read_bytes()
    sections = read_prnm_sections(data)
    rinm = next((section for section in sections if section["magic"] == "RINM0001"), None)
    if not rinm:
        raise ValueError("RINM0001 section not found")

    manifest_path = out_dir / "manifest.jsonl"
    items = []
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for item in iter_rinm_images(data, rinm):
            filename = f"img_{item['index']:04d}_{item['resource_hash']}_{item['width']}x{item['height']}.png"
            png_path = png_dir / filename
            item["image"].save(png_path)
            record = {k: v for k, v in item.items() if k != "image"}
            record["png"] = str(png_path)
            manifest.write(json.dumps(record, ensure_ascii=False) + "\n")
            items.append({**record, "image": item["image"]})
            if args.limit and len(items) >= args.limit:
                break

    with (out_dir / "sections.json").open("w", encoding="utf-8") as fh:
        json.dump({"package": str(package), "sections": sections, "image_count": len(items)}, fh, ensure_ascii=False, indent=2)

    make_contact_sheet(items[:160], out_dir / "contact_sheet_first_160.jpg", "KIA NPKG images: first 160")
    large = [item for item in items if item["width"] >= 40 or item["height"] >= 40]
    make_contact_sheet(large[:240], out_dir / "contact_sheet_large_240.jpg", "KIA NPKG images: larger icons")
    square = [item for item in items if 20 <= item["width"] <= 220 and 20 <= item["height"] <= 220]
    make_contact_sheet(square[:240], out_dir / "contact_sheet_square_240.jpg", "KIA NPKG images: square-ish")

    print(json.dumps({
        "package": str(package),
        "out": str(out_dir),
        "images": len(items),
        "manifest": str(manifest_path),
        "png_dir": str(png_dir),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
