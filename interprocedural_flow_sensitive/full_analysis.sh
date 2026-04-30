#!/bin/bash

# ==============================================================================
#  Defects4J Analyzer 
# ==============================================================================

# ---SET YOUR JAVA ENVIRONMENTS HERE ---
# (Update these paths to match your exact sdkman folder names)
JAVA8_HOME="/home/rajatvarshney/.sdkman/candidates/java/8.0.482-tem"
JAVA11_HOME="/home/rajatvarshney/.sdkman/candidates/java/11" # Put your Java 11 path here

# Defects4J Environment (ADD THIS LINE)
D4J_BIN="/home/rajatvarshney/Documents/IPACO/Project2/defects4j-2.0.0/framework/bin"

# Export the Defects4J path so the script knows the command (ADD THIS LINE)
export PATH="$D4J_BIN:$PATH"

# The exact rt.jar for Soot to analyze
RT_JAR="$JAVA8_HOME/jre/lib/rt.jar"

# The package path to your Main class
MAIN_CLASS="com.ipaco.inter.Main"
WORKSPACE="/tmp/d4j_mass_workspace"
REPORT_FILE="$(pwd)/defects4j_dead_code_report.txt"
LOG_FILE="$(pwd)/mass_execution_errors.log"

export MAVEN_OPTS="-Xmx4g"

# ==============================================================================
# Define the Projects and their Maximum Bug Counts (Defects4J v2.0.0)
# ==============================================================================
# Note: You can comment out projects if you want to run them one at a time.
declare -A PROJECTS=(
    ["Chart"]="26"
    ["Lang"]="65"
    #["Time"]="27"
    #["Math"]="106"
    #["Mockito"]="38"
    #["Closure"]="174"
)

# Initialize the Report File
echo "=====================================================================" > "$REPORT_FILE"
echo "Inter-procedural Dead Code Analysis Report" >> "$REPORT_FILE"
echo "Execution Started: $(date)" >> "$REPORT_FILE"
echo "=====================================================================" >> "$REPORT_FILE"
echo "" > "$LOG_FILE" # Clear the error log

echo "Starting Mass Sweep. Results will be saved to $REPORT_FILE."

# Loop through each project in the array
for project in "${!PROJECTS[@]}"; do
    max_bugs=${PROJECTS[$project]}
    
    echo "========================================"
    echo "Starting Project: $project (Total Bugs: $max_bugs)"
    echo "========================================"

    for (( bug_id=1; bug_id<=max_bugs; bug_id++ )); do
        
        rm -rf "$WORKSPACE"
        echo -n "Analyzing $project-$bug_id... "
        
        # =================================================================
        # ENVIRONMENT SWITCH: JAVA 8 FOR DEFECTS4J
        # =================================================================
        export JAVA_HOME="$JAVA8_HOME"
        export PATH="$JAVA_HOME/bin:$PATH"

        # Checkout and Compile using Java 8
        defects4j checkout -p "$project" -v "${bug_id}b" -w "$WORKSPACE" > /dev/null 2>> "$LOG_FILE"
        
        if [ ! -d "$WORKSPACE" ]; then
            echo "Skipped (Deprecated or Missing)"
            continue
        fi

        (cd "$WORKSPACE" && defects4j compile > /dev/null 2>> "$LOG_FILE")

        # Extract Architecture using Java 8
        FAILING_TEST=$(cd "$WORKSPACE" && defects4j export -p tests.trigger 2>> "$LOG_FILE" | head -n 1 | xargs)
        DIR_CLASSES=$(cd "$WORKSPACE" && defects4j export -p dir.bin.classes 2>> "$LOG_FILE" | xargs)
        DIR_TESTS=$(cd "$WORKSPACE" && defects4j export -p dir.bin.tests 2>> "$LOG_FILE" | xargs)
        PROJECT_LIBS=$(cd "$WORKSPACE" && defects4j export -p cp.test 2>> "$LOG_FILE" | xargs)

        if [ -z "$DIR_CLASSES" ] || [ -z "$FAILING_TEST" ]; then
             echo "Skipped (Failed to extract properties)"
             continue
        fi

        SOOT_CP="$WORKSPACE/$DIR_CLASSES:$WORKSPACE/$DIR_TESTS:$PROJECT_LIBS"

        # =================================================================
        # ENVIRONMENT SWITCH: JAVA 11 FOR SOOT AND MAVEN
        # =================================================================
        export JAVA_HOME="$JAVA11_HOME"
        export PATH="$JAVA_HOME/bin:$PATH"

        # Execute the Analyzer using Java 11
        ANALYSIS_OUTPUT=$(mvn -q clean compile exec:java -Dexec.mainClass="$MAIN_CLASS" -Dexec.args="$SOOT_CP $RT_JAR $FAILING_TEST" 2>&1)

        # Parse the Output for the Report
        # Extract the lines we care about: The specific dead code warnings and the final count
        DEAD_LINES=$(echo "$ANALYSIS_OUTPUT" | grep "^\[DEAD\]")
        COUNT_LINE=$(echo "$ANALYSIS_OUTPUT" | grep "True Dead Assignments:")

        # Write to the Report File
        echo "--- $project-$bug_id ---" >> "$REPORT_FILE"
        
        if [ -n "$COUNT_LINE" ]; then
            # If there are actual dead assignments, print them
            if [ -n "$DEAD_LINES" ]; then
                echo "$DEAD_LINES" >> "$REPORT_FILE"
            fi
            echo "$COUNT_LINE" >> "$REPORT_FILE"
            echo "Done."
        else
            # If we don't see the "True Dead Assignments:" line, SPARK probably crashed or ran out of RAM
            echo "Analysis Failed (See $LOG_FILE for details)" >> "$REPORT_FILE"
            echo "Failed."
            
            # Log the crash output for debugging
            echo "--- Crash in $project-$bug_id ---" >> "$LOG_FILE"
            echo "$ANALYSIS_OUTPUT" >> "$LOG_FILE"
        fi
        
        echo "" >> "$REPORT_FILE"

    done
done

# Final Cleanup
rm -rf "$WORKSPACE"
echo "========================================"
echo "Mass Sweep Complete! Check $REPORT_FILE."
echo "========================================"