# Defects4J Dead Variable Analysis

> **IPACO Course Project** — Intra-Procedural Static Analysis for Detecting Dead Variables in Java Programs

## 📋 Problem Statement

A **dead variable** bug occurs when a variable is assigned a value but never read before being overwritten or the function exits — indicating the wrong variable is being used downstream. This is a critical bug category for Automated Program Repair (APR).

### Example (from Project Spec)

```java
public Paint getPaint(double value) {
    double v = Math.max(value, this.lowerBound);
    v = Math.min(v, this.upperBound);           // v is correctly clamped
    int g = (int) ((value - this.lowerBound) /   // BUG: uses 'value' not 'v'
            (this.upperBound - this.lowerBound) * 255.0);
    return new Color(g, g, g);                   // throws if g outside [0,255]
}
```

Here `v` at line 3 is **dead** — it is defined but never read again. The bug is that `value` was used instead of `v`.

## 🎯 Project Tasks

| Task | Description | Status |
|------|-------------|--------|
| **Task 1** — Dataset Analysis | Analyze Defects4J 2.0 (~854 bugs across 17 projects) to find all bugs matching the dead-variable pattern | ✅ Completed |
| **Task 2** — Implement Analysis Pass | Build a Soot/Jimple analysis pass for automatic dead variable detection | 🔲 Planned |
| **Task 3** — Evaluation | Evaluate the solution's precision and recall against the dataset | 🔲 Planned |

## 📁 Project Structure

```
.
├── README.md                       # This file
├── IPACO_Project_2.pdf             # Problem statement
├── implementation_plan.md          # Detailed 5-phase implementation plan
├── dataset_analysis/               # Task 1: Dataset analysis scripts & results
│   ├── dead_variable_detection.py  # Main: PMD-based analysis across ALL 17 Defects4J projects
│   ├── mine_dead_variable.py       # Prototype: PMD DFA analysis on single project (Lang)
│   ├── analyze_defects4j.sh        # Advanced: Heuristic-based dead-var detection via diff analysis
│   ├── defects4j_dead_assignments_report.csv  # Full results: 782 PMD violations across all projects
│   ├── Lang_smart_DFA_report.json  # Detailed PMD DFA report for the Lang project
│   ├── Lang_dead_vars_report.json  # Filtered dead variable findings for Lang
│   └── analysis.log                # Shell script analysis log (Lang, 64 bugs)
├── defects4j/                      # Defects4J framework (not tracked — see Setup)
└── pmd-bin-7.23.0/                 # PMD static analysis tool (not tracked — see Setup)
```

## 🛠️ Task 1: Dataset Analysis (Completed)

### Approaches

The dataset analysis uses a **two-pronged approach** to identify dead variable bugs:

#### 1. Heuristic Diff-Based Analysis (`analyze_defects4j.sh`)
- Analyzes bug-fix diffs to find single-variable substitution patterns
- Multi-step verification pipeline:
  1. **Substitution Detection** — finds `old_var → new_var` replacements in diffs
  2. **Read-Site Verification** — confirms the bug line is a read (not write) site
  3. **Local Variable Check** — ensures `new_var` is a local variable, not a parameter
  4. **Liveness Analysis** — counts reads of `new_var` above/below the bug line
  5. **Confidence Classification** — `CONFIRMED_DEAD` / `POSSIBLE_DEAD` / `FALSE_POSITIVE`

However, this approach is not very efficient as it requires manual verification of each bug, also it inlcudes lot of false positives and is not scalable to a large number of bugs. So we need a more efficient approach. 

#### 2. PMD-Based Static Analysis (`dead_variable_detection.py`)
- Iterates over all **854 bugs across 17 Defects4J 2.0 projects**
- Checks out each buggy version, identifies modified classes
- Runs PMD's data-flow analysis rules:
  - `UnusedAssignment` — detects dead stores via DFA
  - `UnusedLocalVariable` — detects variables never read
  - `UnusedFormalParameter` — detects unused parameters
  - `UnusedPrivateField` — detects unused fields
- Scopes analysis to **only the modified files** (bug-relevant code)
- Outputs a comprehensive CSV report with file, line, rule, code snippet, and description


### Key Results

| Metric | Value |
|--------|-------|
| Projects Analyzed | 17 (Chart, Cli, Closure, Codec, Collections, Compress, Csv, Gson, JacksonCore, JacksonDatabind, JacksonXml, Jsoup, JxPath, Lang, Math, Mockito, Time) |
| Total Bugs Scanned | 854 |
| PMD Violations Found | 782 (scoped to modified files) |

## ⚙️ Prerequisites

- **Java 1.8** (OpenJDK 1.8+)
- **Python 3.8+**
- **Defects4J** (v2.0+) — [Installation Guide](https://github.com/rjust/defects4j)
- **PMD** (v7.x) — [Download](https://pmd.github.io/)
- **Perl** + `cpanminus` (for Defects4J)
- **Subversion** (`svn`)

## 🚀 Setup

### 1. Clone this Repository

```bash
git clone https://github.com/<your-username>/defects4j-dead-variable-analysis.git
cd defects4j-dead-variable-analysis
```

### 2. Install Defects4J

```bash
git clone https://github.com/rjust/defects4j
cd defects4j
cpanm --installdeps .
./init.sh
export PATH=$PATH:$(pwd)/framework/bin
cd ..
```

### 3. Install PMD

```bash
wget https://github.com/pmd/pmd/releases/download/pmd_releases%2F7.23.0/pmd-dist-7.23.0-bin.zip
unzip pmd-dist-7.23.0-bin.zip
```

### 4. Update Paths

Edit the following files and update the `D4J_EXEC` and `PMD_EXEC` paths to match your system:
- `dataset_analysis/dead_variable_detection.py` (lines 12-13)
- `dataset_analysis/mine_dead_variable.py` (lines 12-13)
- `dataset_analysis/analyze_defects4j.sh` (line 31)

## 📊 Usage

### Run PMD Analysis (All Projects)

```bash
cd dataset_analysis
python3 dead_variable_detection.py
```

This generates `defects4j_dead_assignments_report.csv` with all violations.

### Run Single-Project PMD Mining

```bash
cd dataset_analysis
python3 mine_dead_variable.py
```

Generates a detailed JSON report for the configured project (default: Lang).

##  Author

Rajat Varshney
