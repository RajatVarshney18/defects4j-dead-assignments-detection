package com.intra.ipaco;

import soot.PackManager;
import soot.Scene;
import soot.Transform;
import soot.options.Options;
import java.util.Collections;
import java.io.File;


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

        // Inject our CFG Extractor into the WJTP (Whole-Program Jimple Transformation Pack)
        CFGExtractor cfgExtractor = new CFGExtractor();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.cfg_test", cfgExtractor));


        // Load the classes and execute the packs
        Scene.v().loadNecessaryClasses();
        PackManager.v().runPacks();
    }
}