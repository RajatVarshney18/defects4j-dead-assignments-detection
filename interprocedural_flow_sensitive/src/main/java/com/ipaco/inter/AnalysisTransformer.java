package com.ipaco.inter;

import soot.*;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;

import java.util.*;

public class AnalysisTransformer extends SceneTransformer {
    
    private final Map<SootMethod, Set<Integer>> usedParametersMap = new HashMap<>();
    // --- Object-Sensitive Field Tracker ---
    private final Map<SootField, List<PointsToSet>> globalReadAliases = new HashMap<>();
    private int deadAssignmentsCount = 0;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        System.out.println("=== Inter-procedural Analysis Started ===");
        
        PointsToAnalysis pta = Scene.v().getPointsToAnalysis();

        // 1. Gather all valid methods from the global scene
        List<SootMethod> validMethods = new ArrayList<>();
        for (SootClass sootClass : Scene.v().getClasses()) {
            String className = sootClass.getName();

            // FILTER: We only want to analyze the Defects4J target projects 
            // (e.g., Apache Commons Lang, JFreeChart, Commons Math)
            if (className.startsWith("org.jfree.") || className.startsWith("org.apache.commons.") || className.startsWith("com.ipaco.test.")) {
                
                // EXCLUDE NOISE: Skip the JUnit test files so we don't get false positives
                if (className.endsWith("Test") || className.endsWith("Tests") || className.contains(".junit.")) {
                    continue;
                }

                // Gather the concrete methods from the actual library
                for (SootMethod method : sootClass.getMethods()) {
                    if (method.isConcrete() && method.hasActiveBody()) {
                        validMethods.add(method);
                    }
                }
            }
        }
        
        System.out.println("Total valid library methods found: " + validMethods.size());
        for (SootMethod m : validMethods) {
                System.out.println("\n--- Jimple IR for " + m.getName() + " ---");
                System.out.println(m.getActiveBody().toString());
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
                        new interproceduralLiveness(method, pta, usedParametersMap, globalReadAliases);

                Set<Integer> newUsedParams = liveness.getUsedParameters();
                Set<Integer> oldUsedParams = usedParametersMap.getOrDefault(method, new HashSet<>());

                // Due to monotonicity, liveness sets only grow. If they grow, keep iterating.
                if (!oldUsedParams.equals(newUsedParams)) {
                    usedParametersMap.put(method, newUsedParams);
                    changed = true; 
                }
            }
        } while (changed);
        
       System.out.println("2. Sweeping Global Graph for Dead Code (Filtered)...");
        
        // 3. PHASE 2: Final Sweep with Noise Filters
        for (SootMethod method : validMethods) {
            String className = method.getDeclaringClass().getName();
            
            // FILTER 1: Ignore test classes, but allow any application package
            if (className.endsWith("Test") || className.endsWith("Tests") || className.contains(".junit.")) {
                continue;
            }

            interproceduralLiveness finalLiveness = 
                    new interproceduralLiveness(method, pta, usedParametersMap, globalReadAliases);

            // 3. PHASE 2: Final Sweep with Noise Filters
            for (Unit unit : method.getActiveBody().getUnits()) {
                if (unit instanceof AssignStmt) {
                    AssignStmt assign = (AssignStmt) unit;
                    Value leftOp = assign.getLeftOp();
                    Value rightOp = assign.getRightOp();

                    // THE FIX: Ensure we only filter out local stack variables, not heap fields!
                    boolean isTemporary = (leftOp instanceof soot.Local) && leftOp.toString().startsWith("$");
                    boolean isMethodCall = rightOp instanceof soot.jimple.InvokeExpr;

                    if (isTemporary && !isMethodCall) {
                        continue; 
                    }

                    // --- OBJECT-SENSITIVE HEAP CHECK ---
                    boolean isLiveField = false;
                    if (leftOp instanceof InstanceFieldRef) {
                        InstanceFieldRef fieldWrite = (InstanceFieldRef) leftOp;
                        SootField assignedField = fieldWrite.getField();
                        
                        List<PointsToSet> readSets = globalReadAliases.get(assignedField);
                        if (readSets != null && fieldWrite.getBase() instanceof Local) {
                            PointsToSet writeSet = pta.reachingObjects((Local) fieldWrite.getBase());
                            // ADD THIS LINE: Expose the hidden Soot Engine!
                            System.out.println("[DEBUG ENGINE] Using: " + writeSet.getClass().getSimpleName());
                            for (PointsToSet readSet : readSets) {
                                if (writeSet.hasNonEmptyIntersection(readSet)) {
                                    isLiveField = true;
                                    break;
                                }
                            }
                        }
                    }

                    // Using getFlowAfter to check the state BELOW the assignment!
                    if (!isLiveField && !finalLiveness.getFlowAfter(unit).contains(leftOp)) {
                        int lineNum = unit.getJavaSourceStartLineNumber();
                        String shortClassName = method.getDeclaringClass().getShortName();
                        String varLabel = isTemporary ? "[Ignored Method Return]" : "Variable '" + leftOp + "'";
                        
                        System.out.println("[DEAD] " + shortClassName + ".java:" + lineNum + 
                                           " | Method: " + method.getName() + "()" +
                                           " | " + varLabel);
                        deadAssignmentsCount++;
                    }
                } 
                // =========================================================
                // FIX 2: Catch the compiler-optimized dead store!
                // =========================================================
                else if (unit instanceof soot.jimple.InvokeStmt) {
                    soot.jimple.InvokeStmt invokeStmt = (soot.jimple.InvokeStmt) unit;
                    SootMethod callee = invokeStmt.getInvokeExpr().getMethod();
                    
                    String declaringClass = callee.getDeclaringClass().getName();
                    
                    // If a pure math function is called but not saved, it is mathematically useless
                    if (declaringClass.equals("java.lang.Math")) {
                        int lineNum = unit.getJavaSourceStartLineNumber();
                        String shortClassName = method.getDeclaringClass().getShortName();
                        
                        System.out.println("[DEAD] " + shortClassName + ".java:" + lineNum + 
                                           " | Method: " + method.getName() + "()" +
                                           " | [Useless Pure Function] | Source: " + invokeStmt.getInvokeExpr());
                        deadAssignmentsCount++;
                    }
                }
            }
        }
        System.out.println("=== Analysis Complete. True Dead Assignments: " + deadAssignmentsCount + " ===");
    }
}