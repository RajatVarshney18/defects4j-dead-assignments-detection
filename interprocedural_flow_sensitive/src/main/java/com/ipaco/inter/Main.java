package com.ipaco.inter;

import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.options.Options;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java com.inter.ipaco.Main <target-dir> <rt-jar-path> <TestClass::TestMethod>");
            return;
        }

        String targetPath = args[0];     // Expects: target/classes:target/test-classes
        String rtJarPath = args[1];      // Expects: JRE rt.jar path
        String failingTestArg = args[2]; // Expects: ClassName::MethodName

        // Reset and configure global Soot environment
        soot.G.reset();
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_prepend_classpath(true);

        // =====================================================================
        // THE ARCHITECTURE FIX: The "Process Dir" Switch
        // This natively forces Soot to treat all targeted folders as Application Code.
        // It automatically generates Jimple bodies and ensures SPARK traces every edge.
        // =====================================================================
        List<String> processDirs = new ArrayList<>(Arrays.asList(targetPath.split(File.pathSeparator)));
        Options.v().set_process_dir(processDirs);

        // Set the Classpath for resolving external Java libraries (like rt.jar)
        String classPath = targetPath;
        if (rtJarPath != null && !rtJarPath.isEmpty()) {
            classPath += File.pathSeparator + rtJarPath;
        }
        Options.v().set_soot_classpath(classPath);

        // Enable SPARK Pointer Analysis (Context-Sensitivity strictly OFF)
        Options.v().setPhaseOption("cg", "safe-for-name-clashes:true");
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().setPhaseOption("cg.spark", "enabled:true");

        Options.v().setPhaseOption("cg", "all-reachable:true");

        // Ensure Jimple retains original variable names
        Options.v().setPhaseOption("jb", "use-original-names:true");
        Options.v().set_keep_line_number(true);

        // Inject our custom Inter-procedural Phase 3 Transformer
        AnalysisTransformer transformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.deadcode", transformer));

        // Load classes ONLY AFTER process_dir and classpath are set
        Scene.v().loadNecessaryClasses();

        // --- Automated Targeted Test Entry Point Setup ---
        System.out.println("Configuring Targeted Test Entry Point...");
        
        String[] parts = failingTestArg.split("::");
        if (parts.length != 2) {
            System.err.println("Error: Test argument malformed: " + failingTestArg);
            return;
        }
        
        String testClassName = parts[0];
        String testMethodName = parts[1];

        // Retrieve the class natively from the Scene 
        SootClass testClass = Scene.v().getSootClass(testClassName);
        SootMethod entryMethod = testClass.getMethodByName(testMethodName);
        
        // =====================================================================
        // THE FINAL FIX: Preserving the JVM Bootstraps
        // We must include Soot's implicit entry points so SPARK knows 
        // classes are legally allowed to be instantiated and initialized!
        // =====================================================================
        List<SootMethod> combinedEntryPoints = new ArrayList<>();
        
        // 1. Pull in the implicit JVM methods (<clinit>, Object.<init>, System)
        combinedEntryPoints.addAll(soot.EntryPoints.v().implicit());
        
        // 2. Add our specific target test method
        combinedEntryPoints.add(entryMethod);
        
        // 3. Set the safely combined list
        Scene.v().setEntryPoints(combinedEntryPoints);
        System.out.println("Entry point set successfully with JVM Bootstraps intact.");

        // Run the analysis! 
        PackManager.v().runPacks(); 
    }
}