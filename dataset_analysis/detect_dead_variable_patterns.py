#!/usr/bin/env python3
"""
Dead variable/assignment pattern miner for Defects4J.

This script mines diff-backed findings from buggy/fixed pairs already present in
`dataset_analysis/d4j_bugs` and Defects4J patch metadata in
`defects4j/framework/projects/*/patches/*.src.patch`.

Patterns covered:
1) WRONG_VAR_SUBSTITUTION_DEAD_ASSIGNMENT
2) UNUSED_LOCAL_VARIABLE
3) UNUSED_FORMAL_PARAMETER
4) ASSIGNED_THEN_OVERWRITTEN
5) PRIVATE_FIELD_ASSIGNED_NEVER_READ

Output:
- One consolidated CSV with pattern type, confidence, and evidence fields.
- Optional JSON mirror for debugging.
"""

import argparse
import csv
import json
import os
import re
from dataclasses import dataclass
from itertools import zip_longest
from typing import Dict, List, Optional, Set, Tuple


# Defects4J 2.0 projects and max bug IDs
D4J_PROJECTS: Dict[str, int] = {
    "Chart": 26,
    "Cli": 40,
    "Closure": 176,
    "Codec": 18,
    "Collections": 28,
    "Compress": 47,
    "Csv": 16,
    "Gson": 18,
    "JacksonCore": 26,
    "JacksonDatabind": 112,
    "JacksonXml": 6,
    "Jsoup": 93,
    "JxPath": 22,
    "Lang": 65,
    "Math": 106,
    "Mockito": 38,
    "Time": 27,
}

JAVA_KEYWORDS: Set[str] = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new", "package",
    "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "try", "void", "volatile", "while", "true", "false", "null",
}

JAVA_TYPE_PATTERN = re.compile(r"^[A-Z][a-zA-Z0-9_]*$")
JAVA_IDENT_PATTERN = re.compile(r"^[a-z_][a-zA-Z0-9_]*$")

# Local variable declaration (single variable declaration statements)
LOCAL_DECL_RE = re.compile(
    r"^\s*(?:final\s+)?[A-Za-z_][\w<>\[\], ?]*\s+([a-z_][a-zA-Z0-9_]*)\s*(?:=[^;]*)?;\s*$"
)

DECLARATION_LEAD_KEYWORDS = {
    "return",
    "throw",
    "if",
    "for",
    "while",
    "switch",
    "catch",
    "new",
}

ASSIGNMENT_RE = re.compile(
    r"^\s*(?:this\.)?([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:[+\-*/%&|^]?=)[^=]"
)

PRIVATE_FIELD_RE = re.compile(
    r"^\s*private\s+(?:static\s+)?(?:final\s+)?[A-Za-z_][\w<>\[\], ?]*\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*(?:=[^;]*)?;\s*$"
)


@dataclass
class LineEntry:
    line_no: int
    text: str


@dataclass
class Hunk:
    fixed_start: int
    buggy_start: int
    header_context: str
    removed: List[LineEntry]
    added: List[LineEntry]
    context_before: List[str]
    context_after: List[str]


@dataclass
class PatchFile:
    filename: str
    hunks: List[Hunk]


def is_java_identifier(token: str) -> bool:
    if not token or token in JAVA_KEYWORDS:
        return False
    if JAVA_TYPE_PATTERN.match(token):
        return False
    return bool(JAVA_IDENT_PATTERN.match(token))


def strip_literals(line: str) -> str:
    line = re.sub(r'"[^"\\]*(?:\\.[^"\\]*)*"', '""', line)
    line = re.sub(r"'[^'\\]*(?:\\.[^'\\]*)*'", "''", line)
    return line


def tokenize_java_line(line: str) -> List[str]:
    line = strip_literals(line)
    return re.findall(r"[a-zA-Z_][a-zA-Z0-9_]*", line)


def normalize_patch_file_path(path: str) -> str:
    path = path.strip()
    if path.startswith("a/") or path.startswith("b/"):
        return path[2:]
    return path


