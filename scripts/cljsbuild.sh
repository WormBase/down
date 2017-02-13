#!/bin/sh

target="$1"
lein with-profile "+${target}" cljsbuild once "${target}"
lein with-profile "+${target}" minify-assets "${target}"

