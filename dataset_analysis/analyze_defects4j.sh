#!/bin/bash
# =============================================================================
# analyze_defects4j.sh  — ADVANCED VERSION (v4, correct dead-var heuristics)
# Phase 1: Dataset Analysis — Dead Variable Pattern Detection
# =============================================================================
#
# DEAD VARIABLE PATTERN (precise definition):
#   A variable (new_var) is ASSIGNED a value but NEVER READ before the
#   function exits or it is overwritten — because at the use site, another
#   variable (old_var) is used instead.
#
#   Canonical example:
#       double v = Math.max(value, lowerBound);  ← v is assigned
#       v = Math.min(v, upperBound);              ← v updated
#       int g = (int)((value - lowerBound)/...); ← BUG: 'value' used, not 'v'
#       return new Color(g,g,g);                 ← v NEVER READ → v is DEAD
#
# DETECTION ALGORITHM (per bug):
#   Step 1: Find a one-variable substitution in the diff (old_var → new_var).
#   Step 2: Verify the bug line is a READ site (old_var consumed, not assigned).
#   Step 3: Check new_var is a LOCAL variable, not a method parameter.
#   Step 4: Check new_var is NOT READ in context lines ABOVE the bug line
#           between its last assignment and the bug line.
#           (If it IS read above → it's live, not dead → false positive)
#   Step 5: Check new_var has few/no reads in context lines BELOW the bug.
#           (If many reads below → still alive after the bug → lower confidence)
#   Final:  Classify as CONFIRMED_DEAD / POSSIBLE_DEAD / FALSE_POSITIVE
# =============================================================================

# --- Configuration -----------------------------------------------------------
D4J_HOME="/home/rajatvarshney/Documents/IPACO/Project2/defects4j"
export PATH="$PATH:$D4J_HOME/framework/bin"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT_BASE="/tmp/d4j_checkouts"
CSV="$SCRIPT_DIR/dead_var_candidates.csv"
LOG="$SCRIPT_DIR/analysis.log"

PROJECT="Lang"
MAX_BUGS=64

mkdir -p "$CHECKOUT_BASE"

# --- Output files ------------------------------------------------------------
echo "project,bug_id,file,old_var,new_var,verdict,reads_above,reads_below,buggy_line,fixed_line" > "$CSV"
echo "=== Dead Variable Analysis v4: $(date) ===" > "$LOG"
echo "Project: $PROJECT | Bugs: 1 to $MAX_BUGS" | tee -a "$LOG"
echo ""

# =============================================================================
# JAVA KEYWORDS — tokens that are NOT variable names
# =============================================================================
JAVA_KEYWORDS=" abstract assert boolean break byte case catch char class const \
continue default do double else enum extends final finally float for goto if \
implements import instanceof int interface long native new package private \
protected public return short static strictfp super switch synchronized this \
throw throws transient try void volatile while true false null "

is_java_keyword() {
    [[ "$JAVA_KEYWORDS" == *" $1 "* ]]
}

# =============================================================================
# FUNCTION: do_checkout (unchanged — standalone to avoid exit-255)
# =============================================================================
do_checkout() {
    local project=$1  bug_id=$2  checkout_dir=$3

    if [ -f "$checkout_dir/.checkout_done" ]; then return 0; fi

    rm -rf "$checkout_dir"
    echo ""
    defects4j checkout -p "$project" -v "${bug_id}b" -w "$checkout_dir" 2>/dev/null
    local code=$?
    [ $code -eq 0 ] && touch "$checkout_dir/.checkout_done"
    return $code
}

# =============================================================================
# FUNCTION: get_diff
# Uses -U20 (20 context lines each side) so we capture more of the method body.
# This is crucial for checking whether new_var is read above/below the bug.
# =============================================================================
get_diff() {
    local project=$1  bug_id=$2  checkout_dir=$3
    local buggy="D4J_${project}_${bug_id}_BUGGY_VERSION"
    local fixed="D4J_${project}_${bug_id}_FIXED_VERSION"
    git -C "$checkout_dir" diff -U20 "$buggy" "$fixed" -- src/ 2>/dev/null
}

# =============================================================================
# FUNCTION: tokenize
# Splits a code line into individual tokens (preserving duplicates via sort
# without -u, so frequency differences are captured).
# '-' at end of tr set to avoid range interpretation.
# =============================================================================
tokenize() {
    local line="$1"
    echo "$line" | tr ' ().,;[]{} *+/=!<>&|"\\@:~^%?' '\n' \
                 | tr '-' '\n' \
                 | grep -v '^$' \
                 | sort
    # NOTE: No 'sort -u' — frequency matters for detecting substitution
    # when a variable appears multiple times (e.g., 'pos' twice in one line).
}