def parse_patch_file(path: str) -> List[PatchFile]:
    if not os.path.exists(path):
        return []

    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        lines = fh.readlines()

    patch_files: List[PatchFile] = []
    current_file: Optional[PatchFile] = None
    current_hunk: Optional[Hunk] = None
    fixed_line = 0
    buggy_line = 0

    for raw in lines:
        line = raw.rstrip("\n")

        if line.startswith("diff --git "):
            if current_file is not None:
                patch_files.append(current_file)
            parts = line.split(" b/")
            filename = ""
            if len(parts) >= 2:
                filename = normalize_patch_file_path(parts[-1].strip())
            current_file = PatchFile(filename=filename, hunks=[])
            current_hunk = None
            continue

        if line.startswith("--- ") or line.startswith("+++ "):
            continue

        if line.startswith("@@"):
            m = re.search(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)", line)
            if not m or current_file is None:
                continue
            fixed_start = int(m.group(1))
            buggy_start = int(m.group(3))
            fixed_line = fixed_start
            buggy_line = buggy_start
            current_hunk = Hunk(
                fixed_start=fixed_start,
                buggy_start=buggy_start,
                header_context=m.group(5).strip(),
                removed=[],
                added=[],
                context_before=[],
                context_after=[],
            )
            current_file.hunks.append(current_hunk)
            continue

        if current_hunk is None:
            continue

        if line.startswith(" "):
            content = line[1:]
            if not current_hunk.removed and not current_hunk.added:
                current_hunk.context_before.append(content)
            else:
                current_hunk.context_after.append(content)
            fixed_line += 1
            buggy_line += 1
        elif line.startswith("-"):
            current_hunk.removed.append(LineEntry(line_no=fixed_line, text=line[1:]))
            fixed_line += 1
        elif line.startswith("+"):
            current_hunk.added.append(LineEntry(line_no=buggy_line, text=line[1:]))
            buggy_line += 1

    if current_file is not None:
        patch_files.append(current_file)

    return patch_files


def read_lines(path: str) -> List[str]:
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.readlines()


def find_file_in_dir(base: str, relative_path: str) -> Optional[str]:
    p = os.path.join(base, relative_path)
    if os.path.exists(p):
        return p

    rp = relative_path.lstrip("/")
    p = os.path.join(base, rp)
    if os.path.exists(p):
        return p

    basename = os.path.basename(relative_path)
    for root, _, files in os.walk(base):
        if basename in files:
            return os.path.join(root, basename)
    return None


def extract_method_body(lines: List[str], target_line: int) -> Tuple[int, int, List[str]]:
    # Returns 1-indexed start/end lines and method body lines.
    method_start = max(0, target_line - 1)
    depth = 0
    found = False

    for i in range(target_line - 1, -1, -1):
        text = lines[i]
        for ch in reversed(text):
            if ch == '}':
                depth += 1
            elif ch == '{':
                depth -= 1
                if depth < 0:
                    method_start = i
                    found = True
                    break
        if found:
            break

    sig_start = method_start
    for i in range(method_start, max(-1, method_start - 8), -1):
        t = lines[i].strip()
        if "(" in t and re.search(r"(public|private|protected|static|final|synchronized|native|abstract)", t):
            sig_start = i
            break
        if "(" in t and ")" in t:
            sig_start = i
            break

    method_start = sig_start
    depth = 0
    started = False
    method_end = min(len(lines) - 1, target_line - 1)

    for i in range(method_start, len(lines)):
        for ch in lines[i]:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
                if started and depth == 0:
                    method_end = i
                    return method_start + 1, method_end + 1, lines[method_start:method_end + 1]

    s = max(0, target_line - 30)
    e = min(len(lines), target_line + 30)
    return s + 1, e, lines[s:e]


def get_method_parameters(method_lines: List[str]) -> Set[str]:
    header = ""
    for line in method_lines[:8]:
        header += line
        if "{" in line:
            break
    m = re.search(r"\(([^)]*)\)", header, re.DOTALL)
    if not m:
        return set()
    params = m.group(1)
    names = set()
    for part in params.split(","):
        toks = re.findall(r"[a-zA-Z_][a-zA-Z0-9_]*", part.strip())
        if toks:
            names.add(toks[-1])
    return names


def is_assignment_line(var: str, line: str) -> bool:
    return bool(re.match(rf"\s*(?:this\.)?{re.escape(var)}\s*(?:[+\-*/%&|^]?=)[^=]", line))


def is_declaration_line(var: str, line: str) -> bool:
    m = LOCAL_DECL_RE.match(line)
    return bool(m and m.group(1) == var)


def has_read_of_var(var: str, line: str) -> bool:
    text = strip_literals(line)
    if not re.search(rf"(?<![a-zA-Z0-9_]){re.escape(var)}(?![a-zA-Z0-9_])", text):
        return False
    if is_assignment_line(var, text):
        return False
    if is_declaration_line(var, text):
        return False
    if text.strip().startswith("//") or text.strip().startswith("/*") or text.strip().startswith("*"):
        return False
    return True


