package com.ipaco.analysis;

import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JInstanceOfExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.jimple.common.constant.*;

import java.util.*;

public class DeadVariableAnalysis {
    
    private final SootMethod  method; 
    private final Body body;
    private final StmtGraph<?> cfg;
    
    // Pass-1 liveness maps:
    // LiveIN(s)  = set of locals live just BEFORE s
    // LiveOUT(s) = set of locals live just AFTER s
    private final Map<Stmt, Set<Local>> liveIn  = new HashMap<>();
    private final Map<Stmt, Set<Local>> liveOut = new HashMap<>();

    // Pass-2 constant propagation maps:
    private ConstantPropagationAnalysis constAnalysis;

    public DeadVariableAnalysis(SootMethod method) {
        this.method = method;
        this.body   = method.getBody();
        this.cfg    = body.getStmtGraph();
    }

     // public entry point
    // ----------------------------------------------------------------
    public AnalysisResult run(String className, String sourceFile) {

      // Step 1: forward constant propagation
        constAnalysis = new ConstantPropagationAnalysis(method);
        constAnalysis.run();

      // Step 2: backward liveness and dead assignment detection
        initialise();
        computeLiveness();
        return detectDeadAssignments(className, sourceFile);
    }

     // ----------------------------------------------------------------
    // A: initialise all sets to empty
    // ----------------------------------------------------------------
   private void initialise() {
        for (Stmt stmt : getStatements()) {
            liveIn.put(stmt,  new HashSet<Local>());
            liveOut.put(stmt, new HashSet<Local>());
        }
    }

    private List<Stmt> getStatements() {
        List<Stmt> stmts = new ArrayList<Stmt>();
        for (Stmt s : cfg.getNodes()) {
            stmts.add(s);
        }
        return stmts;
    }

    // ----------------------------------------------------------------
    // B: backward fixpoint — liveness computation
    // Equation:
    //   LiveOUT(s) = union of LiveIN(succ) for all successors of s
    //   LiveIN(s)  = USE(s) union (LiveOUT(s) minus DEF(s))
    //
    // We iterate until no LiveIN set changes.
    // ----------------------------------------------------------------
    private void computeLiveness() {

        // start with all statements in the worklist
        Deque<Stmt> worklist = new ArrayDeque<Stmt>(getStatements());

        while (!worklist.isEmpty()) {

            Stmt s = worklist.poll();

            // --- recompute LiveOUT(s) ---
            // = union of LiveIN of every successor
            Set<Local> newOut = new HashSet<Local>();
            for (Stmt succ : cfg.successors(s)) {
                Set<Local> succIn = liveIn.get(succ);
                if (succIn != null) {
                    newOut.addAll(succIn);
                }
            }

            // --- recompute LiveIN(s) ---
            // = USE(s) union (LiveOUT(s) minus DEF(s))
            Set<Local> use = computeUse(s);
            Set<Local> def = computeDef(s);

            Set<Local> newIn = new HashSet<Local>(newOut);
            newIn.removeAll(def);   // kill what s defines
            newIn.addAll(use);      // gen what s reads

            // --- check if LiveIN changed ---
            if (!newIn.equals(liveIn.get(s))) {

                liveIn.put(s,  newIn);
                liveOut.put(s, newOut);

                // re-add all predecessors — their LiveOUT depends on our LiveIN
                for (Stmt pred : cfg.predecessors(s)) {
                    if (!worklist.contains(pred)) {
                        worklist.add(pred);
                    }
                }

            } else {
                // LiveIN unchanged but still update LiveOUT
                liveOut.put(s, newOut);
            }
        }
    }

