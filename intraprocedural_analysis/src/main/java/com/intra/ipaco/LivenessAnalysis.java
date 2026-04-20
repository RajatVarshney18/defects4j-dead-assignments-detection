package com.intra.ipaco;

import soot.Local;
import soot.Unit;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.ArraySparseSet;
import soot.toolkits.scalar.BackwardFlowAnalysis;
import soot.toolkits.scalar.FlowSet;

public class LivenessAnalysis extends BackwardFlowAnalysis<Unit, FlowSet<Local>> {

  public LivenessAnalysis(UnitGraph graph) {
        super(graph);
        // This single call triggers the entire fixed-point graph traversal!
        doAnalysis(); 
    }

    // --- 1. LATTICE DEFINITIONS ---
    
    // What is the default state of a node before we analyze it? (Empty Set)
    @Override 
    protected FlowSet<Local> newInitialFlow() { 
        return new ArraySparseSet<>(); 
    }
    
    // What is the state at the exit point of the method? (Empty Set)
    @Override 
    protected FlowSet<Local> entryInitialFlow() { 
        return new ArraySparseSet<>(); 
    }
    
    // When control flows merge (e.g., after an if/else), how do we combine them?
    // Liveness is a "MAY" analysis, so we use UNION.
    @Override 
    protected void merge(FlowSet<Local> in1, FlowSet<Local> in2, FlowSet<Local> out) {
        in1.union(in2, out);
    }
    
    @Override 
    protected void copy(FlowSet<Local> source, FlowSet<Local> dest) {
        source.copy(dest);
    }

    // --- 2. THE TRANSFER FUNCTION (Gen / Kill) ---
    
    @Override
    protected void flowThrough(FlowSet<Local> in, Unit unit, FlowSet<Local> out) {
        // Because it's a Backward analysis:
        // 'in' is the flow coming from the bottom (conceptually LiveOut)
        // 'out' is the flow going to the top (conceptually LiveIn)
        
        in.copy(out); // Start with LiveIn = LiveOut

        // STEP A: KILL (If a variable is overwritten, its previous value is dead)
        if (unit instanceof AssignStmt) {
            AssignStmt assign = (AssignStmt) unit;
            if (assign.getLeftOp() instanceof Local) {
                out.remove((Local) assign.getLeftOp()); // KILL
            }
        }

        // STEP B: GEN (If a variable is read, it becomes live)
        // Jimple's getUseBoxes() gets all variables on the RHS, in conditions, or in returns.
        for (ValueBox box : unit.getUseBoxes()) {
            if (box.getValue() instanceof Local) {
                out.add((Local) box.getValue()); // GEN
            }
        }
    }
}