def find_single_substitution(removed_line: str, added_line: str) -> Optional[Tuple[str, str]]:
    removed_tokens = tokenize_java_line(removed_line)
    added_tokens = tokenize_java_line(added_line)
    if len(removed_tokens) != len(added_tokens):
        return None

    diffs: List[Tuple[str, str]] = []
    for r_tok, a_tok in zip(removed_tokens, added_tokens):
        if r_tok != a_tok:
            diffs.append((r_tok, a_tok))

    if len(diffs) != 1:
        return None

    correct_var, wrong_var = diffs[0]
    if not is_java_identifier(correct_var) or not is_java_identifier(wrong_var):
        return None

    call_pat = re.compile(rf"(?:^|[^a-zA-Z0-9_])({re.escape(correct_var)}|{re.escape(wrong_var)})\s*\(")
    if call_pat.search(removed_line) or call_pat.search(added_line):
        return None

    return correct_var, wrong_var


def detect_wrong_var_substitution(
    project: str,
    bug_id: int,
    file_rel: str,
    hunk: Hunk,
    buggy_lines: List[str],
) -> List[dict]:
    removed = [l for l in hunk.removed if l.text.strip()]
    added = [l for l in hunk.added if l.text.strip()]
    if not removed or len(removed) != len(added):
        return []

    substitution: Optional[Tuple[str, str]] = None
    buggy_line_entry: Optional[LineEntry] = None
    fixed_line_entry: Optional[LineEntry] = None

    for r, a in zip(removed, added):
        sub = find_single_substitution(r.text, a.text)
        if sub is None:
            return []
        if substitution is None:
            substitution = sub
            buggy_line_entry = a
            fixed_line_entry = r
        elif substitution != sub:
            return []

    if substitution is None or buggy_line_entry is None or fixed_line_entry is None:
        return []

    correct_var, wrong_var = substitution
    bug_line_no = buggy_line_entry.line_no

    method_start, _, method_lines = extract_method_body(buggy_lines, bug_line_no)
    bug_idx = bug_line_no - method_start
    if bug_idx < 0 or bug_idx >= len(method_lines):
        return []

    params = get_method_parameters(method_lines)
    if correct_var in params:
        return []

    assign_line = None
    for i in range(bug_idx - 1, -1, -1):
        line = method_lines[i]
        if is_assignment_line(correct_var, line) or is_declaration_line(correct_var, line):
            assign_line = method_start + i
            break

    if assign_line is None:
        return []

    reads_between = 0
    for i in range(assign_line - method_start + 1, bug_idx):
        if i < 0 or i >= len(method_lines):
            continue
        if has_read_of_var(correct_var, method_lines[i]):
            reads_between += 1

    bug_line_text = method_lines[bug_idx]
    if is_assignment_line(wrong_var, bug_line_text):
        return []

    if reads_between != 0:
        return []

    return [{
        "Project": project,
        "BugID": bug_id,
        "BugKey": f"{project}-{bug_id}",
        "File": file_rel,
        "PatternType": "WRONG_VAR_SUBSTITUTION_DEAD_ASSIGNMENT",
        "Confidence": "DIFF_CONFIRMED",
        "EvidenceSource": "patch+hunk+buggy_liveness",
        "BuggyLine": bug_line_no,
        "FixedLine": fixed_line_entry.line_no,
        "AssignmentLine": assign_line,
        "OverwriteLine": "",
        "CorrectSymbol": correct_var,
        "WrongSymbol": wrong_var,
        "BuggySnippet": buggy_line_entry.text.strip(),
        "FixedSnippet": fixed_line_entry.text.strip(),
        "DiffHunk": hunk.header_context,
        "Reason": (
            f"'{correct_var}' assigned at line {assign_line} and never read before line {bug_line_no}; "
            f"buggy code reads '{wrong_var}' instead."
        ),
    }]


