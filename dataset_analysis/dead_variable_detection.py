import subprocess
import json
import os
import shutil
import csv

# --- Configuration ---
WORKSPACE_DIR = "/tmp/d4j_workspace"
CSV_OUTPUT_FILE = "defects4j_dead_assignments_report.csv"

# !!! UPDATE THESE PATHS TO MATCH YOUR SYSTEM !!!
D4J_EXEC = "/home/rajatvarshney/Documents/IPACO/Project2/defects4j/framework/bin/defects4j"
PMD_EXEC = "/home/rajatvarshney/Documents/IPACO/Project2/pmd-bin-7.23.0/bin/pmd" 

# Defects4J 2.0 Project Dictionary (Project Name : Number of Bugs)
# Note: You can comment out projects if you want to run them in smaller batches.
# Defects4J 2.0 Complete Project Dictionary
# Values represent the MAXIMUM bug ID to ensure the loop reaches the end.
# The script will automatically skip deprecated bugs (e.g., Lang-2, Time-21).
D4J_PROJECTS = {
    "Chart": 26,
    "Cli": 40,              # Max ID is 40 (Bug 6 is deprecated)
    "Closure": 176,         # Max ID is 176 (Bugs 63, 93 are deprecated)
    "Codec": 18,
    "Collections": 28,      # Max ID is 28 (Bugs 1-24 are deprecated)
    "Compress": 47,
    "Csv": 16,
    "Gson": 18,
    "JacksonCore": 26,
    "JacksonDatabind": 112,
    "JacksonXml": 6,
    "Jsoup": 93,
    "JxPath": 22,
    "Lang": 65,             # Max ID is 65 (Bug 2 is deprecated)
    "Math": 106,
    "Mockito": 38,
    "Time": 27              # Max ID is 27 (Bug 21 is deprecated)
}

# The Compiler-Level and DFA Rules
PMD_RULES = (
    "category/java/bestpractices.xml/UnusedAssignment,"
    "category/java/bestpractices.xml/UnusedLocalVariable,"
    "category/java/bestpractices.xml/UnusedFormalParameter,"
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
    try:
        with open(filepath, 'r') as file:
            lines = file.readlines()
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

    # Initialize CSV File and Write Headers
    csv_headers = ["Project", "BugID", "File", "Line", "Rule", "Code_Snippet", "Description"]
    with open(CSV_OUTPUT_FILE, mode='w', newline='', encoding='utf-8') as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
        writer.writeheader()

    print(f"Starting Universal Data-Flow Analysis. Outputting to {CSV_OUTPUT_FILE}\n")

    # Iterate through all configured projects
    for project, total_bugs in D4J_PROJECTS.items():
        print(f"=== Processing Project: {project} ({total_bugs} bugs) ===")
        
        for bug_id in range(1, total_bugs + 1):
            target_dir = os.path.join(WORKSPACE_DIR, f"{project}_{bug_id}")
            
            # 1. Clean previous checkout and initialize new one
            if os.path.exists(target_dir):
                shutil.rmtree(target_dir)
            
            checkout_res = run_d4j_command(["checkout", "-p", project, "-v", f"{bug_id}b", "-w", target_dir])
            if checkout_res.returncode != 0:
                print(f"  [!] Failed to checkout {project}-{bug_id}. Skipping.")
                continue
                
            # 2. Scope the analysis
            mod_classes = get_modified_classes(target_dir)
            src_dir_relative = get_source_dir(target_dir)
            
            if not mod_classes or not src_dir_relative:
                continue
                
            full_src_dir = os.path.join(target_dir, src_dir_relative)
            if not os.path.exists(full_src_dir):
                continue
                
            mod_file_paths = [cls.replace('.', '/') + ".java" for cls in mod_classes]
            
            # 3. Run Analysis
            violations = run_pmd_analysis(full_src_dir)
            
            bug_results = []
            for file_data in violations:
                filepath = file_data['filename']
                
                # 4. Filter for modified files and extract context
                if any(mod_path in filepath for mod_path in mod_file_paths):
                    for violation in file_data.get('violations', []):
                        line_num = violation['beginline']
                        code_snippet = extract_code_line(filepath, line_num)
                        
                        bug_results.append({
                            "Project": project,
                            "BugID": bug_id,
                            "File": filepath.replace(target_dir, ""),
                            "Line": line_num,
                            "Rule": violation['rule'],
                            "Code_Snippet": code_snippet,
                            "Description": violation['description'].strip()
                        })
            
            # 5. Output Findings & Append to CSV instantly
            if bug_results:
                print(f"  -> {project}-{bug_id} : Found {len(bug_results)} anomaly(s). Writing to CSV.")
                
                with open(CSV_OUTPUT_FILE, mode='a', newline='', encoding='utf-8') as csv_file:
                    writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
                    writer.writerows(bug_results)
            else:
                print(f"  -> {project}-{bug_id} : Clean.")

            # 6. Cleanup workspace to save disk space
            shutil.rmtree(target_dir)

    print(f"\n✅ Full dataset analysis complete. Results saved to {CSV_OUTPUT_FILE}")

if __name__ == "__main__":
    main()