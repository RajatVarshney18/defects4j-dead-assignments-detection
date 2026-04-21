package com.inter.ipaco;

import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.options.Options;

import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.util.*;


public class Main {
    public static void main(String[] args) {
        // We require the path to the target .class files
        if (args.length == 0) {
            System.err.println("Usage: java com.intra.ipaco.Main <target-directory> <path-to-rt.jar>");
            return;
        }

        String targetPath = args[0];
        String rtJarPath = args.length > 1 ? args[1] : null; // // For Java 8 targets, Soot needs the runtime library to resolve base classes

        soot.G.reset();

        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_prepend_classpath(true);

        String classPath = targetPath;
        if (rtJarPath != null && !rtJarPath.isEmpty()) {
            classPath += File.pathSeparator + rtJarPath;
        }
        Options.v().set_soot_classpath(classPath);

        // Tell soot which directory to analyze
        Options.v().set_process_dir(Collections.singletonList(targetPath));

        // Disable the generation of new .class files (we only want analysis)
        Options.v().set_output_format(Options.output_format_none);

        Options.v().setPhaseOption("cg.spark", "on"); // Enable Spark for points-to analysis
        Options.v().setPhaseOption("cg.spark", "vta:true"); // Use Variable Type Analysis for better precision in this context

         // Inject our CFG Extractor into the WJTP (Whole-Program Jimple Transformation Pack)

        // Inject the new Inter-procedural Transformer
        AnalysisTransformer transformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.deadcode", transformer));

        Scene.v().loadNecessaryClasses();

        // --- ADD THIS NEW BLOCK: The "Library Mode" Entry Point Generator ---
            System.out.println("Configuring Library Entry Points...");
            List<SootMethod> entryPoints = new ArrayList<>();

            for (SootClass sootClass : Scene.v().getApplicationClasses()) {
                // We only care about concrete, public classes that external users can access
                if (sootClass.isPublic() && !sootClass.isInterface() && !sootClass.isAbstract()) {
                    for (SootMethod method : sootClass.getMethods()) {
                        // Treat every concrete public method as a valid starting point
                        if (method.isPublic() && method.isConcrete()) {
                            entryPoints.add(method);
                        }
                    }
                } 
            }

            System.out.println("Found " + entryPoints.size() + " public entry points. Building global graph...");
            // Force Soot to use our massive list of entry points
            Scene.v().setEntryPoints(entryPoints);

        PackManager.v().runPacks(); // This will execute our AnalysisTransformer as part of the WJTP phase, which will perform the inter-procedural analysis and print out any dead assignments it finds.
    }
}