def detect_unused_local_variables(
    project: str,
    bug_id: int,
    file_rel: str,
    hunk: Hunk,
    buggy_lines: List[str],
) -> List[dict]:
    findings: List[dict] = []

    for idx, added in enumerate(hunk.added):
        text = added.text.strip()
        m = LOCAL_DECL_RE.match(text)
        if not m:
            continue
        var = m.group(1)
        if not is_java_identifier(var):
            continue

        line_tokens = tokenize_java_line(text)
        if line_tokens and line_tokens[0] in DECLARATION_LEAD_KEYWORDS:
            continue

        method_start, _, method_lines = extract_method_body(buggy_lines, added.line_no)
        rel_idx = added.line_no - method_start
        if rel_idx < 0 or rel_idx >= len(method_lines):
            continue

        params = get_method_parameters(method_lines)
        if var in params:
            continue

        reads = 0
        for i, line in enumerate(method_lines):
            if i == rel_idx:
                continue
            if has_read_of_var(var, line):
                reads += 1

        if reads != 0:
            continue

        paired_removed = hunk.removed[idx].text.strip() if idx < len(hunk.removed) else ""

        findings.append({
            "Project": project,
            "BugID": bug_id,
            "BugKey": f"{project}-{bug_id}",
            "File": file_rel,
            "PatternType": "UNUSED_LOCAL_VARIABLE",
            "Confidence": "DIFF_CONFIRMED",
            "EvidenceSource": "patch+hunk+buggy_method_scan",
            "BuggyLine": added.line_no,
            "FixedLine": hunk.removed[idx].line_no if idx < len(hunk.removed) else "",
            "AssignmentLine": added.line_no,
            "OverwriteLine": "",
            "CorrectSymbol": "",
            "WrongSymbol": var,
            "BuggySnippet": text,
            "FixedSnippet": paired_removed,
            "DiffHunk": hunk.header_context,
            "Reason": f"Local variable '{var}' is declared in changed buggy code and has no reads in its method.",
        })

    return findings


def detect_unused_formal_parameters(
    project: str,
    bug_id: int,
    file_rel: str,
    hunk: Hunk,
    buggy_lines: List[str],
) -> List[dict]:
    findings: List[dict] = []
    seen: Set[Tuple[int, str]] = set()

    for removed, added in zip_longest(hunk.removed, hunk.added):
        if removed is None or added is None:
            continue

        method_start, _, method_lines = extract_method_body(buggy_lines, added.line_no)
        params = get_method_parameters(method_lines)
        if not params:
            continue

        removed_tokens = set(tokenize_java_line(removed.text))
        added_tokens = set(tokenize_java_line(added.text))
        introduced_in_fixed = [t for t in (removed_tokens - added_tokens) if t in params and is_java_identifier(t)]

        for param in introduced_in_fixed:
            key = (method_start, param)
            if key in seen:
                continue

            read_count = 0
            for ln in method_lines:
                if has_read_of_var(param, ln):
                    read_count += 1

            if read_count != 0:
                continue

            seen.add(key)
            findings.append({
                "Project": project,
                "BugID": bug_id,
                "BugKey": f"{project}-{bug_id}",
                "File": file_rel,
                "PatternType": "UNUSED_FORMAL_PARAMETER",
                "Confidence": "DIFF_CONFIRMED",
                "EvidenceSource": "patch+fixed_use_delta+buggy_method_scan",
                "BuggyLine": added.line_no,
                "FixedLine": removed.line_no,
                "AssignmentLine": "",
                "OverwriteLine": "",
                "CorrectSymbol": param,
                "WrongSymbol": "",
                "BuggySnippet": added.text.strip(),
                "FixedSnippet": removed.text.strip(),
                "DiffHunk": hunk.header_context,
                "Reason": (
                    f"Formal parameter '{param}' is unused in buggy method; fixed-side changed line introduces its usage."
                ),
            })

    return findings


