package com.ipaco.analysis;

import sootup.core.model.SootMethod;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

import java.util.Collection;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        
        // ----------------------------------------------------------------
        // Step 1: validate input arguments
        // ----------------------------------------------------------------
        if (args.length < 2) {
            System.err.println("Usage: java -jar dva.jar <path-to-class-directory>");
            System.err.println("Example: java -jar dva.jar /path/to/Chart/build");
            System.exit(1);
        }

        String classPath = args[0];
        String outputFile = args[1];

        System.out.println("=== Dead Variable Analysis — Pass 1 ===");
        System.out.println("Target : " + classPath);
        System.out.println();

        // ----------------------------------------------------------------
        // open the result dumper — creates / overwrites the output file
        // ----------------------------------------------------------------
        ResultDumper dumper;
        try {
            dumper = new ResultDumper(outputFile);
        } catch (Exception e) {
            System.err.println("Cannot open output file: " + e.getMessage());
            System.exit(1);
            return;
        }
        // ----------------------------------------------------------------
        // Step 2: create input location
        
        JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(classPath);
        // ----------------------------------------------------------------
        // Step 3: create SootUp view it resolves classes
        // on demand when you call getClasses() or getMethod()
       
        JavaView view = new JavaView(inputLocation);
        // ----------------------------------------------------------------
        // Step 4: collect all classes
        
        Collection<JavaSootClass> classes = view.getClasses().collect(Collectors.toList());

        System.out.println("Classes found : " + classes.size());
        System.out.println();

        int totalMethods        = 0;

        // ----------------------------------------------------------------
        // Step 5: iterate every class and every method

        for (JavaSootClass sootClass : classes) {

            String className = sootClass.getType().getFullyQualifiedName();

            // skip JDK classes that SootUp may pull in as stubs
            if (className.startsWith("java.")
                    || className.startsWith("javax.")
                    || className.startsWith("sun.")
                    || className.startsWith("com.sun.")) {
                continue;
            }

            String sourceFile = resolveSourceFile(sootClass);

            for (SootMethod method : sootClass.getMethods()) {

                // skip abstract / native methods — they have no body
                if (!method.hasBody()) {
                    continue;
                }

                totalMethods++;
                System.out.println("Analyzing : " + className + "." + method.getName());
                 // Step 6: run the analysis on this method
                // --------------------------------------------------------
                try {
                    DeadVariableAnalysis dva =
                            new DeadVariableAnalysis(method);

                    // pass className and sourceFile so result carries context
                    AnalysisResult result =
                            dva.run(className, sourceFile);

                    // dump to file — skips automatically if no dead entries
                    dumper.dump(result);

                    // also print a progress dot to console
                    if (!result.getDeadStatements().isEmpty()) {
                        System.out.println("  found "
                                + result.getDeadStatements().size()
                                + " dead stmt(s) in : "
                                + method.getSignature());
                    }

                } catch (Exception e) {
                    System.err.println("  Skipped : "
                            + method.getSignature()
                            + " — " + e.getMessage());
                }
            }
        }

        // ----------------------------------------------------------------
        // Step 7: summary
        // ----------------------------------------------------------------
        System.out.println("==============================");
        System.out.println("Methods analyzed  : " + totalMethods);

        dumper.close(totalMethods);

        System.out.println();
        System.out.println("Done.");
        System.out.println("==============================");
    
    }

    // ----------------------------------------------------------------
    // helper: get the source file name for a class
    //
    // SootUp 2.0.0 stores the source file in the class's position.
    // If unavailable, we derive it from the class name — e.g.
    // "org.jfree.chart.JFreeChart" -> "JFreeChart.java"
    // For inner classes "Outer$Inner" -> "Outer.java"
    // ----------------------------------------------------------------
    private static String resolveSourceFile(JavaSootClass sootClass) {
        if (sootClass.getClassSource().getSourcePath() != null) {
            return sootClass.getClassSource().getSourcePath().getFileName().toString();
        } else {
            String fullName = sootClass.getType().getFullyQualifiedName();
            String simpleName = fullName.contains(".")
                    ? fullName.substring(fullName.lastIndexOf(".") + 1)
                    : fullName;
            String baseName = simpleName.contains("$")
                    ? simpleName.substring(0, simpleName.indexOf("$"))
                    : simpleName;
            return baseName + ".java";
        }
    }
}
