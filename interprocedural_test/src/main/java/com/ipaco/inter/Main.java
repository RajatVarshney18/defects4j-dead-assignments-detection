package com.ipaco.inter;

import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.options.Options;

import java.io.File;
import java.util.Collections;


public class Main {
    public static void main(String[] args) {
        // Ensure all arguments are provided
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

        // Set the Classpath
        String classPath = targetPath;
        if (rtJarPath != null && !rtJarPath.isEmpty()) {
            classPath += File.pathSeparator + rtJarPath;
        }
        Options.v().set_soot_classpath(classPath);

        // Enable SPARK Pointer Analysis
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().setPhaseOption("cg.spark", "vta:true");

        // Ensure Jimple retains original variable names for readability
        Options.v().setPhaseOption("jb", "use-original-names:true");
        Options.v().set_keep_line_number(true);

        // Inject our custom Inter-procedural Phase 3 Transformer
        AnalysisTransformer transformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.deadcode", transformer));

        //Load necessary classes before running packs to ensure our transformer has access to the full program representation
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

        System.out.println("Target Class: " + testClassName);
        System.out.println("Target Method: " + testMethodName);

        // Force Soot to resolve the test class as an Application class
        SootClass testClass = Scene.v().forceResolve(testClassName, SootClass.BODIES);
        testClass.setApplicationClass(); 

        // Extract the specific method and set it as the sole entry point for SPARK
        SootMethod entryMethod = testClass.getMethodByName(testMethodName);
        Scene.v().setEntryPoints(Collections.singletonList(entryMethod));
        System.out.println("Entry point set successfully.");
        // ---------------------------------------------------

        PackManager.v().runPacks(); // This will execute our AnalysisTransformer as part of the WJTP phase, which will perform the inter-procedural analysis and print out any dead assignments it finds.
    }
}