package com.ipaco.analysis;

import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;

import java.util.*;

public class ConstantPropagationAnalysis {

    private final Body        body;
    private final StmtGraph<?> cfg;

    // constBefore(s): state of all locals just before stmt s
    private final Map<Stmt, Map<Local, ConstValue>> constBefore
            = new HashMap<>();

    // constAfter(s): state of all locals just after stmt s
    private final Map<Stmt, Map<Local, ConstValue>> constAfter
            = new HashMap<>();

    public ConstantPropagationAnalysis(SootMethod method) {
        this.body = method.getBody();
        this.cfg  = body.getStmtGraph();
    }

    // ----------------------------------------------------------------
    // Run the analysis
    // ----------------------------------------------------------------
    public void run() {
        initialise();
        computeForwardFixpoint();
    }

    // ----------------------------------------------------------------
    // A: initialise
    //    We use TOP (not BOTTOM) as the initial value so the first
    //    real assignment can lower it to a constant.
    // ----------------------------------------------------------------
    private void initialise() {
        List<Local> allLocals = new ArrayList<>(body.getLocals());

        for (Stmt stmt : getStatements()) {
            Map<Local, ConstValue> before = new HashMap<>();
            Map<Local, ConstValue> after  = new HashMap<>();

            for (Local local : allLocals) {
                before.put(local, ConstValue.top());
                after.put(local,  ConstValue.top());
            }

            constBefore.put(stmt, before);
            constAfter.put(stmt,  after);
        }
    }

     // ----------------------------------------------------------------
    // B: forward fixpoint
    //
    //   constBefore(s) = MEET of constAfter(pred) for all predecessors
    //   constAfter(s)  = transfer(s, constBefore(s))
    //
    // We repeat until no constAfter map changes.
    // ----------------------------------------------------------------
    private void computeForwardFixpoint() {

        // use a worklist starting from entry
        Deque<Stmt> worklist = new ArrayDeque<>(getStatements());

        while (!worklist.isEmpty()) {

            Stmt s = worklist.poll();

            // --- Step 1: compute constBefore(s) ---
            // = meet of constAfter of all predecessors
            Map<Local, ConstValue> newBefore = computeMeetOfPredecessors(s);

            // --- Step 2: apply transfer function ---
            // = compute constAfter(s) from constBefore(s)
            Map<Local, ConstValue> newAfter =
                    applyTransferFunction(s, newBefore);

            // --- Step 3: check if constAfter changed ---
            if (!newAfter.equals(constAfter.get(s))) {

                constBefore.put(s, newBefore);
                constAfter.put(s,  newAfter);

                // push all successors — their constBefore depends on our constAfter
                for (Stmt succ : cfg.successors(s)) {
                    if (!worklist.contains(succ)) {
                        worklist.add(succ);
                    }
                }

            } else {
                // constAfter unchanged — still update constBefore
                constBefore.put(s, newBefore);
            }
        }
    }

    // ----------------------------------------------------------------
    // C: meet over all predecessors
    // ----------------------------------------------------------------
    private Map<Local, ConstValue> computeMeetOfPredecessors(Stmt s) {

        List<Stmt> preds = new ArrayList<>();
        for (Stmt p : cfg.predecessors(s)) {
            preds.add(p);
        }

        // no predecessors = entry node: start with TOP for everything
        if (preds.isEmpty()) {
            Map<Local, ConstValue> result = new HashMap<>();
            for (Local l : body.getLocals()) {
                result.put(l, ConstValue.top());
            }
            return result;
        }

        // start with the first predecessor's constAfter
        Map<Local, ConstValue> result =
                new HashMap<>(constAfter.get(preds.get(0)));

        // meet with each remaining predecessor
        for (int i = 1; i < preds.size(); i++) {
            Map<Local, ConstValue> predAfter =
                    constAfter.get(preds.get(i));

            for (Local local : body.getLocals()) {
                ConstValue existing = result.getOrDefault(
                        local, ConstValue.top());
                ConstValue fromPred = predAfter.getOrDefault(
                        local, ConstValue.top());
                result.put(local, existing.meet(fromPred));
            }
        }

        return result;
    }

