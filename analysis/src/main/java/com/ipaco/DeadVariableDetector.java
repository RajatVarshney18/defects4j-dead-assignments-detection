package com.ipaco;

import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.types.ClassType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class DeadVariableDetector {
    private static PrintWriter reportWriter;

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        if (args.length < 1) {
            System.err.println("Error: expected at least 1 argument: <classesDir>");
            printUsage();
            return;
        }

        String classesDir = args[0];
        String libDir = args.length >= 2 ? args[1] : null;
        String outputFile = args.length >= 3 ? args[2] : "dead_variable_report.txt";
        String extraClasspath = args.length >= 4 ? args[3] : null;

        if ("-".equals(libDir)) {
            libDir = null;
        }

        File classesDirFile = new File(classesDir);
        if (!classesDirFile.exists() || !classesDirFile.isDirectory()) {
            throw new IllegalArgumentException("Classes directory does not exist or is not a directory: " + classesDir);
        }

        File reportFile = new File(outputFile);
        File parent = reportFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (PrintWriter writer = new PrintWriter(reportFile, "UTF-8")) {
            reportWriter = writer;

            List<String> classpathEntries = new ArrayList<>();
            classpathEntries.add(classesDir);
            if (libDir != null) {
                classpathEntries.addAll(getAllJarFiles(libDir));
            }
            classpathEntries.addAll(parseExtraClasspath(extraClasspath));

            logInfo("Writing analysis report to: " + reportFile.getAbsolutePath());
            logInfo("Classpath entries:");
            for (String cpEntry : classpathEntries) {
                logInfo(cpEntry);
            }

            // SootUp 2.0.0 expects a classpath string joined with the platform path separator.
            String classPath = String.join(File.pathSeparator, classpathEntries);
            AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(classPath);
            JavaView view = new JavaView(Collections.singletonList(inputLocation));

            List<String> classNames = findClassNamesInDirectory(classesDir);
            logInfo("\nFound " + classNames.size() + " classes to analyze.");

            for (String className : classNames) {
                analyzeClass(view, className);
            }

            logInfo("\nAnalysis complete.");
        } finally {
            reportWriter = null;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp <analysis-jar-and-deps> com.ipaco.DeadVariableDetector <classesDir> [libDir|-] [outputFile] [extraClasspath]");
        System.out.println("Example:");
        System.out.println("  com.ipaco.DeadVariableDetector /path/to/Chart_24_buggy/build /path/to/Chart_24_buggy/lib ./dead_variable_report.txt");
        System.out.println("  com.ipaco.DeadVariableDetector /path/to/Lang_6_buggy/target/classes - ./lang6_report.txt");
    }

    private static List<String> parseExtraClasspath(String extraClasspath) {
        List<String> entries = new ArrayList<>();
        if (extraClasspath == null || extraClasspath.trim().isEmpty()) {
            return entries;
        }

        String[] parts = extraClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static void logInfo(String message) {
        System.out.println(message);
        if (reportWriter != null) {
            reportWriter.println(message);
            reportWriter.flush();
        }
    }

    private static void logError(String message) {
        System.err.println(message);
        if (reportWriter != null) {
            reportWriter.println(message);
            reportWriter.flush();
        }
    }

    // ... (findClassNamesInDirectory, getAllJarFiles methods remain the same as before) ...
    /**
     * Recursively walks a directory, finds all .class files, and converts their
     * file paths into fully qualified class names.
     *
     * @param root        the root directory (e.g., /path/to/build)
     * @return list of fully qualified class names (e.g., "org.jfree.chart.MyClass")
     * @throws IOException if directory traversal fails
     */
    private static List<String> findClassNamesInDirectory(String root) throws IOException {
        List<String> classNames = new ArrayList<>();
        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        try (java.util.stream.Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".class"))
                .forEach(path -> {
                    String relativePath = rootPath.relativize(path.toAbsolutePath().normalize()).toString();
                    String className = relativePath.replace(File.separatorChar, '.')
                            .replace(".class", "");
                    classNames.add(className);
                });
        }
        return classNames;
    }

    /**
     * Scans a directory and returns a list of absolute paths for every .jar file.
     *
     * @param libDirPath path to the directory containing JARs
     * @return list of JAR file paths
     */
    private static List<String> getAllJarFiles(String libDirPath) {
        List<String> jarPaths = new ArrayList<>();
        File libDir = new File(libDirPath);
        if (libDir.exists() && libDir.isDirectory()) {
            File[] files = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (files != null) {
                for (File jar : files) {
                    jarPaths.add(jar.getAbsolutePath());
                }
            }
        } else {
            logError("Warning: lib directory not found at " + libDirPath);
        }
        return jarPaths;
    }

    private static void analyzeClass(JavaView view, String className) {
        try {
            ClassType classType = view.getIdentifierFactory().getClassType(className);
            Optional<? extends SootClass> sootClassOpt = view.getClass(classType);

            if (!sootClassOpt.isPresent()) {
                logError("Skipping " + className + " – class could not be loaded (missing dependencies?)");
                return;
            }

            SootClass sootClass = sootClassOpt.get();
            logInfo("\n=== Analyzing class: " + className + " ===");

            for (SootMethod method : sootClass.getMethods()) {
                if (method.isConcrete()) {
                    if (method.hasBody()) {
                        Body body = method.getBody();
                        logInfo("\nMethod: " + method.getName());
                        // Call the dead variable analysis for each method body.
                        detectDeadAssignments(body, className, method.getName());
                    }
                }
            }
        } catch (Exception e) {
            logError("Error analyzing " + className + ": " + e.getMessage());
        }
    }

    // --- Core Liveness Analysis Logic (SootUp 2.0.0 Compatible) ---

    /**
     * Performs a live variable analysis on the given Jimple body and prints
     * any statements that constitute a dead assignment.
     *
     * @param body The Jimple body of the method to analyze.
     */
    private static void detectDeadAssignments(Body body, String className, String methodName) {
        logInfo("\n--- Dead variable analysis for " + body.getMethodSignature().getName() + " ---");

        // 1. Get the control flow graph as a list of statements.
        sootup.core.graph.StmtGraph<?> stmtGraph = body.getStmtGraph();
        List<sootup.core.jimple.common.stmt.Stmt> stmts = stmtGraph.getStmts();
        Map<sootup.core.jimple.common.stmt.Stmt, Integer> stmtToIndex = new HashMap<>();
        for (int i = 0; i < stmts.size(); i++) {
            stmtToIndex.put(stmts.get(i), i);
        }

        // Map each statement to its successors (list of statement indices)
        Map<Integer, List<Integer>> successors = new HashMap<>();
        for (int i = 0; i < stmts.size(); i++) {
            sootup.core.jimple.common.stmt.Stmt stmt = stmts.get(i);
            List<Integer> succIndices = new ArrayList<>();
            for (sootup.core.jimple.common.stmt.Stmt succ : stmtGraph.successors(stmt)) {
                Integer succIdx = stmtToIndex.get(succ);
                if (succIdx != null) {
                    succIndices.add(succIdx);
                }
            }
            successors.put(i, succIndices);
        }

        // 2. Compute def and use sets for each statement
        List<Set<sootup.core.jimple.basic.Local>> def = new ArrayList<>();
        List<Set<sootup.core.jimple.basic.Local>> use = new ArrayList<>();
        for (sootup.core.jimple.common.stmt.Stmt stmt : stmts) {
            Set<sootup.core.jimple.basic.Local> defSet = new HashSet<>();
            Set<sootup.core.jimple.basic.Local> useSet = new HashSet<>();

            // Use SootUp's generic def/use API to remain robust across Jimple statement kinds.
            Optional<sootup.core.jimple.basic.LValue> defOpt = stmt.getDef();
            if (defOpt.isPresent() && defOpt.get() instanceof sootup.core.jimple.basic.Local) {
                defSet.add((sootup.core.jimple.basic.Local) defOpt.get());
            }

            stmt.getUses().forEach(v -> {
                if (v instanceof sootup.core.jimple.basic.Local) {
                    useSet.add((sootup.core.jimple.basic.Local) v);
                }
            });

            def.add(defSet);
            use.add(useSet);
        }

        // 3. Iterative dataflow: compute IN and OUT sets (backward)
        List<Set<sootup.core.jimple.basic.Local>> in = new ArrayList<>();
        List<Set<sootup.core.jimple.basic.Local>> out = new ArrayList<>();
        for (int i = 0; i < stmts.size(); i++) {
            in.add(new HashSet<>());
            out.add(new HashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            // Process statements in reverse order (backward flow)
            for (int i = stmts.size() - 1; i >= 0; i--) {
                // OUT = union of IN of all successors
                Set<sootup.core.jimple.basic.Local> newOut = new HashSet<>();
                for (int succIdx : successors.get(i)) {
                    newOut.addAll(in.get(succIdx));
                }
                if (!newOut.equals(out.get(i))) {
                    out.set(i, newOut);
                    changed = true;
                }
                // IN = use ∪ (OUT - def)
                Set<sootup.core.jimple.basic.Local> newIn = new HashSet<>(use.get(i));
                Set<sootup.core.jimple.basic.Local> outMinusDef = new HashSet<>(out.get(i));
                outMinusDef.removeAll(def.get(i));
                newIn.addAll(outMinusDef);
                if (!newIn.equals(in.get(i))) {
                    in.set(i, newIn);
                    changed = true;
                }
            }
        } while (changed);

        // 4. Report dead assignments
        for (int i = 0; i < stmts.size(); i++) {
            sootup.core.jimple.common.stmt.Stmt stmt = stmts.get(i);
            if (stmt instanceof sootup.core.jimple.common.stmt.JAssignStmt) {
                sootup.core.jimple.common.stmt.JAssignStmt assign = (sootup.core.jimple.common.stmt.JAssignStmt) stmt;
                sootup.core.jimple.basic.Value leftOp = assign.getLeftOp();
                if (leftOp instanceof sootup.core.jimple.basic.Local) {
                    sootup.core.jimple.basic.Local definedVar = (sootup.core.jimple.basic.Local) leftOp;
                    // A definition is dead if the variable is NOT live after this statement
                    if (!out.get(i).contains(definedVar)) {
                        String sourceLocation = formatSourceLocation(stmt);
                        logInfo("Dead assignment [class=" + className
                                + ", method=" + methodName
                                + ", jimpleIndex=" + i
                                + ", source=" + sourceLocation
                                + "]: " + stmt);
                    }
                }
            }
        }
    }

    private static String formatSourceLocation(sootup.core.jimple.common.stmt.Stmt stmt) {
        sootup.core.model.Position pos = stmt.getPositionInfo().getStmtPosition();
        int firstLine = pos.getFirstLine();
        int firstCol = pos.getFirstCol();
        int lastLine = pos.getLastLine();
        int lastCol = pos.getLastCol();

        if (firstLine <= 0) {
            return "line=unknown";
        }

        if (lastLine > 0 && (lastLine != firstLine || lastCol > 0)) {
            return "line=" + firstLine + ":" + Math.max(firstCol, 1)
                    + "-" + lastLine + ":" + Math.max(lastCol, 1);
        }

        if (firstCol > 0) {
            return "line=" + firstLine + ":" + firstCol;
        }

        return "line=" + firstLine;
    }

}