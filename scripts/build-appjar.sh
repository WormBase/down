#!/bin/sh

lein_cmd_prefix="lein with-profile +datomic-pro,+ddb"
artefact="$1"
eval "${lein_cmd_prefix} cljsbuild once"
path=`${lein_cmd_prefix} ring uberjar | \
	    sed -n 's|^Created \(\/.*standalone.jar\)|\1|p'`

mv "${path}" "${artefact}"
echo "${artefact}"
