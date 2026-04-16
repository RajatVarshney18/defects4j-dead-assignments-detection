# DVA Jimple Statement Handling - PPT Table

## Table 1: Jimple Statement Types & DVA Handling

| Jimple Statement | Handled? | Code Location | Analysis Role | Filters Applied |
|:---|:---:|:---|:---|:---|
| **JAssignStmt** | ✅ | L96-177 | Dead assignment detection | 5 layers |
| **JIdentityStmt** | ✅ | L318-321 | Parameter/this definitions | None |
| **JIfStmt** | ✅ | L308, L60 | Condition uses + branching | None |
| **JReturnStmt** | ✅ | L308 | Return value use | None |
| **JReturnVoidStmt** | ✅ | L308 | Return (no-op) | None |
| **JThrowStmt** | ✅ | L308 | Exception use | None |
| **JInvokeStmt** | ✅ | L308, L124-131 | Method calls + uses | Filter 3 |
| **JGotoStmt** | ✅ | L60 | Control flow | None |
| **JSwitchStmt** | ✅ | L60 | Multi-branch + uses | None |
| **JNopStmt** | ✅ | L308 | No operation | None |
| **JCastExpr** | ⚠️ FILTERED | L138-140 | Cast side-effects | Filter 4 |
| **JEnterMonitorStmt / JExitMonitorStmt** | ✅ | L308 | Lock object use | None |

---

## Table 2: Five-Layer Filtering Pipeline

| Filter # | Name | Code Lines | Pattern | Reason |
|:---:|:---|:---:|:---|:---|
| **1** | Jimple Synthetic Temporaries | L117-122 | `$stack*` locals | IR artifacts, not source code |
| **2** | Zero/Null Initialization | L124-131 | `$v = 0 / null / false` | Jimple IR setup, not programmer bugs |
| **3** | Invoke Result Locals | L132-137 | `$v = invoke(...)` | Jimple materializes all call results |
| **4** | Cast Expressions | L138-140 | `$v = (Type) expr` | ClassCastException side-effect preservation |
| **4b** | Stack Temporary Aliases | L141-145 | `$v = $stackN` | Copy propagation artifact |

---

## Table 3: Dead Assignment Classification (8 Categories)

| Classification | Code Lines | Jimple Pattern | Example |
|:---|:---:|:---|:---|
| **Dead Field Read** | L176-178 | `$v = obj.field` | `$r2 = obj.x` (unused) |
| **Dead Array Access** | L180-182 | `$v = arr[index]` | `$r2 = arr[0]` (unused) |
| **Dead instanceof** | L184-187 | `$v = obj instanceof Type` | `$i0 = obj instanceof String` (unused) |
| **Dead Computation** | L189-193 | `$v = a op b` | `$i0 = $i1 + $i2` (unused) |
| **Dead Unary Op** | L195-198 | `$v = -a` | `$i0 = -$i1` (unused) |
| **Dead Copy** | L200-205 | `$v = $u` | `$r2 = $r3` (unused) |
| **Dead Constant** | L207-211 | `$v = constant` | `$i0 = 42` or `$r0 = "str"` (unused) |
| **Dead Allocation** | L213-220 | `$v = new Type()` | `$r2 = new Object()` (never read) |

---

## Table 4: Algorithm Pipeline

| Step | Method | Code Lines | Input | Output | Jimple Handled |
|:---:|:---|:---:|:---|:---|:---|
| **1** | initialize() | L42-47 | CFG statements | Empty LiveIn/LiveOut maps | All statements |
| **2** | computeLiveness() | L49-75 | CFG + Def/Use sets | LiveIn/LiveOut values | Uses cfg.successors(), DEF/USE extraction |
| **3** | computeDef() | L313-321 | JAssignStmt, JIdentityStmt | Def set for each stmt | JAssignStmt, JIdentityStmt only |
| **4** | computeUse() | L308-311 | Any statement | Use set (extracted from stmt.getUses()) | All statements (polymorphic) |
| **5** | detectDeadAssignments() | L77-172 | Checked assignments | Dead findings list | JAssignStmt with liveness check |
| **6** | classify() | L174-220 | RHS expression type | Reason string or null | 8+ expression types |

---

## Table 5: Method Source Code Mapping

| Code Location | Method | Purpose | Handles Jimple Types |
|:---|:---|:---|:---|
| **DeadVariableAnalysis.java** | | **Main Analysis Class** | |
| L17-24 | Constructor | Initialize with SootMethod | Sets up CFG, Body, method |
| L26-32 | run() | Entry point | Orchestrates analysis pipeline |
| L38-75 | computeLiveness() | **Fixpoint iteration** | All (via successors + DEF/USE) |
| L308-311 | computeUse() | **Extract read variables** | All Jimple statements |
| L313-321 | computeDef() | **Extract written variables** | JAssignStmt, JIdentityStmt |
| L77-172 | detectDeadAssignments() | **Dead detection + filters** | JAssignStmt; applies 5 filters |
| L174-220 | classify() | **Categorize findings** | 8+ RHS expression types |
| **Main.java** | | **Driver** | |
| L36-48 | main() | Setup JavaView + class collection | Loads all classes from bytecode |
| L50-77 | main() loop | Iterate methods, run DVA | Calls DeadVariableAnalysis for each method |

---

## Quick Reference: Filtering Decision Tree

```
                    JAssignStmt
                         |
                    Yes (continue)
                         |
              ┌───────────┴───────────┐
              |                       |
      LHS instanceof Local?    No → SKIP (field/array write)
              |
             Yes
              |
      ┌───────┴───────┐
      |               |
  isJimpleTemps?  Yes → FILTER 1 (SKIP)
      |
      No
      |
  isZeroInit?     Yes → FILTER 2 (SKIP)
      |
      No
      |
  invoke RHS?     Yes → FILTER 3 (SKIP)
      |
      No
      |
  cast RHS?       Yes → FILTER 4 (SKIP)
      |
      No
      |
  stack alias?    Yes → FILTER 4b (SKIP)
      |
      No
      |
  LiveOut.contains(def)? Yes → LIVENESS (SKIP - variable is live)
      |
      No
      |
  → REPORT AS DEAD + CLASSIFY
```

---

## Summary Statistics

| Metric | Value | Code Line(s) |
|:---|:---|:---|
| **Total Jimple Statement Types Handled** | 11+ | L308, L313, L60 |
| **Filtering Layers** | 5 | L117-145 |
| **Dead Assignment Classifications** | 8+ | L174-220 |
| **Dataflow Passes Required** | Fixpoint (1 forward→many backward) | L49-75 |
| **CFG Successors Used** | Yes (implies exception paths handled) | L60 |
| **Excluded Statement Types** | JFieldAssign, JArrayStore (LHS not Local) | L103-104 |
