import subprocess
import json
import os
import shutil

# --- Configuration ---
PROJECT = "Lang"
BUG_IDS = range(1, 66)
WORKSPACE_DIR = "/tmp/d4j_workspace"

# !!! UPDATE THESE PATHS TO MATCH YOUR SYSTEM !!!
D4J_EXEC = "/home/rajatvarshney/Documents/IPACO/Project2/defects4j/framework/bin/defects4j"
PMD_EXEC = "/home/rajatvarshney/Documents/IPACO/Project2/pmd-bin-7.23.0/bin/pmd" 

# Added UnusedAssignment: This is PMD's Data-Flow Analysis rule for dead stores.
PMD_RULES = (
    "category/java/bestpractices.xml/UnusedLocalVariable,"
    "category/java/bestpractices.xml/UnusedAssignment,"
    "category/java/bestpractices.xml/UnusedPrivateField"
)

def run_d4j_command(cmd_args, cwd=None):
    cmd = [D4J_EXEC] + cmd_args
    return subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)

def get_modified_classes(work_dir):
    result = run_d4j_command(["export", "-p", "classes.modified"], cwd=work_dir)
    if result.returncode == 0:
        return [line.strip() for line in result.stdout.split('\n') if line.strip()]
    return []

def get_source_dir(work_dir):
    result = run_d4j_command(["export", "-p", "dir.src.classes"], cwd=work_dir)
    if result.returncode == 0:
        return result.stdout.strip()
    return ""

def extract_code_line(filepath, line_number):
    """Reads the specific line of code from the Java file to eliminate manual lookups."""
    try:
        with open(filepath, 'r') as file:
            lines = file.readlines()
            # 0-indexed array, 1-indexed line numbers
            return lines[line_number - 1].strip()
    except Exception:
        return "<Could not extract code line>"

def run_pmd_analysis(source_dir):
    cmd = [
        PMD_EXEC, "check",
        "-d", source_dir,
        "-R", PMD_RULES,
        "-f", "json"
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    try:
        output = result.stdout
        json_start = output.find('{')
        if json_start != -1:
            report = json.loads(output[json_start:])
            return report.get('files', [])
        return []
    except json.JSONDecodeError:
        return []

def main():
    os.makedirs(WORKSPACE_DIR, exist_ok=True)
    results_log = {}

    print("Starting Smart Data-Flow Analysis with PMD...")

    for bug_id in BUG_IDS:
        target_dir = os.path.join(WORKSPACE_DIR, f"{PROJECT}_{bug_id}")
        
        # 1. Checkout (Suppressing heavy output)
        if os.path.exists(target_dir):
            shutil.rmtree(target_dir)
        checkout_res = run_d4j_command(["checkout", "-p", PROJECT, "-v", f"{bug_id}b", "-w", target_dir])
        
        if checkout_res.returncode != 0:
            continue
            
        # 2. Scope the analysis
        mod_classes = get_modified_classes(target_dir)
        src_dir_relative = get_source_dir(target_dir)
        full_src_dir = os.path.join(target_dir, src_dir_relative)
        
        if not mod_classes or not os.path.exists(full_src_dir):
            continue
            
        mod_file_paths = [cls.replace('.', '/') + ".java" for cls in mod_classes]
        
        # 3. Run Data-Flow Analysis
        violations = run_pmd_analysis(full_src_dir)
        
        bug_results = []
        for file_data in violations:
            filepath = file_data['filename']
            
            # 4. Filter and Extract
            if any(mod_path in filepath for mod_path in mod_file_paths):
                for violation in file_data.get('violations', []):
                    line_num = violation['beginline']
                    code_snippet = extract_code_line(filepath, line_num)
                    
                    bug_results.append({
                        "file": filepath.replace(target_dir, ""),
                        "line": line_num,
                        "rule": violation['rule'],
                        "code": code_snippet,
                        "description": violation['description'].strip()
                    })
        
        # 5. Output Findings Instantly
        if bug_results:
            print(f"\n[!] {PROJECT}-{bug_id} : Found {len(bug_results)} Data-Flow Anomaly(s) in modified files:")
            for res in bug_results:
                print(f"    Line {res['line']} | Rule: {res['rule']}")
                print(f"    Code : {res['code']}")
            results_log[f"{PROJECT}-{bug_id}"] = bug_results
        else:
            print(f"[{PROJECT}-{bug_id}] Clean.")

    # Save detailed JSON report
    output_file = f"{PROJECT}_smart_DFA_report.json"
    with open(output_file, "w") as f:
        json.dump(results_log, f, indent=4)
    print(f"\nAnalysis complete. Results saved to {output_file}")

if __name__ == "__main__":
    main()