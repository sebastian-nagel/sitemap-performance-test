#!/bin/bash

DIR=$(dirname $0)


CLASS=${CLASS:-crawlercommons.sitemaps.SiteMapPerformanceTest}

JAVA_OPTS="${JAVA_OPTS:-}"
while [[ $# -gt 0 ]]; do
	case "$1" in
		-D* | -X*)
			JAVA_OPTS="$JAVA_OPTS $1"
			shift
			;;
		* )
			break
			;;
	esac
done

cd $DIR

if ! [ -d "$DIR/target/dependency/" ]; then
    mvn dependency:copy-dependencies
fi

CLASSPATH=$(ls target/sitemap-parser-test-*.jar):$(ls target/dependency/*.jar | tr '\n' ':')target/test-classes

set -x

PROFILE=${PROFILE:-false} # ASYNCIO_HOME=$ASYNCIO_HOME PROFILE=true 
if $PROFILE; then
    java -cp $CLASSPATH $JAVA_OPTS $CLASS "$@" &
    pid=$!
    sleep .1
    $ASYNCIO_HOME/profiler.sh "${ASYNCIO_OPTS[@]}" -d 1800 -f $CLASS.$(date +%Y-%m-%d-%H-%M).async-prof.svg "$pid"
else
    time java -cp $CLASSPATH $JAVA_OPTS $CLASS "$@"
fi
