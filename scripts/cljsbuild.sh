#!/bin/sh

target="$1"
rm -rf resources/public/css/site.min.css
rm -rf resources/public/js/site.min.js
lein clean
lein cljsbuild once "${target}"
lein minify-assets "${target}"

