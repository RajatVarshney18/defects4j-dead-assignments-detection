package com.inter.ipaco;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ArraySparseSet;
import soot.toolkits.scalar.BackwardFlowAnalysis;
import soot.toolkits.scalar.FlowSet;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class interproceduralLiveness extends BackwardFlowAnalysis<Unit, FlowSet<Value>> {

    private final PointsToAnalysis pta;
    private final Map<SootMethod, Set<Integer>> globalParameterUsage;

    // Tracks the parameter indices that THIS method actually uses
    private final Set<Integer> usedParameters = new HashSet<>();

    public interproceduralLiveness(SootMethod method, PointsToAnalysis pta, Map<SootMethod, Set<Integer>> globalMap) {
        super(new ExceptionalUnitGraph(method.getActiveBody()));
        this.pta = pta;
        this.globalParameterUsage = globalMap;
        doAnalysis(); 
    }

    @Override protected FlowSet<Value> newInitialFlow() { return new ArraySparseSet<>(); }
    @Override protected FlowSet<Value> entryInitialFlow() { return new ArraySparseSet<>(); }
    @Override protected void merge(FlowSet<Value> in1, FlowSet<Value> in2, FlowSet<Value> out) {
        in1.union(in2, out);
    }
    @Override protected void copy(FlowSet<Value> source, FlowSet<Value> dest) {
        source.copy(dest);
    }

    @Override
    protected void flowThrough(FlowSet<Value> in, Unit unit, FlowSet<Value> out) {
        in.copy(out); 
        if (!(unit instanceof Stmt)) return;
        Stmt stmt = (Stmt) unit;

        // 1. Handle Assignments (Locals and Heap)
        if (stmt instanceof AssignStmt) {
            AssignStmt assign = (AssignStmt) stmt;
            Value left = assign.getLeftOp();
            Value right = assign.getRightOp();

            if (left instanceof InstanceFieldRef) {
                // HEAP CONSTRAINT & ALIASING: a.field = b
                InstanceFieldRef fieldWrite = (InstanceFieldRef) left;
                boolean isLive = false;

                // Check if 'a' aliases with any live object needing 'field'
                for (Value liveVar : in) {
                    if (liveVar instanceof InstanceFieldRef) {
                        InstanceFieldRef liveField = (InstanceFieldRef) liveVar;
                        if (fieldWrite.getField().equals(liveField.getField())) {
                            PointsToSet writeSet = pta.reachingObjects((Local) fieldWrite.getBase());
                            PointsToSet liveSet = pta.reachingObjects((Local) liveField.getBase());
                            
                            // If they share memory locations, the write is live!
                            if (writeSet.hasNonEmptyIntersection(liveSet)) {
                                isLive = true;
                                break;
                            }
                        }
                    }
                }
                if (isLive) {
                    addUses(right, out); // Gen
                } else {
                    out.remove(left);    // Kill (Dead field assignment)
                }
            } 
            else if (left instanceof Local) {
                out.remove(left); // Kill
                if (in.contains(left) || stmt.containsInvokeExpr()) {
                    addUses(right, out); // Gen
                }
            }
        }
        // 2. Handle Inter-procedural Method Calls
        else if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            SootMethod callee = invoke.getMethod();

            if (invoke instanceof soot.jimple.InstanceInvokeExpr) {
                soot.jimple.InstanceInvokeExpr instInvoke = (soot.jimple.InstanceInvokeExpr) invoke;
                if (instInvoke.getBase() instanceof Local) {
                    out.add(instInvoke.getBase()); // GEN the base object
                }
            }
            
            // Look up what the callee actually needs
            Set<Integer> usedByCallee = globalParameterUsage.getOrDefault(callee, new HashSet<>());
            
            for (int i = 0; i < invoke.getArgCount(); i++) {
                Value arg = invoke.getArg(i);
                // If the callee uses parameter 'i', the argument becomes live here
                if (arg instanceof Local && usedByCallee.contains(i)) {
                    out.add(arg); 
                }
            }
        }
        // 3. Summarize THIS Method's Parameter Usage
        else if (stmt instanceof IdentityStmt) {
            IdentityStmt idStmt = (IdentityStmt) stmt;
            if (idStmt.getRightOp() instanceof ParameterRef) {
                ParameterRef paramRef = (ParameterRef) idStmt.getRightOp();
                // If the parameter maps to a local that is live, record it
                if (out.contains(idStmt.getLeftOp())) {
                    usedParameters.add(paramRef.getIndex());
                }
            }
        }

        // Generic use handling for branching/returns
        if (stmt instanceof IfStmt || stmt instanceof SwitchStmt || stmt instanceof ReturnStmt) {
            addUses(stmt, out);
        }
    }

    private void addUses(Value value, FlowSet<Value> out) {
        for (ValueBox box : value.getUseBoxes()) {
            if (box.getValue() instanceof Local || box.getValue() instanceof FieldRef) {
                out.add(box.getValue());
            }
        }
    }

    private void addUses(Stmt stmt, FlowSet<Value> out) {
        for (ValueBox box : stmt.getUseBoxes()) {
             if (box.getValue() instanceof Local || box.getValue() instanceof FieldRef) {
                 out.add(box.getValue());
             }
        }
    }

    public Set<Integer> getUsedParameters() { return usedParameters; }

  
}
