#!/bin/sh

target="$1"
lein cljsbuild once "${target}"
lein minify-assets "${target}"

