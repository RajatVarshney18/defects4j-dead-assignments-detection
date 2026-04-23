#!/bin/bash

# ==============================================================================
# IPACO Inter-procedural Analysis Runner
# ==============================================================================

# 1. SET YOUR PATHS HERE
# The directory where you checked out the Defects4J bug
TARGET_DIR="/home/rajatvarshney/Documents/IPACO/Project2/defects4j-2.0.0/d4j_checkouts/Chart/Chart_24_buggy"

# The path to your Java 8 JRE rt.jar
RT_JAR="/home/rajatvarshney/.sdkman/candidates/java/8.0.482-tem/jre/lib/rt.jar"

# ==============================================================================
# SCRIPT LOGIC (Do not edit below unless modifying the pipeline)
# ==============================================================================

echo "=== Starting IPACO Analysis Pipeline ==="
echo "Target Directory: $TARGET_DIR"

# 2. Verify Target Directory Exists
if [ ! -d "$TARGET_DIR" ]; then
    echo "[ERROR] Target directory does not exist: $TARGET_DIR"
    exit 1
fi

# 3. Extract the Failing Test Automatically
echo "Extracting failing test using Defects4J..."
# We navigate to the dir, run export, grab the first line, and use xargs to trim whitespace
FAILING_TEST=$(cd "$TARGET_DIR" && defects4j export -p tests.trigger | head -n 1 | xargs)

if [ -z "$FAILING_TEST" ]; then
    echo "[ERROR] Could not extract failing test. Is this a valid compiled Defects4J project?"
    exit 1
fi

echo "Target Test: $FAILING_TEST"

# 4. Construct the Classpath Dynamically using Defects4J
echo "Querying Defects4J for project architecture..."
DIR_CLASSES=$(cd "$TARGET_DIR" && defects4j export -p dir.bin.classes | xargs)
DIR_TESTS=$(cd "$TARGET_DIR" && defects4j export -p dir.bin.tests | xargs)

# --- Grab third-party dependencies ---
PROJECT_LIBS=$(cd "$TARGET_DIR" && defects4j export -p cp.test | xargs)

if [ -z "$DIR_CLASSES" ] || [ -z "$DIR_TESTS" ]; then
    echo "[ERROR] Could not extract bin directories from Defects4J."
    exit 1
fi

CP_CLASSES="$TARGET_DIR/$DIR_CLASSES"
CP_TESTS="$TARGET_DIR/$DIR_TESTS"
SOOT_CP="$CP_CLASSES:$CP_TESTS:$PROJECT_LIBS"

echo "Library Classpath: $CP_CLASSES"
echo "Test Classpath: $CP_TESTS"
echo "Project Libraries: $PROJECT_LIBS"
# 5. Allocate Memory for SPARK Call Graph Generation
export MAVEN_OPTS="-Xmx4g"
echo "Allocated 4GB RAM for JVM."

# 6. Run the Analyzer
echo "Executing Maven..."
echo "----------------------------------------------------------------------"
mvn clean compile exec:java -Dexec.mainClass="com.ipaco.inter.Main" -Dexec.args="$SOOT_CP $RT_JAR $FAILING_TEST"