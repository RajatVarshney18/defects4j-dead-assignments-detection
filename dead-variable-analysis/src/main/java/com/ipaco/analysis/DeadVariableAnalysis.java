package com.ipaco.analysis;

import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.stmt.*;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;

import java.util.*;
import java.util.stream.Collectors;

public class DeadVariableAnalysis {

    private final SootMethod    method;
    private final Body          body;
    private final StmtGraph<?>  cfg;

    private final Map<Stmt, Set<Local>> liveIn  = new HashMap<>();
    private final Map<Stmt, Set<Local>> liveOut = new HashMap<>();

    public DeadVariableAnalysis(SootMethod method) {
        this.method = method;
        this.body   = method.getBody();
        this.cfg    = body.getStmtGraph();
    }

    // ----------------------------------------------------------------
    // entry point
    // ----------------------------------------------------------------
    public AnalysisResult run(String className, String sourceFile) {
        initialise();
        computeLiveness();
        return detectDeadAssignments(className, sourceFile);
    }

    // ----------------------------------------------------------------
    // initialise liveness maps
    // ----------------------------------------------------------------
    private void initialise() {
        for (Stmt s : getStatements()) {
            liveIn.put(s,  new HashSet<Local>());
            liveOut.put(s, new HashSet<Local>());
        }
    }

    // ----------------------------------------------------------------
    // backward liveness fixpoint
    // ----------------------------------------------------------------
    private void computeLiveness() {
        Deque<Stmt> worklist = new ArrayDeque<>(getStatements());

        while (!worklist.isEmpty()) {
            Stmt s = worklist.poll();

            Set<Local> newOut = new HashSet<Local>();
            for (Stmt succ : cfg.successors(s)) {
                Set<Local> si = liveIn.get(succ);
                if (si != null) newOut.addAll(si);
            }

            Set<Local> use = computeUse(s);
            Set<Local> def = computeDef(s);

            Set<Local> newIn = new HashSet<Local>(newOut);
            newIn.removeAll(def);
            newIn.addAll(use);

            if (!newIn.equals(liveIn.get(s))) {
                liveIn.put(s,  newIn);
                liveOut.put(s, newOut);
                for (Stmt pred : cfg.predecessors(s)) {
                    if (!worklist.contains(pred)) worklist.add(pred);
                }
            } else {
                liveOut.put(s, newOut);
            }
        }
    }

    // ----------------------------------------------------------------
    // detect dead assignments
    // ----------------------------------------------------------------
    private AnalysisResult detectDeadAssignments(String className,
                                                  String sourceFile) {
        AnalysisResult result = new AnalysisResult();
        result.setContext(className,
                method.getSignature().toString(), sourceFile);

        List<Stmt> stmts = getStatements();

        for (int idx = 0; idx < stmts.size(); idx++) {
            Stmt s = stmts.get(idx);

            // only JAssignStmt can produce a dead local definition
            if (!(s instanceof JAssignStmt)) continue;

            JAssignStmt assign = (JAssignStmt) s;
            Value lhs = assign.getLeftOp();
            Value rhs = assign.getRightOp();

            // only care about writes to local variables
            // (not field writes / array stores which have no Local LHS)
            if (!(lhs instanceof Local)) continue;

            Local definedVar = (Local) lhs;

            // --------------------------------------------------------
            // FILTER 1: skip Jimple synthetic temporaries
            // Jimple names these $stack0, $stack1, etc.
            // They are IR artefacts — not programmer variables.
            // --------------------------------------------------------
            if (isJimpleTemporary(definedVar)) continue;

            // --------------------------------------------------------
            // FILTER 2: skip zero/null initialisations
            // Jimple emits $v = 0 / $v = null before conditional
            // assignments as IR setup. These look dead but are artefacts.
            // --------------------------------------------------------
            if (isJimpleZeroInit(rhs)) continue;

            // --------------------------------------------------------
            // FILTER 3: skip invoke RHS entirely
            // "$v = invoke(...)" — the interesting finding is whether
            // the *invocation* is needed, not whether $v is live.
            // Reporting these creates enormous noise because Jimple
            // materialises every call result into a local even when
            // the Java source discards it.
            // --------------------------------------------------------
            if (rhs instanceof AbstractInvokeExpr) continue;

            // --------------------------------------------------------
            // FILTER 4: skip cast expressions
            // The cast must survive for its ClassCastException side
            // effect regardless of whether $v is live.
            // --------------------------------------------------------
            if (rhs instanceof JCastExpr) continue;

            // --------------------------------------------------------
            // FILTER 4b: skip alias copies from Jimple stack temps.
            // Pattern: userLocal = $stackN, where subsequent uses stay
            // on $stackN. This is an IR artefact, not a source bug.
            // --------------------------------------------------------
            if (rhs instanceof Local && isJimpleTemporary((Local) rhs)) {
                continue;
            }

            // --------------------------------------------------------
            // core liveness check: is the defined variable dead?
            // --------------------------------------------------------
            Set<Local> out = liveOut.get(s);
            if (out == null || out.contains(definedVar)) continue;

            // --------------------------------------------------------
            // FILTER 5: skip constant zero/false assignments that are
            // dead — these are Jimple boolean/int initialisations
            // for control flow, not real programmer assignments.
            // We already caught zero-init in FILTER 2 but some appear
            // as arithmetic results too.
            // --------------------------------------------------------

            // --------------------------------------------------------
            // classify what kind of dead assignment this is
            // --------------------------------------------------------
            String reason = classify(definedVar, rhs);
            if (reason == null) continue; // filtered by classify

            result.addDead(s, reason, idx + 1);
        }

        return result;
    }

