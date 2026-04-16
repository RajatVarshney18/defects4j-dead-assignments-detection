package com.dva;

import sootup.core.jimple.common.stmt.Stmt;

import java.util.ArrayList;
import java.util.List;

public class AnalysisResult {

    private String className;
    private String methodSignature;
    private String sourceFile;

    private final List<DeadEntry> deadStatements = new ArrayList<DeadEntry>();

    public void setContext(String className,
                           String methodSignature,
                           String sourceFile) {
        this.className       = className;
        this.methodSignature = methodSignature;
        this.sourceFile      = sourceFile;
    }

    public void addDead(Stmt stmt, String reason, int jimpleLine) {
        deadStatements.add(new DeadEntry(stmt, reason, jimpleLine));
    }

    public List<DeadEntry>  getDeadStatements()  { return deadStatements; }
    public String           getClassName()       { return className; }
    public String           getMethodSignature() { return methodSignature; }
    public String           getSourceFile()      { return sourceFile; }

    // ----------------------------------------------------------------
    public static class DeadEntry {

        private final Stmt   stmt;
        private final String reason;
        private final int    jimpleLine;   // position in Jimple stmt list
        private final int    sourceLine;   // original Java source line

        public DeadEntry(Stmt stmt, String reason, int jimpleLine) {
            this.stmt       = stmt;
            this.reason     = reason;
            this.jimpleLine = jimpleLine;

            // extract source line from SootUp position info
            int line = -1;
            try {
                if (stmt.getPositionInfo() != null
                        && stmt.getPositionInfo().getStmtPosition() != null) {
                    line = stmt.getPositionInfo()
                                .getStmtPosition().getFirstLine();
                }
            } catch (Exception ignored) { }
            this.sourceLine = line;
        }

        public Stmt   getStmt()       { return stmt;       }
        public String getReason()     { return reason;     }
        public int    getJimpleLine() { return jimpleLine; }
        public int    getSourceLine() { return sourceLine; }
    }
}