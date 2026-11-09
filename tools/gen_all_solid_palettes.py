#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从颂拼豆(iBeads) 7.19.36 的 flutter 资产生成「全色系」色卡。

输入（随仓库保存，保证可复现）：
  tools/vendor/ibeads/bead.json          # 302 个实物色号 → RGB，按色系分组
  tools/vendor/ibeads/bead_package.json  # 9 套成品套装 → 色号清单（24/48/…/272）

输出：
  app/src/main/assets/palettes/all_solid_{n}.json

用法：
  python tools/gen_all_solid_palettes.py
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, "tools", "vendor", "ibeads")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "palettes")

# 套装 id → (品牌代号, 中文名, 是否标注「全实色」)
PACKAGES = [
    ("bead24", "ALL24", "全色系 24 色", False),
    ("bead48", "ALL48", "全色系 48 色", False),
    ("bead72", "ALL72", "全色系 72 色", False),
    ("bead96", "ALL96", "全色系 96 色", False),
    ("bead120", "ALL120", "全色系 120 色", False),
    ("bead144", "ALL144", "全色系 144 色", False),
    ("bead216", "ALL216", "全色系 216 全实色", True),
    ("bead221", "ALL221", "全色系 221 全实色", True),
    ("bead272", "ALL272", "全色系 272 全实色", True),
]

SOURCE = "颂拼豆(iBeads) 7.19.36 flutter assets: bead.json + bead_package.json"


def load_codes():
    """色号 → (hex, 系列名)。系列名去掉 "[A]融合拼豆实物色卡·" 前缀。"""
    bead = json.load(open(os.path.join(VENDOR, "bead.json"), encoding="utf-8"))
    table = {}
    for cat in bead["categoryList"]:
        for series in cat["seriesList"]:
            title = series["titleZh"]
            short = title.split("·")[-1] if "·" in title else title
            short = short.replace("系列", "").strip()
            for c in series["colorList"]:
                # 同一个色号在多个规格里出现过时保留先出现的（302 色号本身无重复）
                table.setdefault(c["n"], ("#%02X%02X%02X" % (c["r"], c["g"], c["b"]), short))
    return table


def main():
    table = load_codes()
    packages = json.load(open(os.path.join(VENDOR, "bead_package.json"), encoding="utf-8"))
    by_id = {p["id"]: p for p in packages["packageList"]}

    os.makedirs(OUT_DIR, exist_ok=True)
    for pid, brand, label, _solid in PACKAGES:
        pkg = by_id[pid]
        colors = []
        for code in pkg["colorList"]:
            if code not in table:
                sys.exit("缺少色号 %s（%s）" % (code, pid))
            hex_value, series = table[code]
            colors.append({"code": code, "name": series, "hex": hex_value})
        dto = {
            "brand": brand,
            "brandLabel": label,
            "beadSize": "2.6mm",
            "version": 1,
            "source": SOURCE,
            "colors": colors,
        }
        path = os.path.join(OUT_DIR, "all_solid_%d.json" % len(colors))
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(dto, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("%-28s %3d 色  %s" % (os.path.basename(path), len(colors), label))


if __name__ == "__main__":
    main()
