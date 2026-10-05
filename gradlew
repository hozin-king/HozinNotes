#!/bin/sh
# Gradle wrapper stub launcher for Hozin Notes.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_BASE_NAME=$(basename -- "$0")
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