    // ----------------------------------------------------------------
    // classify a confirmed-dead assignment
    // Returns a human-readable reason, or null to suppress this entry.
    // ----------------------------------------------------------------
    private String classify(Local definedVar, Value rhs) {

        // dead field read — programmer read a field but never used result
        if (rhs instanceof JFieldRef) {
            return "Dead field read: result of field access never used";
        }

        // dead array read
        if (rhs instanceof JArrayRef) {
            return "Dead array read: result of array access never used";
        }

        // dead instanceof — programmer checked a type but never used result
        if (rhs instanceof JInstanceOfExpr) {
            return "Dead instanceof: type check result never used";
        }

        // dead arithmetic / comparison
        if (rhs instanceof AbstractBinopExpr) {
            return "Dead computation: result of expression '"
                    + rhs + "' never used";
        }

        // dead unary
        if (rhs instanceof JNegExpr) {
            return "Dead negation: result never used";
        }

        // dead copy: $v = $u where $v is never read
        if (rhs instanceof Local) {
            return "Dead copy: " + definedVar.getName()
                + " = " + ((Local) rhs).getName()
                    + " never read after assignment";
        }

        // dead literal constant assignment (non-zero, non-null)
        // zero and null were already filtered above
        if (isNonTrivialConstant(rhs)) {
            return "Dead constant assignment: $"
                    + definedVar.getName()
                    + " = " + rhs + " never read";
        }

        // dead new-object allocation where result is never used
        if (rhs instanceof JNewExpr) {
            // new without reading the object = wasted allocation
            // BUT: the constructor call (specialinvoke) is a separate
            // JInvokeStmt and will survive — only the $v = new T
            // portion is dead
            return "Dead allocation: new "
                    + ((JNewExpr) rhs).getType()
                    + " result never used";
        }

        // suppress everything else — safer to under-report
        return null;
    }

    // ----------------------------------------------------------------
    // FILTER: is this local a Jimple synthetic temporary?
    //
    // SootUp names Jimple-generated temporaries with a $ prefix
    // followed by "stack" — e.g. $stack0, $stack1, $stack2.
    // Method parameters and user locals also start with $ in Jimple
    // (e.g. $r0, $r1, $i0) but these are parameter/type-derived names.
    //
    // We filter the stack temporaries only — not $r0/$i0 style locals.
    // ----------------------------------------------------------------
    private boolean isJimpleTemporary(Local local) {
        String name = local.getName();
        // "$stackN" pattern — pure IR temporaries
        if (name.startsWith("$stack")) return true;
        // "stack" without $ (some SootUp versions)
        if (name.startsWith("stack") && name.length() > 5
                && Character.isDigit(name.charAt(5))) return true;
        return false;
    }

    // ----------------------------------------------------------------
    // FILTER: is the RHS a zero/null/false initialisation?
    //
    // Jimple emits these as default-value assignments before
    // conditional assignments and try-catch entry points.
    // They look dead but are IR artefacts, not programmer bugs.
    // ----------------------------------------------------------------
    private boolean isJimpleZeroInit(Value rhs) {
        if (rhs instanceof IntConstant) {
            return ((IntConstant) rhs).getValue() == 0;
        }
        if (rhs instanceof LongConstant) {
            return ((LongConstant) rhs).getValue() == 0L;
        }
        if (rhs instanceof FloatConstant) {
            return ((FloatConstant) rhs).getValue() == 0.0f;
        }
        if (rhs instanceof DoubleConstant) {
            return ((DoubleConstant) rhs).getValue() == 0.0;
        }
        if (rhs instanceof NullConstant) {
            return true;
        }
        return false;
    }

    // ----------------------------------------------------------------
    // is RHS a non-trivial constant (not zero, not null)?
    // Used to identify genuine constant assignments worth reporting.
    // ----------------------------------------------------------------
    private boolean isNonTrivialConstant(Value rhs) {
        if (rhs instanceof IntConstant)
            return ((IntConstant) rhs).getValue() != 0;
        if (rhs instanceof LongConstant)
            return ((LongConstant) rhs).getValue() != 0L;
        if (rhs instanceof FloatConstant)
            return ((FloatConstant) rhs).getValue() != 0.0f;
        if (rhs instanceof DoubleConstant)
            return ((DoubleConstant) rhs).getValue() != 0.0;
        if (rhs instanceof StringConstant) return true;
        return false;
    }

    // ----------------------------------------------------------------
    // USE, DEF, helper — unchanged
    // ----------------------------------------------------------------
    private Set<Local> computeUse(Stmt s) {
        Set<Local> uses = new HashSet<Local>();
        for (Value v : s.getUses().collect(Collectors.toList())) {
            if (v instanceof Local) uses.add((Local) v);
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

    private List<Stmt> getStatements() {
        List<Stmt> stmts = new ArrayList<>();
        for (Stmt s : cfg.getNodes()) stmts.add(s);
        return stmts;
    }
}