    // ----------------------------------------------------------------
    // detect dead assignments — now uses BOTH analyses
    // ----------------------------------------------------------------
    private AnalysisResult detectDeadAssignments(String className,
                                                  String sourceFile) {
        AnalysisResult result = new AnalysisResult();
        result.setContext(className,
                method.getSignature().toString(), sourceFile);

        for (Stmt s : getStatements()) {

            if (!(s instanceof JAssignStmt)) continue;

            JAssignStmt assign = (JAssignStmt) s;
            Value lhs = assign.getLeftOp();
            Value rhs = assign.getRightOp();

            if (!(lhs instanceof Local)) continue;

            Local definedVar = (Local) lhs;

            // ---- Check 1: standard liveness (original) ----
            Set<Local> out = liveOut.get(s);
            if (out != null && !out.contains(definedVar)) {
                String reason = classifyDeadAssignment(rhs);
                result.addDead(s, reason);
                continue; // already dead — no need for check 2
            }

            // ---- Check 2: constant-value deadness (new) ----
            // Variable IS live (check 1 passed) but we check whether
            // the constant value being written is already present
            // i.e., the assignment is a redundant constant write
            String constReason = checkConstantValueDead(s, definedVar, rhs);
            if (constReason != null) {
                result.addDead(s, constReason);
            }
        }

        return result;
    }

    // ----------------------------------------------------------------
    // NEW: check if a live assignment is a redundant constant write
    //
    // A statement $v = C is constant-value dead if:
    //   (a) C is a literal constant (or folds to one)
    //   AND
    //   (b) the constant propagation analysis tells us that
    //       $v already holds value C just BEFORE this statement
    //
    // In that case, the assignment writes a value that $v
    // already contains — completely redundant.
    //
    // Returns a description string if dead, null if not.
    // ----------------------------------------------------------------
    private String checkConstantValueDead(Stmt s, Local definedVar,
                                           Value rhs) {

        // extract the constant being written (if the RHS is one)
        Object rhsConstant = extractConstant(rhs);

        if (rhsConstant == null) {
            // RHS is not a simple constant or foldable expression
            return null;
        }

        // what does constant propagation say $v holds BEFORE s?
        Object valueBefore =
                constAnalysis.getConstantValueBefore(s, definedVar);

        if (valueBefore == null) {
            // $v is not known to be constant before s
            return null;
        }

        // are they the same?
        if (rhsConstant.equals(valueBefore)) {
            return "constant-value dead: $" + definedVar.getName()
                    + " already holds " + rhsConstant
                    + " before this assignment — fully eliminable";
        }

        return null;
    }

    // ----------------------------------------------------------------
    // Extract a constant value from a simple RHS value.
    // Returns the constant object, or null if RHS is not constant.
    // We only handle literal constants here — the full folding
    // is done inside ConstantPropagationAnalysis.evaluateRhs.
    // ----------------------------------------------------------------
    private Object extractConstant(Value rhs) {
        if (rhs instanceof IntConstant)
            return ((IntConstant) rhs).getValue();
        if (rhs instanceof LongConstant)
            return ((LongConstant) rhs).getValue();
        if (rhs instanceof FloatConstant)
            return ((FloatConstant) rhs).getValue();
        if (rhs instanceof DoubleConstant)
            return ((DoubleConstant) rhs).getValue();
        if (rhs instanceof StringConstant)
            return ((StringConstant) rhs).getValue();
        if (rhs instanceof NullConstant)
            return null; // null is a special case — skip
        return null;
    }

    // ----------------------------------------------------------------
    // classify dead RHS — unchanged from before
    // ----------------------------------------------------------------
    private String classifyDeadAssignment(Value rhs) {
        if (rhs instanceof JCastExpr)
            return "dead cast result — transform to bare checkcast, do not eliminate";
        if (rhs instanceof JInstanceOfExpr)
            return "dead instanceof result — fully eliminable";
        if (rhs instanceof AbstractInvokeExpr)
            return "dead invoke result — transform to bare invoke, keep side effects";
        if (rhs instanceof JFieldRef)
            return "dead field read — eliminable (verify not volatile)";
        if (rhs instanceof JArrayRef)
            return "dead array read — fully eliminable";
        return "dead assignment — fully eliminable";
    }

    // ----------------------------------------------------------------
    // E: USE(s) — locals READ by statement s
    //    SootUp's getUses() returns all Values used in the statement.
    //    We filter to keep only Local instances.
    // ----------------------------------------------------------------
    private Set<Local> computeUse(Stmt s) {
        Set<Local> uses = new HashSet<Local>();
        Collection<Value> useValues = s.getUses().collect(java.util.stream.Collectors.toList());
        if (useValues != null) {
            for (Value v : useValues) {
                if (v instanceof Local) {
                    uses.add((Local) v);
                }
            }
        }
        return uses;
    }

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
}
