package com.ipaco.analysis;

import sootup.core.jimple.common.stmt.Stmt;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ResultDumper {
   private final String       outputPath;
    private final PrintWriter  writer;
    private int                totalDead    = 0;
    private int                totalMethods = 0;

    // ----------------------------------------------------------------
    // constructor — opens the file and writes the header immediately
    // ----------------------------------------------------------------
    public ResultDumper(String outputPath) throws IOException {
        this.outputPath = outputPath;

        // BufferedWriter for performance — we may write thousands of lines
        FileWriter   fw = new FileWriter(outputPath, false); // false = overwrite
        BufferedWriter bw = new BufferedWriter(fw);
        this.writer = new PrintWriter(bw);

        writeHeader();
    }

    // ----------------------------------------------------------------
    // write the file header with timestamp and column legend
    // ----------------------------------------------------------------
    private void writeHeader() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                               .format(new Date());

        writer.println("================================================================");
        writer.println("  DEAD VARIABLE ANALYSIS — PASS 1 RESULTS");
        writer.println("  Generated : " + timestamp);
        writer.println("================================================================");
        writer.println();
        writer.println("FORMAT PER ENTRY:");
        writer.println("  [CLASS]   fully qualified class name");
        writer.println("  [METHOD]  method signature");
        writer.println("  [FILE]    source file name");
        writer.println("  [LINE]    source line number (-1 = no debug info in bytecode)");
        writer.println("  [JIMPLE]  the dead Jimple IR statement");
        writer.println("  [REASON]  classification and recommended action");
        writer.println();
        writer.println("================================================================");
        writer.println();
        writer.flush();
    }

    // ----------------------------------------------------------------
    // dump all dead entries for one method's result
    // called once per method that has at least one dead statement
    // ----------------------------------------------------------------
    public void dump(AnalysisResult result) {

        List<AnalysisResult.DeadEntry> entries = result.getDeadStatements();
        if (entries.isEmpty()) {
            return;
        }

        totalMethods++;

        // method-level header
        writer.println("----------------------------------------------------------------");
        writer.println("[CLASS]   " + result.getClassName());
        writer.println("[METHOD]  " + result.getMethodSignature());
        writer.println("[FILE]    " + result.getSourceFile());
        writer.println();

        // one block per dead statement
        for (AnalysisResult.DeadEntry entry : entries) {
            totalDead++;

            Stmt stmt = entry.getStmt();
            int  line = entry.getSourceLine();

            writer.println("  [LINE]    "
                    + (line > 0 ? String.valueOf(line) : "unavailable (no debug info)"));

            writer.println("  [JIMPLE]  " + stmt.toString());

            writer.println("  [REASON]  " + entry.getReason());

            writer.println();
        }

        writer.flush(); // flush after each method so file is readable mid-run
    }

    // ----------------------------------------------------------------
    // write the summary footer and close the file
    // ----------------------------------------------------------------
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
