package com.ipaco.analysis;

import sootup.core.jimple.common.stmt.Stmt;

import java.util.ArrayList;
import java.util.List;

public class AnalysisResult {

  // class and method context — set once per analysis run
    private String className;
    private String methodSignature;
    private String sourceFile;

    private final List<DeadEntry> deadStatements = new ArrayList<>();

    public void setContext(String className, String methodSignature, String sourceFile) {
        this.className       = className;
        this.methodSignature = methodSignature;
        this.sourceFile      = sourceFile;
    }

    public void addDead(Stmt stmt, String reason) {
        deadStatements.add(new DeadEntry(stmt, reason));
    }

    public List<DeadEntry> getDeadStatements() {
        return deadStatements;
    }

    public String getClassName()       { return className; }
    public String getMethodSignature() { return methodSignature; }
    public String getSourceFile()      { return sourceFile; }

    public void print() {
        for (DeadEntry entry : deadStatements) {
            System.out.println("  [DEAD] " + entry.getStmt()
                    + "  |  reason: " + entry.getReason());
        }
    }

    public static class DeadEntry {

        private final Stmt stmt;
        private final String reason;
        private final int sourceLine;

        public DeadEntry(Stmt stmt, String reason) {
            this.stmt   = stmt;
            this.reason = reason;

            // extract source line from SootUp position info
            // getPositionInfo() is always present but line may be -1 if the bytecode had no debug info compiled in
            int line = -1;
            try {
                if (stmt.getPositionInfo() != null && stmt.getPositionInfo().getStmtPosition() != null) {
                  line = stmt.getPositionInfo().getStmtPosition().getFirstLine();
                }
              } catch (Exception e) {
                // position info unavailable for this statement
                line = -1;
              }
              this.sourceLine = line;
          };

        public Stmt getStmt() {
            return stmt;
        }

        public String getReason() {
            return reason;
        }

        public int getSourceLine() {
            return sourceLine;
        }
    }
}