def detect_assigned_then_overwritten(
    project: str,
    bug_id: int,
    file_rel: str,
    hunk: Hunk,
    buggy_lines: List[str],
) -> List[dict]:
    findings: List[dict] = []

    for added in hunk.added:
        m = ASSIGNMENT_RE.match(added.text)
        if not m:
            continue
        var = m.group(1)
        if not is_java_identifier(var):
            continue

        method_start, _, method_lines = extract_method_body(buggy_lines, added.line_no)
        rel_idx = added.line_no - method_start
        if rel_idx < 0 or rel_idx >= len(method_lines):
            continue

        first_overwrite_idx = None
        seen_read = False

        for i in range(rel_idx + 1, len(method_lines)):
            line = method_lines[i]
            if has_read_of_var(var, line):
                seen_read = True
                break
            if is_assignment_line(var, line):
                first_overwrite_idx = i
                break

        if seen_read or first_overwrite_idx is None:
            continue

        overwrite_line = method_start + first_overwrite_idx

        findings.append({
            "Project": project,
            "BugID": bug_id,
            "BugKey": f"{project}-{bug_id}",
            "File": file_rel,
            "PatternType": "ASSIGNED_THEN_OVERWRITTEN",
            "Confidence": "DIFF_CONFIRMED",
            "EvidenceSource": "patch+hunk+buggy_method_flow",
            "BuggyLine": added.line_no,
            "FixedLine": "",
            "AssignmentLine": added.line_no,
            "OverwriteLine": overwrite_line,
            "CorrectSymbol": "",
            "WrongSymbol": var,
            "BuggySnippet": added.text.strip(),
            "FixedSnippet": "",
            "DiffHunk": hunk.header_context,
            "Reason": (
                f"Variable '{var}' assigned at line {added.line_no} and overwritten at line {overwrite_line} before any read."
            ),
        })

    return findings


def collect_private_fields(lines: List[str]) -> Set[str]:
    fields: Set[str] = set()
    for line in lines:
        m = PRIVATE_FIELD_RE.match(line.strip())
        if m:
            fields.add(m.group(1))
    return fields


def detect_private_field_assigned_never_read(
    project: str,
    bug_id: int,
    file_rel: str,
    hunk: Hunk,
    buggy_lines: List[str],
    private_fields: Set[str],
) -> List[dict]:
    findings: List[dict] = []
    if not private_fields:
        return findings

    for added in hunk.added:
        m = ASSIGNMENT_RE.match(added.text)
        if not m:
            continue
        field = m.group(1)
        if field not in private_fields:
            continue

        reads = 0
        for line in buggy_lines:
            if has_read_of_var(field, line):
                reads += 1

        if reads != 0:
            continue

        findings.append({
            "Project": project,
            "BugID": bug_id,
            "BugKey": f"{project}-{bug_id}",
            "File": file_rel,
            "PatternType": "PRIVATE_FIELD_ASSIGNED_NEVER_READ",
            "Confidence": "DIFF_CONFIRMED",
            "EvidenceSource": "patch+hunk+buggy_class_scan",
            "BuggyLine": added.line_no,
            "FixedLine": "",
            "AssignmentLine": added.line_no,
            "OverwriteLine": "",
            "CorrectSymbol": "",
            "WrongSymbol": field,
            "BuggySnippet": added.text.strip(),
            "FixedSnippet": "",
            "DiffHunk": hunk.header_context,
            "Reason": f"Private field '{field}' is assigned in changed buggy code and has no reads in class file.",
        })

    return findings


def deduplicate_findings(findings: List[dict]) -> List[dict]:
    out: List[dict] = []
    seen: Set[Tuple] = set()

    for f in findings:
        key = (
            f["Project"],
            f["BugID"],
            f["File"],
            f["PatternType"],
            f["BuggyLine"],
            f["WrongSymbol"],
            f["CorrectSymbol"],
        )
        if key in seen:
            continue
        seen.add(key)
        out.append(f)

    out.sort(key=lambda x: (x["Project"], int(x["BugID"]), x["File"], str(x["BuggyLine"]), x["PatternType"]))
    return out