     // ----------------------------------------------------------------
    // D: transfer function
    //    Computes constAfter(s) from constBefore(s).
    //
    //    For most statements: constAfter = constBefore (no change)
    //    For assignment $v = rhs:
    //        evaluate rhs using constBefore → if result is a constant
    //        then constAfter[$v] = CONST(that value)
    //        else constAfter[$v] = BOTTOM
    // ----------------------------------------------------------------
    private Map<Local, ConstValue> applyTransferFunction(
            Stmt s, Map<Local, ConstValue> before) {

        // copy before into after as the starting state
        Map<Local, ConstValue> after = new HashMap<>(before);

        if (s instanceof JAssignStmt) {
            JAssignStmt assign = (JAssignStmt) s;
            Value lhs = assign.getLeftOp();
            Value rhs = assign.getRightOp();

            // only track local-variable definitions
            if (lhs instanceof Local) {
                Local definedLocal = (Local) lhs;
                ConstValue rhsValue = evaluateRhs(rhs, before);
                after.put(definedLocal, rhsValue);
            }
            // if lhs is a field/array ref — no local is defined,
            // constAfter stays the same as constBefore

        } else if (s instanceof JIdentityStmt) {
            // $v := @this / @parameterN / @caughtexception
            // we cannot know what value the caller passed
            // so mark the local as BOTTOM (non-constant)
            JIdentityStmt identity = (JIdentityStmt) s;
            Value lhs = identity.getLeftOp();
            if (lhs instanceof Local) {
                after.put((Local) lhs, ConstValue.bottom());
            }
        }

        return after;
    }

    // ----------------------------------------------------------------
    // E: evaluate an RHS expression given the current constant state
    //
    //    If the expression evaluates to a known constant → CONST(c)
    //    If it depends on a non-constant local → BOTTOM
    //    If it involves an invoke or unknown construct → BOTTOM
    // ----------------------------------------------------------------
    private ConstValue evaluateRhs(Value rhs,
                                       Map<Local, ConstValue> state) {

        // ---- literal constants from Jimple ----

        if (rhs instanceof IntConstant) {
            return ConstValue.ofConstant(
                    ((IntConstant) rhs).getValue());
        }
        if (rhs instanceof LongConstant) {
            return ConstValue.ofConstant(
                    ((LongConstant) rhs).getValue());
        }
        if (rhs instanceof FloatConstant) {
            return ConstValue.ofConstant(
                    ((FloatConstant) rhs).getValue());
        }
        if (rhs instanceof DoubleConstant) {
            return ConstValue.ofConstant(
                    ((DoubleConstant) rhs).getValue());
        }
        if (rhs instanceof StringConstant) {
            return ConstValue.ofConstant(
                    ((StringConstant) rhs).getValue());
        }
        if (rhs instanceof NullConstant) {
            return ConstValue.ofConstant(null);
        }

        // ---- local variable read ----
        // $v = $u  →  the value of $v is whatever $u currently holds
        if (rhs instanceof Local) {
            Local srcLocal = (Local) rhs;
            return state.getOrDefault(srcLocal, ConstValue.bottom());
        }

        // ---- arithmetic binary expressions ----
        // try to fold: if both operands are constants, compute result
        if (rhs instanceof AbstractBinopExpr) {
            AbstractBinopExpr binop = (AbstractBinopExpr) rhs;
            ConstValue left  = evaluateRhs(binop.getOp1(), state);
            ConstValue right = evaluateRhs(binop.getOp2(), state);

            if (left.isConstant() && right.isConstant()) {
                Object foldedResult =
                        tryFold(binop, left.getValue(), right.getValue());
                if (foldedResult != null) {
                    return ConstValue.ofConstant(foldedResult);
                }
            }
            // if either operand is non-constant → result is non-constant
            return ConstValue.bottom();
        }

        // ---- negation (unary minus) ----
        if (rhs instanceof JNegExpr) {
            ConstValue operand =
                    evaluateRhs(((JNegExpr) rhs).getOp(), state);
            if (operand.isConstant()) {
                Object neg = tryNegate(operand.getValue());
                if (neg != null) return ConstValue.ofConstant(neg);
            }
            return ConstValue.bottom();
        }

        // ---- cast expression ----
        // $v = (SomeType) $u  →  conservatively BOTTOM
        // (value may change due to narrowing casts)
        if (rhs instanceof JCastExpr) {
            return ConstValue.bottom();
        }

        // ---- invoke, new, field read, array read, instanceof ----
        // all are conservatively BOTTOM — we cannot determine
        // their values statically in an intra-procedural analysis
        return ConstValue.bottom();
    }