# =============================================================================
# FUNCTION: get_substitution
# Returns "old_var|new_var" if exactly ONE token changed between the two lines.
# Returns "" if not a clean single-token substitution.
# =============================================================================
get_substitution() {
    local removed="$1"  added="$2"

    local d
    d=$(diff <(tokenize "$removed") <(tokenize "$added") 2>/dev/null)

    local ndiffs
    ndiffs=$(echo "$d" | grep '^[<>]' | wc -l)

    [ "$ndiffs" -ne 2 ] && echo "" && return

    local old_tok new_tok
    old_tok=$(echo "$d" | grep '^< ' | sed 's/^< //' | xargs)
    new_tok=$(echo "$d" | grep '^> ' | sed 's/^> //' | xargs)

    echo "${old_tok}|${new_tok}"
}

# =============================================================================
# FUNCTION: is_identifier
# Returns 0 (true) if the token looks like a Java local variable name.
# =============================================================================
is_identifier() {
    [[ "$1" =~ ^[a-z][a-zA-Z0-9_]*$ ]] && ! is_java_keyword "$1"
}

# =============================================================================
# FUNCTION: is_lhs_assignment
# Returns 0 (true) if the code line looks like an assignment WHERE 'token'
# is the LEFT-HAND SIDE (i.e., the variable is being WRITTEN, not READ).
#
# WHY THIS MATTERS:
#   If old_var is on the LHS of an assignment in the bug line, then old_var
#   is being WRITTEN at the bug site, not read. The dead-var pattern requires
#   that old_var is being USED (read) at the bug site in the wrong place.
#   Example of a false match: "oldVar = something;" — already excluded.
#
# Patterns matched as LHS:
#   "token = ..."         simple assignment
#   "token += ..."        compound assignment
#   "Type token = ..."    declaration + assignment
# =============================================================================
is_lhs_assignment() {
    local token="$1"  line="$2"
    # Match: token followed by assignment operator (optionally with type prefix)
    echo "$line" | grep -qE "(^|[^a-zA-Z0-9_])${token}\s*([+\-*\/&|^]?=)[^=]"
}

# =============================================================================
# FUNCTION: is_method_parameter
# Returns 0 (true) if 'var' is declared as a formal parameter of the method
# that contains the bug line.
#
# WHY THIS MATTERS:
#   Method parameters are always "live" from the caller's perspective.
#   If new_var is a parameter, it was never "dead" — it was given a value
#   by the caller and the programmer forgot to use it, but there's no
#   assignment of a "computed" value that went unused.
#   Lang-59: 'width' is a parameter → not a local dead var.
#
# Approach: find the closest method signature ABOVE the bug line in source,
# check if the parameter list contains 'var'.
# =============================================================================
is_method_parameter() {
    local var="$1"  source_file="$2"  bug_lineno="$3"

    # Get all content UP TO the bug line
    local above
    above=$(head -n "$bug_lineno" "$source_file" 2>/dev/null)

    # Find the last method signature before the bug line:
    # A method signature ends with ')' and its parameter list may contain 'var'
    local last_sig
    last_sig=$(echo "$above" | grep -E '(public|private|protected|static|final|void|int|long|double|float|boolean|String|Object|char|byte|short)[^{;]*\(' \
               | tail -1)

    # If the method signature line itself contains 'var' → it's a parameter
    echo "$last_sig" | grep -qE "\b${var}\b"
}

