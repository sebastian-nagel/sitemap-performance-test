#!/bin/bash

DIR=$(dirname $0)


CLASS=${CLASS:-crawlercommons.sitemaps.SiteMapPerformanceTest}

JAVA_OPTS=""
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

CLASSPATH=target/sitemap-parser-test-0.0.1-SNAPSHOT.jar:$(ls target/dependency/*.jar | tr '\n' ':')target/test-classes
set -x
time java -cp $CLASSPATH $JAVA_OPTS $CLASS "$@"
