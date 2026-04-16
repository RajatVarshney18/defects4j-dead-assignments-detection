# DVA Implementation: Jimple Statement Handling Analysis

## Overview Table

| Jimple Statement Type | Handled? | Code Location | Analysis Role | Details |

|:---|:---|:---|:---|:---|
| **JAssignStmt** | ✅ YES | `DeadVariableAnalysis.java` (L96-177) | **Primary Focus** | Directly analyzed for dead assignments. LHS extracted for definition tracking; RHS analyzed for uses. Subject to 5 filtering layers. |
| **JIdentityStmt** | ✅ YES | `DeadVariableAnalysis.java` (L318-321: computeDef()) | **Def Extraction** | Extracts initial definitions of `@this` and `@parameter` locals; seeded into liveness analysis. Not analyzed for deadness itself. |
| **JInvokeStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308-309: computeUse()) | **Use Extraction** | Method receiver and arguments extracted as uses via `s.getUses()`. Result locals from invokes are filtered (FILTER 3, L124-131). |
| **JIfStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **Condition Uses** | Condition expression variables extracted as uses; branch targets handled by `cfg.successors()`. |
| **JReturnStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **Return Value Use** | Returned value extracted as a use; prevents returned variables from being marked dead. |
| **JReturnVoidStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **No-op for Analysis** | No uses/defs; handled transparently by generic statements. |
| **JThrowStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **Exception Use** | Thrown exception object extracted as a use; exception remains live. |
| **JGotoStmt** | ✅ YES | `cfg.successors(s)` (L60) | **CFG Navigation** | No uses/defs; control flow handled by StmtGraph successor iteration. |
| **JSwitchStmt** | ✅ YES | `cfg.successors(s)` (L60) | **Switch Key Use** | Switch key extracted as use; all case targets merged into successor set. |
| **JEnterMonitorStmt / JExitMonitorStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **Lock Object Use** | Lock object extracted as use; minimal impact on local analysis. |
| **JNopStmt** | ✅ YES | `DeadVariableAnalysis.java` (L308: computeUse()) | **Transparent** | No uses/defs; safely skipped. |
| **JCastExpr** (within statements) | ⚠️ FILTERED | `DeadVariableAnalysis.java` (L135-138: FILTER 4) | **Side-Effect Preservation** | Cast destinations filtered because casts must survive for ClassCastException side effects. |
| **Field Reads (JFieldRef)** | ✅ YES | `DeadVariableAnalysis.java` (L176-178: classify()) | **Dead Field Read Detection** | Identified and classified as dead if result is not live. |
| **Array Access (JArrayRef)** | ✅ YES | `DeadVariableAnalysis.java` (L180-182: classify()) | **Dead Array Read Detection** | Identified and classified as dead if result is not live. |

---

## Filtering Layers & Code References

### FILTER 1: Jimple Synthetic Temporaries

**Code:** `DeadVariableAnalysis.java` (L117-122)  
**Method:** `isJimpleTemporary(Local)`  
**Applies To:** `JAssignStmt` with LHS = `$stack*` pattern  
**Rationale:** `$stack0`, `$stack1`, etc. are IR artifacts, not programmer code.

```java
private boolean isJimpleTemporary(Local local) {
    String name = local.getName();
    if (name.startsWith("$stack")) return true;
    if (name.startsWith("stack") && name.length() > 5
            && Character.isDigit(name.charAt(5))) return true;
    return false;
}
```

### FILTER 2: Zero/Null Initialization

**Code:** `DeadVariableAnalysis.java` (L124-131)  
**Method:** `isJimpleZeroInit(Value)`  
**Applies To:** `JAssignStmt` with RHS = `0`, `null`, `false`  
**Rationale:** Jimple default-value assignments before conditionals are IR setup, not bugs.

```java
private boolean isJimpleZeroInit(Value rhs) {
    if (rhs instanceof IntConstant) 
        return ((IntConstant) rhs).getValue() == 0;
    if (rhs instanceof NullConstant) return true;
    // ... other constant types
}
```

### FILTER 3: Invoke Result Locals

**Code:** `DeadVariableAnalysis.java` (L132-137)  
**Pattern:** `$v = invoke(...)`  
**Rationale:** Jimple materializes every call result into a local, even when Java source discards it. Reporting these creates noise.

```java
if (rhs instanceof AbstractInvokeExpr) continue;
```

### FILTER 4: Cast Expressions

**Code:** `DeadVariableAnalysis.java` (L138-140)  
**Pattern:** `$v = ($Type) expr`  
**Rationale:** Casts must survive for their `ClassCastException` side effect.

```java
if (rhs instanceof JCastExpr) continue;
```

### FILTER 4b: Stack Temporary Aliases

**Code:** `DeadVariableAnalysis.java` (L141-145)  
**Pattern:** `userLocal = $stackN`  
**Rationale:** IR artifact where Jimple aliases temporaries; subsequent uses stay on stack. Not a source bug.

```java
if (rhs instanceof Local && isJimpleTemporary((Local) rhs)) {
    continue;
}
```

### FILTER 5: Liveness Check (Core Algorithm)

**Code:** `DeadVariableAnalysis.java` (L147-149)  
**Condition:** `out.contains(definedVar)` or `out == null` → Skip (variable is live)  
**Rationale:** Backward liveness fixpoint; if defined variable appears in LiveOut, it's not dead.

```java
Set<Local> out = liveOut.get(s);
if (out == null || out.contains(definedVar)) continue;
```

---

## Dead Assignment Classification (Post-Detection)

Once an assignment passes all filters and is confirmed dead, it is classified into one of these categories:

| Classification | Code Location | Jimple Pattern Examples |

|:---|:---|:---|
| **Dead Field Read** | `classify()` L176-178 | `$v = obj.field` where `$v` never used |
| **Dead Array Access** | `classify()` L180-182 | `$v = arr[i]` where `$v` never used |
| **Dead instanceof** | `classify()` L184-187 | `$v = obj instanceof Type` where `$v` never used |
| **Dead Computation** | `classify()` L189-193 | `$v = a + b` or `$v = a < b` where `$v` never used |
| **Dead Unary Op** | `classify()` L195-198 | `$v = -a` where `$v` never used |
| **Dead Copy** | `classify()` L200-205 | `$v = $u` where `$v` never used |
| **Dead Constant** | `classify()` L207-211 | `$v = 42` or `$v = "str"` where `$v` never used |
| **Dead Allocation** | `classify()` L213-220 | `$v = new Type()` where `$v` never used after construction |

---

## Core Dataflow Algorithm: USE & DEF Extraction

### `computeUse(Stmt s)` — Lines 308-311

Extracts all `Local` variables read by any statement via the generic SootUp `getUses()` method:

- **Applies To:** ALL statement types (JAssignStmt, JIfStmt, JInvokeStmt, JReturnStmt, JThrowStmt, etc.)
- **UML:** `JAssignStmt.getRightOp()` → uses  |  `JIfStmt.getCondition()` → uses  |  etc.

```java
private Set<Local> computeUse(Stmt s) {
    Set<Local> uses = new HashSet<Local>();
    for (Value v : s.getUses().collect(Collectors.toList())) {
        if (v instanceof Local) uses.add((Local) v);
    }
    return uses;
}
```

### `computeDef(Stmt s)` — Lines 313-321

Extracts all `Local` variables written by assignment or identity statements:

- **JAssignStmt:** LHS is the definition
- **JIdentityStmt:** LHS is the initial definition (`@this`, `@parameter`)

```java
private Set<Local> computeDef(Stmt s) {
    Set<Local> defs = new HashSet<Local>();
    if (s instanceof JAssignStmt) {
        Value lhs = ((JAssignStmt) s).getLeftOp();
        if (lhs instanceof Local) defs.add((Local) lhs);
    } else if (s instanceof JIdentityStmt) {
        Value lhs = ((JIdentityStmt) s).getLeftOp();
        if (lhs instanceof Local) defs.add((Local) lhs);
    }
    return defs;
}
```

---

## Backward Liveness Fixpoint Algorithm

**Code Location:** `computeLiveness()` Lines 54-75

| Step | Code | Jimple Role |

|:---|:---|:---|
| **1. Worklist Init** | L55 | Populate with all statements from CFG |
| **2. Successor Union** | L59-61 | For each statement, union LiveIn of all successors (control flow via `cfg.successors()`) |
| **3. Extract Use/Def** | L63-64 | Extract uses and defs for current statement |
| **4. Compute LiveIn** | L66-68 | `LiveIn = Use ∪ (LiveOut - Def)` |
| **5. Fixpoint Check** | L70-75 | If LiveIn changed, re-queue predecessors; otherwise converge |

### Control Flow Handling

- **Branch Targets (JIfStmt, JSwitchStmt):** Handled by `cfg.successors(s)` which merges all possible paths
- **Exception Handlers:** Standard `cfg.successors()` includes exception edges (SootUp detail)
- **Loops:** Worklist adds predecessors when state changes, enabling loop iteration

---

## Summary of Jimple Statement Handling

| Category | Statements | Treatment | Output |

|:---|:---|:---|:---|
| **Definitions** | JAssignStmt, JIdentityStmt | Extract LHS as `Def` | Added to kill set during liveness computation |
| **Uses (Explicit)** | JAssignStmt (RHS), JIfStmt (condition), JReturnStmt (value), JInvokeStmt (recv+args), JThrowStmt (object) | Extract via `computeUse()` + SootUp API | Added to gen set during liveness computation |
| **Control Flow** | JIfStmt, JSwitchStmt, JGotoStmt, JReturnStmt, JThrowStmt, JReturnVoidStmt | Route via `cfg.successors()` | Successor liveness merged upstream |
| **Filtered (Dead Analysis)** | JAssignStmt with $stack*, 0/null init, invoke result, cast, stack alias | `continue` statement | Excluded from dead assignment report |
| **No-ops** | JNopStmt, JEnterMonitorStmt/JExitMonitorStmt (basic analysis) | Transparent passthrough | No impact on dead variable detection |

---

## PowerPoint Summary for Presentation

```
┌─────────────────────────────────────────────────────────────┐
│        DVA Implementation: Jimple Statement Handling        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. INPUT: Extract all statements from method CFG          │
│     ↓                                                       │
│  2. BACKWARD LIVENESS: Compute LiveIn/LiveOut for each     │
│     statement using DEF/USE sets (fixpoint iteration)      │
│     ↓                                                       │
│  3. DEAD ASSIGN DETECTION: Find JAssignStmt where LHS      │
│     not in LiveOut                                         │
│     ↓                                                       │
│  4. FILTERING LAYER: Apply 5 filters to eliminate IR       │
│     artifacts ($stack*, 0-init, invoke result, etc.)       │
│     ↓                                                       │
│  5. CLASSIFICATION: Categorize remaining findings          │
│     (field read, array access, computation, etc.)          │
│     ↓                                                       │
│  OUTPUT: Ranked list of dead assignments + reasons         │
│                                                             │
│  Handles 11+ Jimple statement types                         │
│  5 filtering layers for IR noise suppression               │
│  8 classification categories for dead assignments          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```
