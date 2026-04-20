package com.intra.ipaco;

import soot.*;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import java.util.Map;

// SceneTransformer allows us to look at the entire program at once
public class CFGExtractor extends SceneTransformer {

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        System.out.println("=== CFG Extraction Started ===");

        // Iterate through all classes that belong to the target application
        for (SootClass sootClass : Scene.v().getApplicationClasses()) {
            
            // Iterate through all methods within the class
            for (SootMethod method : sootClass.getMethods()) {

                // We can only analyze methods that have an active body (not abstract or native)
                if (method.isConcrete() && method.getName().equals("calculate")) {
                    
                    System.out.println("\n*** Analyzing Method: " + method.getSignature() + " ***");

                    // Retrieve the actual body containing the Jimple statements
                    Body body = method.retrieveActiveBody();
                    UnitGraph cfg = new ExceptionalUnitGraph(body);

                    System.out.println("\n=== Complete Jimple IR ===");
                    System.out.println(body.toString()); 
                    System.out.println("==========================\n");
   
                   
                    // // Print the CFG to verify it works
                    // // We print the "Heads" (entry points) of the graph
                    // System.out.println("CFG Entry Points (Heads):");
                    // for (Unit head : cfg.getHeads()) {
                    //     System.out.println("  -> " + head.toString());
                    // }
                    
                    // // Print the "Tails" (exit points / return statements) of the graph
                    // System.out.println("CFG Exit Points (Tails):");
                    // for (Unit tail : cfg.getTails()) {
                    //     System.out.println("  -> " + tail.toString());
                    // }

                    // // For this initial test, we'll stop after successfully extracting one method's CFG
                    // // to keep the console output readable.
                    // System.out.println("=== Successfully extracted CFG. Stopping test. ===");

                    // Run the Data-Flow Analysis
                        LivenessAnalysis liveness = new LivenessAnalysis(cfg);

                    //  Print the results chronologically statement by statement
                        System.out.println("--- Liveness Flow ---");
                            for (soot.Unit unit : body.getUnits()) {
                            System.out.println("Statement: " + unit.toString());
        
                            // FlowBefore = Variables needed BEFORE this statement executes (LiveIn) FlowAfter = Variables needed AFTER this statement executes (LiveOut)
                            System.out.println("   Live-In  : " + liveness.getFlowBefore(unit));
                            System.out.println("   Live-Out : " + liveness.getFlowAfter(unit));
                            System.out.println("---------------------");
                        }

                        System.out.println("=== Successfully ran liveness. Stopping test. ===");
                    return; 
                }
            }
        }
        System.out.println("=== Finished ===");
    }
}