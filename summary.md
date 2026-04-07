# Project2 Progress Summary

## Scope Covered So Far

This summary captures all major work completed in the workspace up to now for dead variable/dead assignment mining on Defects4J local data.

## 1. New Multi-Pattern Detector Implemented

Created:

- `dataset_analysis/detect_dead_variable_patterns.py`

What it does:

- Mines diff-backed dead-variable/dead-assignment findings from local Defects4J buggy/fixed pairs.
- Uses patch metadata and local source analysis.
- Detects these pattern types:
  - `WRONG_VAR_SUBSTITUTION_DEAD_ASSIGNMENT`
  - `UNUSED_LOCAL_VARIABLE`
  - `UNUSED_FORMAL_PARAMETER`
  - `ASSIGNED_THEN_OVERWRITTEN`
  - `PRIVATE_FIELD_ASSIGNED_NEVER_READ`
- Produces one consolidated CSV with pattern type, confidence, and evidence source.

Key behavior updates made during iteration:

- Fixed false positive in unused-local detection (e.g., `return null;` was being incorrectly interpreted as declaration).
- Added local project auto-detection from `dataset_analysis/d4j_bugs` when `--projects` is not provided.

## 2. Detector Validation and Execution

Performed checks:

- Syntax validation via `python3 -m py_compile`.
- Smoke and targeted runs.

Observed run outcomes (examples):

- `--projects Cli --output-csv dead_variable_patterns_report.cli.csv`
  - Scanned: 39 patch sets
  - Findings: 1 (after false-positive fix)
- Default auto-detect mode with bounded run:
  - Produced findings successfully (example included `ASSIGNED_THEN_OVERWRITTEN` on Codec sample)

Generated outputs:

- `dataset_analysis/dead_variable_patterns_report.sample.csv`
- `dataset_analysis/dead_variable_patterns_report.cli.csv`
- `dataset_analysis/dead_variable_patterns_report.smoke.csv`
- `dataset_analysis/dead_variable_patterns_report.csv` (full run output used later for reverification)

## 3. Full Reverification of Reported Findings

Input checked:

- `dataset_analysis/dead_variable_patterns_report.csv`

Action done:

- Independently re-verified each reported row directly against buggy/fixed source files.
- Applied pattern-specific checks (line presence, assignment/read constraints, symbol use checks).

Reverification result:

- Total rows checked: 32
- Verified present: 32
- Not verified: 0

Breakdown:

- `WRONG_VAR_SUBSTITUTION_DEAD_ASSIGNMENT`: 1/1
- `UNUSED_LOCAL_VARIABLE`: 15/15
- `ASSIGNED_THEN_OVERWRITTEN`: 15/15
- `PRIVATE_FIELD_ASSIGNED_NEVER_READ`: 1/1

Generated reverification artifact:

- `dataset_analysis/dead_variable_patterns_reverification.csv`

## 4. Workspace/Context Inventory Provided

A concise PROJECT2 directory map and LLM-ready context summary were prepared, based on local inspection of:

- Root workspace folders
- `dataset_analysis` contents
- `defects4j` framework and local repos
- `pmd-bin-7.23.0`
- Local project availability under `dataset_analysis/d4j_bugs`

## 5. Rewritten General PMD Script (Project-Wide, Local-Only)

Modified:

- `dataset_analysis/dead_variable_detection_general.py`

Old behavior removed:

- Patch-based strict filtering.
- Defects4J checkout per bug.

New behavior implemented:

- Scans only local projects present in `dataset_analysis/d4j_bugs`.
- For each project, selects the first buggy folder (`<Project>_<minBugId>_buggy`).
- Auto-discovers Java source root (priority):
  - `source`
  - `src/main/java`
  - `src/java`
  - `src/main`
  - `src`
- Runs PMD once per selected project with same rules:
  - `UnusedAssignment`
  - `UnusedLocalVariable`
  - `UnusedFormalParameter`
  - `UnusedPrivateField`
- Writes consolidated CSV rows with schema:
  - `Project, BugID, File, Line, Rule, Code_Snippet, Description`

Validation run (sample):

- Command:
  - `cd dataset_analysis && python3 -m py_compile dead_variable_detection_general.py && python3 dead_variable_detection_general.py --projects Cli,Lang --output defects4j_dead_assignments_general_report.sample.csv`
- Result:
  - `Cli-1`: 7 violations
  - `Lang-1`: 44 violations
  - Total: 51

Generated sample output:

- `dataset_analysis/defects4j_dead_assignments_general_report.sample.csv`

## 6. Current Status

- Multi-pattern diff-backed detector is implemented and validated.
- Full report reverification is complete with 100% verification for current report rows.
- General PMD script now supports project-wide local scans (no patch dependency).
- Core outputs and sample reports are available in `dataset_analysis`.

## 7. Suggested Next Steps

1. Run full local-project scan using `dead_variable_detection_general.py` without `--projects` and store final CSV.
2. Add optional parallel execution for PMD runs across projects to reduce runtime.
3. Add a compact per-project/per-rule aggregate report (`counts only`) alongside detailed CSV.