def bug_range(max_bug: int, limit: int) -> range:
    if limit > 0:
        return range(1, min(max_bug, limit) + 1)
    return range(1, max_bug + 1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Detect dead variable/assignment patterns from Defects4J patches.")
    parser.add_argument(
        "--projects",
        default="",
        help="Comma-separated Defects4J projects to scan (default: auto-detect from d4j_bugs).",
    )
    parser.add_argument(
        "--limit-bugs",
        type=int,
        default=0,
        help="Optional per-project bug limit for quick runs (0 = full).",
    )
    parser.add_argument(
        "--output-csv",
        default="dead_variable_patterns_report.csv",
        help="Output CSV file name (created in dataset_analysis by default if relative).",
    )
    parser.add_argument(
        "--output-json",
        default="",
        help="Optional JSON output path.",
    )

    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    root_dir = os.path.abspath(os.path.join(script_dir, ".."))
    d4j_bugs_dir = os.path.join(script_dir, "d4j_bugs")
    d4j_projects_dir = os.path.join(root_dir, "defects4j", "framework", "projects")

    output_csv = args.output_csv
    if not os.path.isabs(output_csv):
        output_csv = os.path.join(script_dir, output_csv)

    output_json = args.output_json.strip()
    if output_json and not os.path.isabs(output_json):
        output_json = os.path.join(script_dir, output_json)

    if args.projects.strip():
        project_names = [p.strip() for p in args.projects.split(",") if p.strip()]
    else:
        project_names = []
        if os.path.isdir(d4j_bugs_dir):
            for name in sorted(os.listdir(d4j_bugs_dir)):
                if name in D4J_PROJECTS and os.path.isdir(os.path.join(d4j_bugs_dir, name)):
                    project_names.append(name)

    findings: List[dict] = []
    scanned_patches = 0
    skipped_missing_assets = 0

    for project in project_names:
        if project not in D4J_PROJECTS:
            continue

        max_bug = D4J_PROJECTS[project]
        for bug_id in bug_range(max_bug, args.limit_bugs):
            patch_path = os.path.join(d4j_projects_dir, project, "patches", f"{bug_id}.src.patch")
            if not os.path.exists(patch_path):
                continue

            buggy_dir = os.path.join(d4j_bugs_dir, project, f"{project}_{bug_id}_buggy")
            fixed_dir = os.path.join(d4j_bugs_dir, project, f"{project}_{bug_id}_fixed")
            if not os.path.isdir(buggy_dir) or not os.path.isdir(fixed_dir):
                skipped_missing_assets += 1
                continue

            patch_files = parse_patch_file(patch_path)
            if not patch_files:
                continue

            scanned_patches += 1

            for patch_file in patch_files:
                file_rel = normalize_patch_file_path(patch_file.filename)
                buggy_file = find_file_in_dir(buggy_dir, file_rel)
                fixed_file = find_file_in_dir(fixed_dir, file_rel)
                if not buggy_file or not fixed_file:
                    skipped_missing_assets += 1
                    continue

                buggy_lines = read_lines(buggy_file)
                if not buggy_lines:
                    continue

                private_fields = collect_private_fields(buggy_lines)

                for hunk in patch_file.hunks:
                    findings.extend(
                        detect_wrong_var_substitution(project, bug_id, file_rel, hunk, buggy_lines)
                    )
                    findings.extend(
                        detect_unused_local_variables(project, bug_id, file_rel, hunk, buggy_lines)
                    )
                    findings.extend(
                        detect_unused_formal_parameters(project, bug_id, file_rel, hunk, buggy_lines)
                    )
                    findings.extend(
                        detect_assigned_then_overwritten(project, bug_id, file_rel, hunk, buggy_lines)
                    )
                    findings.extend(
                        detect_private_field_assigned_never_read(
                            project,
                            bug_id,
                            file_rel,
                            hunk,
                            buggy_lines,
                            private_fields,
                        )
                    )

    findings = deduplicate_findings(findings)

    headers = [
        "Project",
        "BugID",
        "BugKey",
        "File",
        "PatternType",
        "Confidence",
        "EvidenceSource",
        "BuggyLine",
        "FixedLine",
        "AssignmentLine",
        "OverwriteLine",
        "CorrectSymbol",
        "WrongSymbol",
        "BuggySnippet",
        "FixedSnippet",
        "DiffHunk",
        "Reason",
    ]

    with open(output_csv, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=headers)
        writer.writeheader()
        for row in findings:
            writer.writerow(row)

    if output_json:
        with open(output_json, "w", encoding="utf-8") as fh:
            json.dump(findings, fh, indent=2)

    by_pattern: Dict[str, int] = {}
    by_project: Dict[str, int] = {}
    for f in findings:
        by_pattern[f["PatternType"]] = by_pattern.get(f["PatternType"], 0) + 1
        by_project[f["Project"]] = by_project.get(f["Project"], 0) + 1

    print("=" * 72)
    print("Dead Variable/Assignment Pattern Mining Complete")
    print("=" * 72)
    print(f"Scanned patch sets: {scanned_patches}")
    print(f"Skipped assets (missing file/checkout pieces): {skipped_missing_assets}")
    print(f"Total findings: {len(findings)}")
    print(f"CSV output: {output_csv}")
    if output_json:
        print(f"JSON output: {output_json}")

    print("\nFindings by pattern:")
    for pattern in sorted(by_pattern.keys()):
        print(f"  {pattern}: {by_pattern[pattern]}")

    print("\nFindings by project:")
    for project in sorted(by_project.keys()):
        print(f"  {project}: {by_project[project]}")


if __name__ == "__main__":
    main()
