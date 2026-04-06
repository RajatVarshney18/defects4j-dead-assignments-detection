import subprocess
import json
import os
import shutil
import csv
import re

# --- Configuration ---
WORKSPACE_DIR = "/tmp/d4j_workspace"
CSV_OUTPUT_FILE = "defects4j_verified_dead_bugs.csv"

# !!! UPDATE THESE PATHS TO MATCH YOUR SYSTEM !!!
D4J_HOME = "/home/rajatvarshney/Documents/IPACO/Project2/defects4j"
D4J_EXEC = os.path.join(D4J_HOME, "framework/bin/defects4j")
PMD_EXEC = "/home/rajatvarshney/Documents/IPACO/Project2/pmd-bin-7.23.0/bin/pmd" 

# D4J 2.0 Project Dictionary (Project : Max Bug ID)
D4J_PROJECTS = {
    "Chart": 26, "Cli": 40, "Closure": 176, "Codec": 18, 
    "Collections": 28, "Compress": 47, "Csv": 16, "Gson": 18, 
    "JacksonCore": 26, "JacksonDatabind": 112, "JacksonXml": 6, 
    "Jsoup": 93, "JxPath": 22, "Lang": 65, "Math": 106, 
    "Mockito": 38, "Time": 27
}

PMD_RULES = (
    "category/java/bestpractices.xml/UnusedAssignment,"
    "category/java/bestpractices.xml/UnusedLocalVariable,"
    "category/java/bestpractices.xml/UnusedFormalParameter,"
    "category/java/bestpractices.xml/UnusedPrivateField"
)

def run_d4j_command(cmd_args, cwd=None):
    cmd = [D4J_EXEC] + cmd_args
    return subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)

def get_source_dir(work_dir):
    result = run_d4j_command(["export", "-p", "dir.src.classes"], cwd=work_dir)
    return result.stdout.strip() if result.returncode == 0 else ""

def extract_code_line(filepath, line_number):
    try:
        with open(filepath, 'r') as file:
            return file.readlines()[line_number - 1].strip()
    except Exception:
        return "<Could not extract code line>"

def get_buggy_lines_from_patch(project, bug_id):
    """
    Parses the Defects4J .src.patch file and returns a dictionary mapping
    relative file paths to a Set of line numbers that were removed/modified.
    """
    patch_path = os.path.join(D4J_HOME, "framework", "projects", project, "patches", f"{bug_id}.src.patch")
    buggy_lines_map = {}
    
    if not os.path.exists(patch_path):
        return buggy_lines_map

    current_file = None
    current_line = 0

    with open(patch_path, 'r', encoding='utf-8') as f:
        for line in f:
            if line.startswith('--- a/'):
                # Extract the file path (e.g., src/main/java/org/apache/...)
                current_file = line[6:].strip()
                buggy_lines_map[current_file] = set()
            
            elif line.startswith('@@'):
                # Extract the starting line number of the buggy chunk
                # Matches: @@ -342,7 +342,7 @@
                match = re.search(r'@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@', line)
                if match:
                    current_line = int(match.group(1))
            
            elif current_file:
                # If it's a removed/changed line in the buggy file
                if line.startswith('-'):
                    buggy_lines_map[current_file].add(current_line)
                    current_line += 1
                # If it's unchanged context, just increment the buggy line counter
                elif line.startswith(' '):
                    current_line += 1
                # Lines starting with '+' are additions to the FIXED file, 
                # they do not exist in the buggy file, so we do not increment current_line.
                
    return buggy_lines_map

def run_pmd_analysis(source_dir):
    cmd = [PMD_EXEC, "check", "-d", source_dir, "-R", PMD_RULES, "-f", "json"]
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
    csv_headers = ["Project", "BugID", "File", "Line", "Rule", "Code_Snippet", "Description"]
    
    with open(CSV_OUTPUT_FILE, mode='w', newline='', encoding='utf-8') as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
        writer.writeheader()

    print(f"Starting STRICT Patch-Verified Analysis. Outputting to {CSV_OUTPUT_FILE}\n")

    for project, max_bug in D4J_PROJECTS.items():
        print(f"=== Processing Project: {project} ===")
        
        for bug_id in range(1, max_bug + 1):
            target_dir = os.path.join(WORKSPACE_DIR, f"{project}_{bug_id}")
            
            # 1. Get the exact lines changed in the patch BEFORE checking out code
            buggy_lines_map = get_buggy_lines_from_patch(project, bug_id)
            if not buggy_lines_map:
                continue # Skip if no patch file or no deleted/modified lines found
            
            # 2. Checkout
            if os.path.exists(target_dir):
                shutil.rmtree(target_dir)
            checkout_res = run_d4j_command(["checkout", "-p", project, "-v", f"{bug_id}b", "-w", target_dir])
            if checkout_res.returncode != 0:
                continue
                
            # 3. Get source dir and run PMD
            src_dir_relative = get_source_dir(target_dir)
            full_src_dir = os.path.join(target_dir, src_dir_relative)
            if not src_dir_relative or not os.path.exists(full_src_dir):
                shutil.rmtree(target_dir)
                continue
                
            violations = run_pmd_analysis(full_src_dir)
            
            bug_results = []
            for file_data in violations:
                abs_filepath = file_data['filename']
                
                # Check if this PMD file matches any file in our patch
                for patch_file, modified_lines in buggy_lines_map.items():
                    # The patch path is usually relative (e.g. src/java/...), so we check if absolute path ends with it
                    patch_path_normalized = patch_file.replace('/', os.sep)
                    if abs_filepath.endswith(patch_path_normalized):
                        
                        # We have a file match! Now strictly check the line numbers.
                        for violation in file_data.get('violations', []):
                            line_num = violation['beginline']
                            
                            # THE STRICT FILTER: Is this PMD line inside the developer's patch?
                            if line_num in modified_lines:
                                code_snippet = extract_code_line(abs_filepath, line_num)
                                bug_results.append({
                                    "Project": project,
                                    "BugID": bug_id,
                                    "File": patch_file,
                                    "Line": line_num,
                                    "Rule": violation['rule'],
                                    "Code_Snippet": code_snippet,
                                    "Description": violation['description'].strip()
                                })
            
            # 4. Output and Save True Positives
            if bug_results:
                print(f"  -> {project}-{bug_id} : Found {len(bug_results)} STRICT match(es).")
                with open(CSV_OUTPUT_FILE, mode='a', newline='', encoding='utf-8') as csv_file:
                    writer = csv.DictWriter(csv_file, fieldnames=csv_headers)
                    writer.writerows(bug_results)
            
            # Cleanup
            shutil.rmtree(target_dir)

    print(f"\n✅ Strict analysis complete. Ground-truth results saved to {CSV_OUTPUT_FILE}")

if __name__ == "__main__":
    main()