    // ----------------------------------------------------------------
    // F: constant folding for binary operations
    //    Returns the folded result, or null if we cannot fold.
    // ----------------------------------------------------------------
    private Object tryFold(AbstractBinopExpr binop,
                            Object leftVal, Object rightVal) {
        try {
            // integer arithmetic
            if (leftVal instanceof Integer && rightVal instanceof Integer) {
                int l = (Integer) leftVal;
                int r = (Integer) rightVal;

                if (binop instanceof JAddExpr)  return l + r;
                if (binop instanceof JSubExpr)  return l - r;
                if (binop instanceof JMulExpr)  return l * r;
                if (binop instanceof JDivExpr  && r != 0) return l / r;
                if (binop instanceof JRemExpr  && r != 0) return l % r;
                if (binop instanceof JAndExpr)  return l & r;
                if (binop instanceof JOrExpr)   return l | r;
                if (binop instanceof JXorExpr)  return l ^ r;
                if (binop instanceof JShlExpr)  return l << r;
                if (binop instanceof JShrExpr)  return l >> r;
                if (binop instanceof JUshrExpr) return l >>> r;
                if (binop instanceof JEqExpr)   return (l == r) ? 1 : 0;
                if (binop instanceof JNeExpr)   return (l != r) ? 1 : 0;
                if (binop instanceof JLtExpr)   return (l <  r) ? 1 : 0;
                if (binop instanceof JLeExpr)   return (l <= r) ? 1 : 0;
                if (binop instanceof JGtExpr)   return (l >  r) ? 1 : 0;
                if (binop instanceof JGeExpr)   return (l >= r) ? 1 : 0;
            }

            // double arithmetic
            if (leftVal instanceof Double && rightVal instanceof Double) {
                double l = (Double) leftVal;
                double r = (Double) rightVal;

                if (binop instanceof JAddExpr) return l + r;
                if (binop instanceof JSubExpr) return l - r;
                if (binop instanceof JMulExpr) return l * r;
                if (binop instanceof JDivExpr) return l / r;
                if (binop instanceof JRemExpr) return l % r;
            }

            // float arithmetic
            if (leftVal instanceof Float && rightVal instanceof Float) {
                float l = (Float) leftVal;
                float r = (Float) rightVal;

                if (binop instanceof JAddExpr) return l + r;
                if (binop instanceof JSubExpr) return l - r;
                if (binop instanceof JMulExpr) return l * r;
                if (binop instanceof JDivExpr) return l / r;
            }

            // long arithmetic
            if (leftVal instanceof Long && rightVal instanceof Long) {
                long l = (Long) leftVal;
                long r = (Long) rightVal;

                if (binop instanceof JAddExpr) return l + r;
                if (binop instanceof JSubExpr) return l - r;
                if (binop instanceof JMulExpr) return l * r;
                if (binop instanceof JDivExpr && r != 0) return l / r;
            }

        } catch (Exception e) {
            // arithmetic error (e.g. division by zero) — treat as BOTTOM
        }

        return null; // cannot fold
    }

    // ----------------------------------------------------------------
    // G: constant negation for unary minus
    // ----------------------------------------------------------------
    private Object tryNegate(Object val) {
        if (val instanceof Integer) return -(Integer) val;
        if (val instanceof Long)    return -(Long)    val;
        if (val instanceof Float)   return -(Float)   val;
        if (val instanceof Double)  return -(Double)  val;
        return null;
    }

    // ----------------------------------------------------------------
    // H: helper — collect all statements as a stable list
    // ----------------------------------------------------------------
    private List<Stmt> getStatements() {
        List<Stmt> stmts = new ArrayList<>();
        for (Stmt s : cfg.getNodes()) {
            stmts.add(s);
        }
        return stmts;
    }

    // ----------------------------------------------------------------
    // public query methods — used by the dead variable analysis
    // ----------------------------------------------------------------

    /**
     * Returns the constant state of all locals
     * just BEFORE statement s executes.
     */
    public Map<Local, ConstValue> getConstBefore(Stmt s) {
        Map<Local, ConstValue> result = constBefore.get(s);
        if (result == null) return Collections.emptyMap();
        return result;
    }

    /**
     * Returns the constant state of all locals
     * just AFTER statement s executes.
     */
    public Map<Local, ConstValue> getConstAfter(Stmt s) {
        Map<Local, ConstValue> result = constAfter.get(s);
        if (result == null) return Collections.emptyMap();
        return result;
    }

    /**
     * Convenience: is local $v a known constant BEFORE s?
     */
    public boolean isConstantBefore(Stmt s, Local v) {
        Map<Local, ConstValue> before = constBefore.get(s);
        if (before == null) return false;
        ConstValue cv = before.get(v);
        return cv != null && cv.isConstant();
    }

    /**
     * Convenience: what constant value does $v hold BEFORE s?
     * Returns null if not a constant.
     */
    public Object getConstantValueBefore(Stmt s, Local v) {
        Map<Local, ConstValue> before = constBefore.get(s);
        if (before == null) return null;
        ConstValue cv = before.get(v);
        if (cv == null || !cv.isConstant()) return null;
        return cv.getValue();
    }
    
}