# =============================================================================
# FUNCTION: count_reads_in_context (KEY FUNCTION — the heart of the new logic)
#
# Counts how many times 'var' appears in READ context (not just declaration
# or assignment LHS) within the given block of lines.
#
# Read context = var appears in an expression, condition, argument, etc.
# NOT read context = "Type var = ..." (declaration) or "var = ..." (assignment LHS)
#
# This is used to check:
#   - reads ABOVE bug: if > 0, var is live before bug → not dead
#   - reads BELOW bug: if > 0, var is used after bug → less dead
#
# IMPLEMENTATION:
#   1. Find all lines containing var (word boundary match)
#   2. Exclude declaration lines: "Type var [=;]"
#   3. Exclude pure LHS assignment: "var = ..." or "var += ..." etc
#   4. Count what remains (= True reads)
# =============================================================================
count_reads_in_context() {
    local var="$1"
    local context="$2"

    # Use fgrep + safer boundary check (avoids \b which is unreliable for ERE
    # and avoids interpolating var into brackets which causes 'Invalid range end')
    all_lines=$(echo "$context" | grep -F "$var" 2>/dev/null \
                | grep -E "(^|[^a-zA-Z0-9_])${var}([^a-zA-Z0-9_]|$)" 2>/dev/null)

    [ -z "$all_lines" ] && echo "0" && return

    # Remove DECLARATION lines:
    #   Pattern 1: "Type varName" or "Type varName =" (local declaration)
    #   Pattern 2: "for (... Type varName" (for-loop variable)
    # Strategy: if a line contains a type keyword OR uppercase-starting type
    #            immediately before var, it's a declaration.
    local non_decl
    non_decl=$(echo "$all_lines" | while IFS= read -r line; do
        # Check if this line declares var (type precedes var name)
        if echo "$line" | grep -qE \
            "(boolean|byte|char|short|int|long|float|double|String|Object|[A-Z][A-Za-z0-9_<>\[\]]*)[[:space:]]+${var}[[:space:]]*[=;,)]"; then
            continue  # It's a declaration — skip
        fi
        echo "$line"
    done)

    [ -z "$non_decl" ] && echo "0" && return

    # Remove pure LHS-assignment lines where var is the ONLY thing on the left:
    #   e.g., 'var = ...', 'var += ...', 'var++', 'var--'
    local reads
    reads=$(echo "$non_decl" | while IFS= read -r line; do
        if echo "$line" | grep -qE "^[[:space:]]*${var}[[:space:]]*([+\-*\/&|^%]?=)[^=]"; then
            continue  # Pure LHS assignment — skip
        fi
        if echo "$line" | grep -qE "^[[:space:]]*${var}([+][+]|[\-][\-])[[:space:]]*;"; then
            continue  # var++ or var-- alone — skip
        fi
        echo "$line"
    done)

    echo "$reads" | grep '[^[:space:]]' | wc -l
}

# =============================================================================
# FUNCTION: get_context_above
# Extracts the context lines that appear BEFORE the first removed ('-') line
# in the diff. These are the lines of the method body above the bug.
# =============================================================================
get_context_above() {
    local diff_text="$1"
    echo "$diff_text" | awk '
        /^@@ /   { in_hunk=1; past_minus=0; next }
        /^diff / { in_hunk=0; next }
        in_hunk && substr($0,1,4) == "--- " { next }
        in_hunk && substr($0,1,4) == "+++ " { next }
        in_hunk && substr($0,1,1) == "-" { past_minus=1 }
        in_hunk && substr($0,1,1) == " " && !past_minus { print substr($0, 2) }
    '
    # NOTE: uses substr() not regex for -/+ to avoid ERE quantifier issues
}

# =============================================================================
# FUNCTION: get_context_below
# Extracts the context lines that appear AFTER the last added ('+') line
# in the diff. These show what happens in the method AFTER the fix point.
# =============================================================================
get_context_below() {
    local diff_text="$1"
    echo "$diff_text" | awk '
        /^@@ /   { in_hunk=1; past_plus=0; next }
        /^diff / { in_hunk=0; next }
        in_hunk && substr($0,1,4) == "--- " { next }
        in_hunk && substr($0,1,4) == "+++ " { next }
        in_hunk && substr($0,1,1) == "+" { past_plus=1 }
        in_hunk && substr($0,1,1) == " " && past_plus { print substr($0, 2) }
    '
}

