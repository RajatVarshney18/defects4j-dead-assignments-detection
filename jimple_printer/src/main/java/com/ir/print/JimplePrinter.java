package com.ir.print;

import sootup.core.model.SootMethod;
import sootup.core.types.ClassType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;
 
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.Collectors;

public class JimplePrinter {
   public static void main(String[] args) throws Exception {
 
        // ── 1. Parse arguments ─────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Usage: JimplePrinter <classdir> [outdir]");
            System.err.println("  classdir - root directory that contains compiled .class files");
            System.err.println("  outdir   - (optional) directory to write .jimple files into");
            System.exit(1);
        }
 
        String classDir = args[0];
        String outDir   = args.length > 1 ? args[1] : null;
 
        Path classDirPath = Paths.get(classDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(classDirPath)) {
            System.err.println("ERROR: not a directory: " + classDirPath);
            System.exit(1);
        }
 
        if (outDir != null) {
            Files.createDirectories(Paths.get(outDir));
        }
 
        System.out.println("=== SootUp 2.0.0 Jimple Printer ===");
        System.out.println("Class directory : " + classDirPath);
        System.out.println("Output          : " + (outDir != null ? outDir : "stdout"));
        System.out.println();
 
        // ── 2. Build the SootUp JavaView ───────────────────────────────────
        //
        //  JavaClassPathAnalysisInputLocation reads compiled .class files
        //  from a directory (or JAR).  The second argument controls whether
        //  byte-code bodies are eagerly or lazily loaded; ON_DEMAND is fine
        //  for printing all classes sequentially.
        JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(classDirPath.toString());
 
        JavaView view = new JavaView(inputLocation);
 
        // ── 3. Iterate every class ─────────────────────────────────────────
        Collection<JavaSootClass> classes = view.getClasses().collect(Collectors.toList());
 
 
        if (classes.isEmpty()) {
            System.err.println("WARNING: No classes found in " + classDirPath);
            System.err.println("Make sure you compiled the project first (defects4j compile).");
            System.exit(1);
        }
 
        int classCount  = 0;
        int methodCount = 0;
 
        for (JavaSootClass clazz : classes) {
 
            ClassType classType = clazz.getType();
            String    className = classType.getFullyQualifiedName();
 
            StringBuilder sb = new StringBuilder();
 
            // ── Class header ──────────────────────────────────────────────
            sb.append("// ").append("=".repeat(72)).append("\n");
            sb.append("// CLASS : ").append(className).append("\n");
            sb.append("// ").append("=".repeat(72)).append("\n\n");
 
            // Modifiers + class declaration line
            sb.append(clazz.getModifiers()).append(" class ").append(className);
 
            clazz.getSuperclass().ifPresent(sc ->
                    sb.append(" extends ").append(sc.getFullyQualifiedName()));
 
            if (!clazz.getInterfaces().isEmpty()) {
                sb.append(" implements ");
                sb.append(String.join(", ",
                        clazz.getInterfaces().stream()
                             .map(ClassType::getFullyQualifiedName)
                             .collect(Collectors.toList())));
            }
            sb.append(" {\n\n");
 
            // ── Methods ───────────────────────────────────────────────────
            for (SootMethod method : clazz.getMethods()) {
                sb.append("    // ---- method: ")
                  .append(method.getSignature())
                  .append(" ----\n");
 
                if (method.hasBody()) {
                    // body.toString() gives the full Jimple text
                    String bodyText = method.getBody().toString();
                    // indent every line for readability
                    for (String line : bodyText.split("\n")) {
                        sb.append("    ").append(line).append("\n");
                    }
                } else {
                    sb.append("    // (abstract or native – no body)\n");
                }
                sb.append("\n");
                methodCount++;
            }
 
            sb.append("}\n");
 
            // ── 4. Output ─────────────────────────────────────────────────
            if (outDir == null) {
                // Print to stdout
                System.out.println(sb);
            } else {
                // Write to   outDir/<pkg/subpkg/ClassName>.jimple
                String relativePath = className.replace('.', '/') + ".jimple";
                Path   outFile      = Paths.get(outDir, relativePath);
                Files.createDirectories(outFile.getParent());
 
                try (PrintWriter pw = new PrintWriter(new FileWriter(outFile.toFile()))) {
                    pw.print(sb);
                }
                System.out.println("Written: " + outFile);
            }
 
            classCount++;
        }
 
        System.out.println();
        System.out.printf("Done. %d class(es), %d method(s) processed.%n",
                classCount, methodCount);
    }
}
