import argparse
import csv
import json
import os
import re
import subprocess


BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(BASE_DIR, ".."))
D4J_BUGS_DIR = os.path.join(BASE_DIR, "d4j_bugs")
PMD_EXEC = os.path.join(PROJECT_ROOT, "pmd-bin-7.23.0", "bin", "pmd")
CSV_OUTPUT_FILE = "defects4j_dead_assignments_general_report.csv"

PMD_RULES = (
    "category/java/bestpractices.xml/UnusedAssignment,"
    "category/java/bestpractices.xml/UnusedLocalVariable,"
    "category/java/bestpractices.xml/UnusedFormalParameter,"
    "category/java/bestpractices.xml/UnusedPrivateField"
)

BUGGY_DIR_PATTERN = re.compile(r"^([A-Za-z0-9]+)_(\d+)_buggy$")


def extract_code_line(filepath, line_number):
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as file:
            return file.readlines()[line_number - 1].strip()
    except Exception:
        return "<Could not extract code line>"


def has_java_files(root_dir):
    for root, _, files in os.walk(root_dir):
        for name in files:
            if name.endswith(".java"):
                return True
    return False


def discover_source_dir(buggy_dir):
    candidates = ["source", "src/main/java", "src/java", "src/main", "src"]
    for rel in candidates:
        abs_dir = os.path.join(buggy_dir, rel)
        if os.path.isdir(abs_dir) and has_java_files(abs_dir):
            return abs_dir

    if has_java_files(buggy_dir):
        return buggy_dir
    return None


def find_first_buggy_folder(project_dir, project_name):
    best_id = None
    best_path = None

    for entry in os.listdir(project_dir):
        full = os.path.join(project_dir, entry)
        if not os.path.isdir(full):
            continue

        m = BUGGY_DIR_PATTERN.match(entry)
        if not m:
            continue
        if m.group(1) != project_name:
            continue

        bug_id = int(m.group(2))
        if best_id is None or bug_id < best_id:
            best_id = bug_id
            best_path = full

    return best_id, best_path


def run_pmd_analysis(source_dir):
    cmd = [PMD_EXEC, "check", "-d", source_dir, "-R", PMD_RULES, "-f", "json"]
    result = subprocess.run(cmd, capture_output=True, text=True)

    output = result.stdout or ""
    json_start = output.find("{")
    if json_start == -1:
        return []

    try:
        report = json.loads(output[json_start:])
        return report.get("files", [])
    except json.JSONDecodeError:
        return []


def list_local_projects(project_filter):
    if not os.path.isdir(D4J_BUGS_DIR):
        return []

    project_names = []
    for entry in sorted(os.listdir(D4J_BUGS_DIR)):
        full = os.path.join(D4J_BUGS_DIR, entry)
        if os.path.isdir(full):
            project_names.append(entry)

    if not project_filter:
        return project_names

    keep = {p.strip() for p in project_filter.split(",") if p.strip()}
    return [p for p in project_names if p in keep]


def main():
    parser = argparse.ArgumentParser(
        description="Run PMD dead variable/assignment rules on one local buggy checkout per project."
    )
    parser.add_argument(
        "--projects",
        default="",
        help="Comma-separated project names from d4j_bugs to process (default: all present).",
    )
    parser.add_argument(
        "--output",
        default=CSV_OUTPUT_FILE,
        help="Output CSV file name (default: defects4j_dead_assignments_general_report.csv).",
    )
    args = parser.parse_args()

    output_csv = args.output
    if not os.path.isabs(output_csv):
        output_csv = os.path.join(BASE_DIR, output_csv)

    csv_headers = ["Project", "BugID", "File", "Line", "Rule", "Code_Snippet", "Description"]
    with open(output_csv, mode="w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
        writer.writeheader()

    if not os.path.exists(PMD_EXEC):
        print(f"❌ PMD executable not found: {PMD_EXEC}")
        return

    projects = list_local_projects(args.projects)
    if not projects:
        print(f"❌ No projects found in {D4J_BUGS_DIR}")
        return

    print(f"Starting project-wide PMD scan from local buggy folders. Output: {output_csv}\n")

    total_rows = 0
    for project in projects:
        project_dir = os.path.join(D4J_BUGS_DIR, project)
        if not os.path.isdir(project_dir):
            continue

        bug_id, buggy_dir = find_first_buggy_folder(project_dir, project)
        if buggy_dir is None:
            print(f"=== {project}: skipped (no *_buggy folder found) ===")
            continue

        src_dir = discover_source_dir(buggy_dir)
        if src_dir is None:
            print(f"=== {project}-{bug_id}: skipped (no Java source directory found) ===")
            continue

        print(f"=== Processing {project}-{bug_id} (source: {src_dir}) ===")
        violations = run_pmd_analysis(src_dir)

        rows = []
        for file_data in violations:
            abs_filepath = file_data.get("filename", "")
            rel_filepath = os.path.relpath(abs_filepath, buggy_dir) if abs_filepath else ""
            for violation in file_data.get("violations", []):
                line_num = violation.get("beginline", "")
                rows.append({
                    "Project": project,
                    "BugID": bug_id,
                    "File": rel_filepath,
                    "Line": line_num,
                    "Rule": violation.get("rule", ""),
                    "Code_Snippet": extract_code_line(abs_filepath, line_num) if isinstance(line_num, int) else "",
                    "Description": str(violation.get("description", "")).strip(),
                })

        if rows:
            with open(output_csv, mode="a", newline="", encoding="utf-8") as csv_file:
                writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
                writer.writerows(rows)
            print(f"  -> Found {len(rows)} violation(s)")
            total_rows += len(rows)
        else:
            print("  -> No violations found")

    print(f"\n✅ Analysis complete. Total violations: {total_rows}")
    print(f"✅ CSV saved to: {output_csv}")


if __name__ == "__main__":
    main()