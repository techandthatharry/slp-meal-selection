#!/bin/sh

APP_HOME=$(cd "${0%/*}" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$CLASSPATH" ]; then
  echo "ERROR: gradle-wrapper.jar not found. Please generate wrapper in Android Studio."
  exit 1
fi

exec "${JAVA_HOME}/bin/java" -Xmx64m -Xms64m ${JAVA_OPTS} ${GRADLE_OPTS} -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
