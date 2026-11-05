#!/usr/bin/env bash
# Downloads the MOTIS binary, the TransMilenio/SITP GTFS feed and a Bogotá OSM
# extract, then runs the MOTIS import. Safe to re-run: existing downloads are
# kept unless you pass --refresh.
set -euo pipefail

MOTIS_VERSION="v2.11.4"
# Official TransMilenio feed, as registered in Transitland Atlas
# (f-d2g6-transmilenio~...). The filename changes with each release, so check
# https://github.com/transitland/transitland-atlas/blob/main/feeds/transmilenio.gov.co.dmfr.json
GTFS_URL="https://storage.googleapis.com/gtfs-estaticos/GTFS_20260929.zip"
OSM_URL="https://download.geofabrik.de/south-america/colombia-latest.osm.pbf"
# Covers every stop in the feed (Usme to Chía/Soacha) with some margin.
BBOX="-74.30,4.30,-73.95,4.90"

cd "$(dirname "$0")/.."
mkdir -p bin input

refresh=false
[[ "${1:-}" == "--refresh" ]] && refresh=true

need() { command -v "$1" >/dev/null || { echo "missing dependency: $1" >&2; exit 1; }; }
need curl
need osmium

if [[ ! -x bin/motis ]] || $refresh; then
  echo "→ MOTIS $MOTIS_VERSION"
  curl -fSL "https://github.com/motis-project/motis/releases/download/$MOTIS_VERSION/motis-linux-amd64.tar.bz2" \
    | tar -xj -C bin
fi

if [[ ! -f input/transmilenio.gtfs.zip ]] || $refresh; then
  echo "→ GTFS"
  curl -fSL -o input/transmilenio.gtfs.zip "$GTFS_URL"
fi

if [[ ! -f input/bogota.osm.pbf ]] || $refresh; then
  echo "→ OSM (Colombia, cropped to Bogotá)"
  curl -fSL -o input/colombia.osm.pbf "$OSM_URL"
  osmium extract --overwrite -b "$BBOX" -o input/bogota.osm.pbf input/colombia.osm.pbf
  rm input/colombia.osm.pbf
fi

echo "→ import (takes a few minutes the first time)"
bin/motis import -c config.yml
echo "done. start the server with: pnpm motis"
