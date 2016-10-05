#!/bin/sh
# Compile a JAR file comprising the application for production
#  - includes all JavaScript and CSS resources:
# Positional arguments:
#   1. $target_profile:"prod" or "dev".
#   2. $artefact: path to jar file to be created.
target_profile="$1"
artefact="$2"
lein cljsbuild once "${target_profile}"
lein minify-assets "${target_profile}"
path=`lein with-profile +${target_profile} ring uberjar | \
	    sed -n 's|^Created \(\/.*standalone.jar\)|\1|p'`
mv "${path}" "${artefact}"
echo "${artefact}"