# =============================================================================
# FUNCTION: find_bug_lineno
# Finds the line number in the source file corresponding to the bug line.
# We use the hunk header "@@ -old_start ..." to get the line numbers.
# =============================================================================
find_bug_lineno() {
    local diff_text="$1"
    # "@@ -old_start,old_count +new_start,new_count @@"
    # The old_start gives the line number in the buggy file where the hunk starts.
    local hunk_header
    hunk_header=$(echo "$diff_text" | grep '^@@ ' | head -1)
    local old_start
    old_start=$(echo "$hunk_header" | sed 's/@@ -\([0-9]*\).*/\1/')

    # Count how many context lines appear before the first removed line
    local context_before
    context_before=$(echo "$diff_text" | awk '
        /^@@ / { in_hunk=1; next }
        in_hunk && /^-/ { exit }
        in_hunk && /^ / { count++ }
        END { print count+0 }
    ')

    echo $((old_start + context_before))
}

# =============================================================================
# FUNCTION: analyze_bug
# Master analysis function. Returns a verdict string for each bug.
#
# VERDICT FORMAT: "VERDICT:details"
# Verdicts:
#   CONFIRMED_DEAD  — passes all dead-var checks (high confidence)
#   POSSIBLE_DEAD   — passes substitution + some checks (medium confidence)
#   FALSE_POSITIVE  — fails a dead-var check (var is live)
#   SKIP:reason     — not even a substitution candidate
# =============================================================================
analyze_bug() {
    local diff_text="$1"
    local checkout_dir="$2"

    # -- Pre-check: single file only --
    local file_count
    file_count=$(echo "$diff_text" | grep '^diff --git' | wc -l)
    [ "$file_count" -gt 1 ] && echo "SKIP:multi-file($file_count)" && return

    local changed_file
    changed_file=$(echo "$diff_text" | grep '^diff --git' | head -1 \
                   | sed 's|diff --git a/.* b/||')

    # -- Extract all removed/added code lines --
    local tmp_r tmp_a
    tmp_r=$(mktemp)
    tmp_a=$(mktemp)
    echo "$diff_text" | grep '^-' | grep -v '^---' | sed 's/^-//' > "$tmp_r"
    echo "$diff_text" | grep '^+' | grep -v '^+++' | sed 's/^+//' > "$tmp_a"

    local removed_count added_count
    removed_count=$(grep '[^[:space:]]' "$tmp_r" | wc -l)
    added_count=$(  grep '[^[:space:]]' "$tmp_a" | wc -l)

    if [ "$removed_count" -eq 0 ]; then
        rm -f "$tmp_r" "$tmp_a"
        echo "SKIP:pure-addition"
        return
    fi

    if [ "$removed_count" -ne "$added_count" ]; then
        rm -f "$tmp_r" "$tmp_a"
        echo "SKIP:unequal-lines(${removed_count}r-${added_count}a)"
        return
    fi

    # -- Check each (removed, added) pair for consistent substitution --
    local global_old="" global_new=""
    local first_removed="" first_added=""
    local all_consistent=true

    for i in $(seq 1 "$removed_count"); do
        local r a sub
        r=$(sed -n "${i}p" "$tmp_r")
        a=$(sed -n "${i}p" "$tmp_a")
        sub=$(get_substitution "$r" "$a")
        if [ -z "$sub" ]; then all_consistent=false; break; fi
        local this_old="${sub%%|*}"  this_new="${sub##*|}"
        if [ -z "$global_old" ]; then
            global_old="$this_old"; global_new="$this_new"
            first_removed="$r"; first_added="$a"
        elif [ "$this_old" != "$global_old" ] || [ "$this_new" != "$global_new" ]; then
            all_consistent=false; break
        fi
    done

    rm -f "$tmp_r" "$tmp_a"

    if [ "$all_consistent" != "true" ] || [ -z "$global_old" ]; then
        echo "SKIP:no-consistent-substitution"
        return
    fi

    # -- Filter: must be Java identifiers (not keywords, not types) --
    if ! is_identifier "$global_old" || ! is_identifier "$global_new"; then
        echo "SKIP:not-identifier(${global_old}→${global_new})"
        return
    fi

    # -- Filter: neither token should be used as a method call --
    if echo "$first_removed $first_added" | grep -qE "(^|[^a-zA-Z0-9_])(${global_old}|${global_new})\s*\("; then
        echo "SKIP:method-call(${global_old}|${global_new})"
        return
    fi

    # =========================================================================
    # NEW DEAD-VAR CHECKS (the key improvements over v3)
    # =========================================================================

    # -- Check 1: The bug line must be a READ site for old_var --
    # If old_var is on the LHS of an assignment in the bug line,
    # then old_var is being WRITTEN there, not wrongly READ → not our pattern.
    if is_lhs_assignment "$global_old" "$first_removed"; then
        echo "FALSE_POSITIVE:old_var_is_lhs(${global_old})"
        return
    fi

    # -- Get context lines above and below the bug --
    local ctx_above ctx_below
    ctx_above=$(get_context_above "$diff_text")
    ctx_below=$(get_context_below "$diff_text")

    # -- Check 2: new_var must NOT be a method parameter --
    # Find the source file for more reliable parameter check
    local source_file
    source_file=$(find "$checkout_dir" -name "$(basename "$changed_file")" \
                  -path "*/src/*" 2>/dev/null | head -1)

    if [ -n "$source_file" ]; then
        local bug_lineno
        bug_lineno=$(find_bug_lineno "$diff_text")

        if is_method_parameter "$global_new" "$source_file" "$bug_lineno"; then
            echo "FALSE_POSITIVE:new_var_is_parameter(${global_new})"
            return
        fi
    fi

    # -- Check 3: Count reads of new_var in context ABOVE the bug line --
    # WHY: If new_var is read above the bug line, it was already consumed
    # before the bug site → it is NOT dead at the bug point.
    # A dead variable's value should be sitting UNUSED until the fix reads it.
    local reads_above
    reads_above=$(count_reads_in_context "$global_new" "$ctx_above")

    # -- Check 4: Count reads of new_var in context BELOW the bug line --
    # WHY: If new_var is heavily read BELOW the bug line in the buggy version,
    # it might be live on other paths (e.g., else-branches).
    # However: having reads below is common and doesn't disprove the pattern;
    # it just lowers our confidence a bit.
    local reads_below
    reads_below=$(count_reads_in_context "$global_new" "$ctx_below")

    # -- Confidence: new_var must be ASSIGNED in context above (not just a field) --
    # Checks for local variable declaration OR for-loop variable declaration in context.
    # Patterns:
    #   Local var:   "int pt = 0" or "String str" or "SomeType var ="
    #   For-loop:    "for (int pt" or "for (Type pt"
    local is_declared_locally
    if echo "$ctx_above" | grep -F "$global_new" 2>/dev/null | grep -qE \
        "(boolean|byte|char|short|int|long|float|double|String|Object|[A-Z][A-Za-z0-9_<>\[\]]*)[[:space:]]+${global_new}[[:space:]]*[=;,)]"; then
        is_declared_locally=true
    elif echo "$ctx_above" | grep -F "$global_new" 2>/dev/null | grep -qE \
        "for[[:space:]]*\(.*[[:space:]]+${global_new}[[:space:]]*[=;,)]"; then
        # For-loop variable: "for (int pt = 0; pt < ...; pt++)"
        is_declared_locally=true
    else
        is_declared_locally=false
    fi

    # =========================================================================
    # VERDICT LOGIC
    # =========================================================================
    local verdict
    local confidence="${removed_count}-line"
    [ "$removed_count" -gt 1 ] && confidence="multi-line-consistent(${removed_count})"

    if [ "$reads_above" -eq 0 ] && [ "$is_declared_locally" = "true" ]; then
        # new_var declared locally + NOT read above bug → strong dead-var signal
        verdict="CONFIRMED_DEAD"
    elif [ "$reads_above" -eq 0 ] && [ "$is_declared_locally" = "false" ]; then
        # new_var not read above, but no local declaration found in context window
        # Could be declared further up (out of our 20-line context), or be a field
        verdict="POSSIBLE_DEAD:no-local-decl-in-context"
    elif [ "$reads_above" -eq 1 ]; then
        # new_var read ONCE above — could be a for-loop condition (e.g., pt < N)
        # which doesn't consume the VALUE, just checks it. Keep as lower confidence.
        verdict="POSSIBLE_DEAD:1-read-above(may-be-loop-condition)"
    else
        # new_var read >= 2 times above → clearly alive before the bug
        verdict="FALSE_POSITIVE:new_var_live_above(reads=${reads_above})"
    fi

    echo "RESULT:${global_old}|${global_new}|${changed_file}|${verdict}|${confidence}|${reads_above}|${reads_below}|${first_removed}|${first_added}"
}


# =============================================================================
# MAIN LOOP
# =============================================================================
echo "Scanning $MAX_BUGS bugs in $PROJECT..."
echo "----------------------------------------"

checked=0; confirmed=0; possible=0; false_pos=0; errors=0

for bug_id in $(seq 1 $MAX_BUGS); do

    echo -n "  Bug $PROJECT-$bug_id: "
    CHECKOUT_DIR="$CHECKOUT_BASE/${PROJECT}_${bug_id}"

    # Checkout (standalone — not in $())
    do_checkout "$PROJECT" "$bug_id" "$CHECKOUT_DIR"
    if [ $? -ne 0 ]; then
        echo "ERROR (checkout failed)"
        echo "$PROJECT,$bug_id,ERROR,,,,,,,," >> "$LOG"
        errors=$((errors + 1))
        continue
    fi

    # Get diff
    diff_output=$(get_diff "$PROJECT" "$bug_id" "$CHECKOUT_DIR")
    if [ -z "$diff_output" ]; then
        echo "SKIP (empty diff)"
        echo "$PROJECT,$bug_id,SKIP:empty-diff,,,,,,,," >> "$LOG"
        continue
    fi

    checked=$((checked + 1))

    # Analyze
    result=$(analyze_bug "$diff_output" "$CHECKOUT_DIR")

    # Interpret result
    if [[ "$result" == RESULT:* ]]; then
        info="${result#RESULT:}"
        old_var="${info%%|*}";       info="${info#*|}"
        new_var="${info%%|*}";       info="${info#*|}"
        changed_file="${info%%|*}";  info="${info#*|}"
        verdict="${info%%|*}";       info="${info#*|}"
        confidence="${info%%|*}";    info="${info#*|}"
        reads_above="${info%%|*}";   info="${info#*|}"
        reads_below="${info%%|*}";   info="${info#*|}"
        buggy_line="${info%%|*}"
        fixed_line="${info##*|}"

        buggy_esc=$(echo "$buggy_line" | sed 's/,/;/g')
        fixed_esc=$(echo "$fixed_line"  | sed 's/,/;/g')

        if [[ "$verdict" == CONFIRMED_DEAD* ]]; then
            echo "✓  CONFIRMED_DEAD: '$old_var' → '$new_var'  [above:${reads_above} below:${reads_below}]  $(basename "$changed_file")"
            confirmed=$((confirmed + 1))
        elif [[ "$verdict" == POSSIBLE_DEAD* ]]; then
            detail="${verdict#POSSIBLE_DEAD:}"
            echo "?  POSSIBLE_DEAD ($detail): '$old_var' → '$new_var'  [above:${reads_above} below:${reads_below}]  $(basename "$changed_file")"
            possible=$((possible + 1))
        elif [[ "$verdict" == FALSE_POSITIVE* ]]; then
            detail="${verdict#FALSE_POSITIVE:}"
            echo "✗  FALSE_POSITIVE ($detail): '$old_var' → '$new_var'"
        else
            echo "?  UNKNOWN: $verdict"
        fi

        echo "$PROJECT,$bug_id,$changed_file,$old_var,$new_var,$verdict,$reads_above,$reads_below,\"$buggy_esc\",\"$fixed_esc\"" >> "$CSV"
        echo "$PROJECT,$bug_id,$verdict: '$old_var'→'$new_var' [$confidence|above:${reads_above}|below:${reads_below}] $changed_file" >> "$LOG"

    elif [[ "$result" == FALSE_POSITIVE:* ]]; then
        detail="${result#FALSE_POSITIVE:}"
        echo "✗  FALSE_POSITIVE ($detail) — filtered before context check"
        echo "$PROJECT,$bug_id,,$detail,,FALSE_POSITIVE,,,,," >> "$LOG"
        false_pos=$((false_pos + 1))

    elif [[ "$result" == SKIP:* ]]; then
        reason="${result#SKIP:}"
        echo "SKIP ($reason)"
        echo "$PROJECT,$bug_id,SKIP:$reason,,,,,,,," >> "$LOG"
    else
        echo "SKIP ($result)"
        echo "$PROJECT,$bug_id,SKIP:$result,,,,,,,," >> "$LOG"
    fi

done

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo "========================================"
echo "  ANALYSIS COMPLETE"
echo "========================================"
printf "  Project:         %s\n"    "$PROJECT"
printf "  Bugs checked:    %d\n"    "$checked"
printf "  CONFIRMED_DEAD:  %d  ← high confidence true positives\n" "$confirmed"
printf "  POSSIBLE_DEAD:   %d  ← review manually\n" "$possible"
printf "  Errors:          %d\n"    "$errors"
echo ""
echo "  CSV:  $CSV"
echo "  Log:  $LOG"
echo "========================================"
echo ""
echo "MANUAL REVIEW GUIDE:"
echo "  CONFIRMED_DEAD: new_var declared locally, not read above bug site."
echo "  POSSIBLE_DEAD:  new_var not read above but no local decl in context,"
echo "                  OR read exactly once above (may be loop cond only)."
echo "  For each, verify manually:"
echo "    1. Look at new_var's declaration — is it a local variable?"
echo "    2. Is new_var read ANYWHERE between declaration and bug line?"
echo "    3. If not → TRUE dead variable bug."
