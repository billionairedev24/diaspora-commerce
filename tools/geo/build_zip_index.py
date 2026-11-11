#!/usr/bin/env python3
"""Build go/services/geo/internal/adapter/zipindex/us_zips.tsv.gz (US ZIP centroids with city and state).

Usage: tools/geo/build_zip_index.py <source.csv> [out.tsv.gz]

Source columns: code,city,state,lat,lon (extra columns ignored). Two public sources work:
  * GeoNames postal codes, https://download.geonames.org/export/zip/US.zip (CC-BY 4.0; tab separated, convert with
    `awk -F'\t' 'BEGIN{print "code,city,state,lat,lon"} {print $2","$3","$5","$10","$11}'`);
  * the free_zipcode_data csv (code,city,state,county,area_code,lat,lon), derived from USPS/Census public data.
The Census ZCTA gazetteer (public domain) has centroids but no city, so join it with a city source first.
Output: one line per ZIP, "zip<TAB>city<TAB>state<TAB>lat<TAB>lon", sorted by ZIP, 4-decimal coordinates (~11 m).
"""
import csv, gzip, sys

src = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else "go/services/geo/internal/adapter/zipindex/us_zips.tsv.gz"
rows = {}
with open(src, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        z = r["code"].strip().zfill(5)
        try:
            lat, lon = float(r["lat"]), float(r["lon"])
        except ValueError:
            continue
        if len(z) == 5 and z.isdigit() and r["city"].strip() and len(r["state"].strip()) == 2:
            rows[z] = (r["city"].strip(), r["state"].strip().upper(), lat, lon)
with gzip.GzipFile(out, "wb", 9, mtime=0) as g:
    for z in sorted(rows):
        c, s, la, lo = rows[z]
        g.write(f"{z}\t{c}\t{s}\t{la:.4f}\t{lo:.4f}\n".encode())
print(len(rows), "ZIPs ->", out)
