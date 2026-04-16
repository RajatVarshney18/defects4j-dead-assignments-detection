package com.dva;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ResultDumper {

    private final String      outputPath;
    private final PrintWriter writer;
    private int totalDead    = 0;
    private int totalMethods = 0;

    public ResultDumper(String outputPath) throws IOException {
        this.outputPath = outputPath;
        FileWriter    fw = new FileWriter(outputPath, false);
        BufferedWriter bw = new BufferedWriter(fw);
        this.writer = new PrintWriter(bw);
        writeHeader();
    }

    private void writeHeader() {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new Date());
        writer.println("================================================================");
        writer.println("  DEAD VARIABLE ANALYSIS — PASS RESULTS");
        writer.println("  Generated : " + ts);
        writer.println("================================================================");
        writer.println();
        writer.println("  Each entry contains:");
        writer.println("    [CLASS]        fully qualified class name");
        writer.println("    [METHOD]       full method signature");
        writer.println("    [SOURCE FILE]  Java source filename");
        writer.println("    [SOURCE LINE]  line in original .java file");
        writer.println("    [JIMPLE LINE]  statement index in Jimple body");
        writer.println("    [JIMPLE IR]    the dead Jimple statement");
        writer.println("    [REASON]       why it is dead + action");
        writer.println();
        writer.println("  Filtered out (not reported):");
        writer.println("    - $stack temporaries (Jimple IR artefacts)");
        writer.println("    - zero / null initialisations (IR artefacts)");
        writer.println("    - invoke result locals (kept for side effects)");
        writer.println("    - cast result locals (kept for CCE check)");
        writer.println("    - @this / @parameter identity statements");
        writer.println();
        writer.println("================================================================");
        writer.println();
        writer.flush();
    }

    public void dump(AnalysisResult result) {
        List<AnalysisResult.DeadEntry> entries = result.getDeadStatements();
        if (entries.isEmpty()) return;

        totalMethods++;

        writer.println("----------------------------------------------------------------");
        writer.println("[CLASS]        " + result.getClassName());
        writer.println("[METHOD]       " + result.getMethodSignature());
        writer.println("[SOURCE FILE]  " + result.getSourceFile());
        writer.println();

        for (AnalysisResult.DeadEntry entry : entries) {
            totalDead++;

            int srcLine    = entry.getSourceLine();
            int jimpleLine = entry.getJimpleLine();

            writer.println("  [SOURCE LINE]  "
                    + (srcLine > 0
                       ? String.valueOf(srcLine)
                       : "unavailable (compile without -g for debug info)"));

            writer.println("  [JIMPLE LINE]  " + jimpleLine);

            writer.println("  [JIMPLE IR]    " + entry.getStmt().toString());

            writer.println("  [REASON]       " + entry.getReason());

            writer.println();
        }

        writer.flush();
    }

    public void close(int totalMethodsAnalyzed) {
        writer.println("================================================================");
        writer.println("  SUMMARY");
        writer.println("================================================================");
        writer.println("  Total methods analyzed          : " + totalMethodsAnalyzed);
        writer.println("  Methods with dead assignments   : " + totalMethods);
        writer.println("  Total dead assignments found    : " + totalDead);
        writer.println("================================================================");
        writer.close();
        System.out.println("Results written to: " + outputPath);
    }
}