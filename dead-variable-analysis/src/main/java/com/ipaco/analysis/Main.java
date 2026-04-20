package com.ipaco.analysis;

import sootup.core.model.SootMethod;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

import java.util.Collection;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) {

        if (args.length < 2) {
            System.err.println(
                "Usage: java -jar dva.jar <class-dir> <output-file>");
            System.exit(1);
        }

        String classPath  = args[0];
        String outputFile = args[1];

        System.out.println("=== Dead Variable Analysis — Pass 1 ===");
        System.out.println("Target : " + classPath);
        System.out.println("Output : " + outputFile);
        System.out.println();

        ResultDumper dumper;
        try {
            dumper = new ResultDumper(outputFile);
        } catch (Exception e) {
            System.err.println("Cannot open output file: " + e.getMessage());
            System.exit(1);
            return;
        }

        JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(classPath);
        JavaView view = new JavaView(inputLocation);

        Collection<JavaSootClass> classes = view.getClasses().collect(Collectors.toList());
        System.out.println("Classes found : " + classes.size());
        System.out.println();

        int totalMethods = 0;

        for (JavaSootClass sootClass : classes) {

            String className = sootClass.getType().getFullyQualifiedName();

            if (className.startsWith("java.")
                    || className.startsWith("javax.")
                    || className.startsWith("sun.")
                    || className.startsWith("com.sun.")) {
                continue;
            }

            String sourceFile = resolveSourceFile(sootClass);

            for (SootMethod method : sootClass.getMethods()) {

                if (!method.hasBody()) continue;

                totalMethods++;

                try {
                    DeadVariableAnalysis dva =
                            new DeadVariableAnalysis(method);
                    AnalysisResult result =
                            dva.run(className, sourceFile);

                    dumper.dump(result);

                    if (!result.getDeadStatements().isEmpty()) {
                        System.out.println("  "
                                + result.getDeadStatements().size()
                                + " finding(s) : "
                                + method.getSignature());
                    }

                } catch (Exception e) {
                    System.err.println("  Skipped: "
                            + method.getSignature()
                            + " — " + e.getMessage());
                }
            }
        }

        dumper.close(totalMethods);
        System.out.println("Methods analyzed : " + totalMethods);
    }

    private static String resolveSourceFile(JavaSootClass sootClass) {
        String fullName = sootClass.getType().getFullyQualifiedName();
        int dollar = fullName.indexOf('$');
        if (dollar >= 0) fullName = fullName.substring(0, dollar);
        int dot = fullName.lastIndexOf('.');
        String simple = (dot >= 0) ? fullName.substring(dot + 1) : fullName;
        return simple + ".java";
    }
}