package com.inter.ipaco;

import soot.*;
import soot.jimple.AssignStmt;
import java.util.*;

public class AnalysisTransformer extends SceneTransformer {
    
    private final Map<SootMethod, Set<Integer>> usedParametersMap = new HashMap<>();
    private int deadAssignmentsCount = 0;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        System.out.println("=== Phase 3: Inter-procedural Analysis Started ===");
        
        PointsToAnalysis pta = Scene.v().getPointsToAnalysis();

        // 1. Gather all valid application methods to analyze
        List<SootMethod> validMethods = new ArrayList<>();
        for (SootClass sootClass : Scene.v().getApplicationClasses()) {
            for (SootMethod method : sootClass.getMethods()) {
                if (method.isConcrete() && !method.getDeclaringClass().isLibraryClass()) {
                    method.retrieveActiveBody(); // Force Jimple generation
                    validMethods.add(method);
                }
            }
        }

        // ------------------------------
        System.out.println("Total valid methods found: " + validMethods.size());
        for (SootMethod m : validMethods) {
            if (m.getName().equals("calculate") || m.getName().equals("runProcess")) {
                System.out.println("\n--- Jimple IR for " + m.getName() + " ---");
                System.out.println(m.getActiveBody().toString());
            }
        }
        System.out.println("----------------------------------------\n");
        // ------------------------------

        System.out.println("Computing Global Inter-procedural Summaries (Fixed-Point)...");
        
        // 2. PHASE 1: Global Fixed-Point Iteration (Bypasses the need for CallGraphGraph/SCC)
        boolean changed;
        do {
            changed = false;
            for (SootMethod method : validMethods) {
                // Using your exact class name here
                interproceduralLiveness liveness = 
                        new interproceduralLiveness(method, pta, usedParametersMap);

                Set<Integer> newUsedParams = liveness.getUsedParameters();
                Set<Integer> oldUsedParams = usedParametersMap.getOrDefault(method, new HashSet<>());

                // Due to monotonicity, liveness sets only grow. If they grow, keep iterating.
                if (!oldUsedParams.equals(newUsedParams)) {
                    usedParametersMap.put(method, newUsedParams);
                    changed = true; 
                }
            }
        } while (changed);

        System.out.println("Sweeping Global Graph for Dead Code...");
        
        // 3. PHASE 2: Final Sweep
        for (SootMethod method : validMethods) {
            // Using your exact class name here
            interproceduralLiveness finalLiveness = 
                    new interproceduralLiveness(method, pta, usedParametersMap);

            for (Unit unit : method.getActiveBody().getUnits()) {
                if (unit instanceof AssignStmt) {
                    AssignStmt assign = (AssignStmt) unit;
                    if (!finalLiveness.getFlowAfter(unit).contains(assign.getLeftOp())) {
                        System.out.println("[DEAD ASSIGNMENT] " + method.getDeclaringClass().getShortName() + 
                                           "." + method.getName() + " (Line " + unit.getJavaSourceStartLineNumber() + "): " + unit);
                        deadAssignmentsCount++;
                    }
                }
            }
        }
        System.out.println("=== Analysis Complete. Total Dead Assignments: " + deadAssignmentsCount + " ===");
    }
}