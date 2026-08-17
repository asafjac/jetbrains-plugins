#!/usr/bin/env sh
# Builds without a JDK installed.
#
# Gradle needs a JVM before it can do anything, so a machine with no JAVA_HOME and no java on
# PATH cannot run ./gradlew at all - which is most machines, since a JetBrains IDE bundles its
# own runtime instead of installing one system-wide. That bundled runtime is a real JDK, so
# this finds one and hands it to Gradle.
set -e

find_jbr() {
    [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && { echo "$JAVA_HOME"; return; }
    command -v java >/dev/null 2>&1 && { echo ""; return; }

    for root in \
        "$LOCALAPPDATA/Programs" \
        "$HOME/AppData/Local/Programs" \
        "$HOME/AppData/Local/JetBrains/Toolbox/apps" \
        "/c/Program Files/JetBrains" \
        "/Applications" \
        "$HOME/.local/share/JetBrains/Toolbox/apps"
    do
        [ -d "$root" ] || continue
        # Newest first, so a current IDE's runtime wins over an old one left behind.
        for jbr in $(ls -dt "$root"/*/jbr "$root"/*/*/jbr "$root"/*/Contents/jbr 2>/dev/null); do
            if [ -x "$jbr/bin/java" ] || [ -x "$jbr/Contents/Home/bin/java" ]; then
                [ -x "$jbr/Contents/Home/bin/java" ] && echo "$jbr/Contents/Home" || echo "$jbr"
                return
            fi
        done
    done
    echo ""
}

JBR="$(find_jbr)"
if [ -n "$JBR" ]; then
    JAVA_HOME="$JBR"
    export JAVA_HOME
    echo "Using JDK: $JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
    echo "Using java from PATH"
else
    echo "No JDK found. Install any JetBrains IDE (its bundled runtime is enough), or set JAVA_HOME." >&2
    exit 1
fi

# The IntelliJ Platform needs a specific JDK to compile against, which may differ from the one
# running Gradle; offering this JDK as a toolchain candidate saves downloading another.
exec ./gradlew "-Porg.gradle.java.installations.paths=$JAVA_HOME" "${@:-buildPlugin}"
