package com.ipaco.analysis;
/**
 * The lattice has three levels:
 *
 *         TOP  (undefined — variable not yet assigned)
 *          |
 *       CONSTANT(c)  (variable holds exactly one known value)
 *          |
 *        BOTTOM  (variable holds multiple possible values — non-constant)
 *
 * Lattice rule (meet operation):
 *   TOP    meet  anything  = anything
 *   BOTTOM meet  anything  = BOTTOM
 *   CONST(a) meet CONST(b) = CONST(a) if a==b, else BOTTOM
 */
public class ConstValue {
  // the three kinds
    public enum latticeValue { TOP, CONSTANT, BOTTOM }

    private final latticeValue   latticevalue;
    private final Object value; // only valid when latticevalue == CONSTANT

    // private constructor — use factory methods below
    private ConstValue(latticeValue latticevalue, Object value) {
        this.latticevalue  = latticevalue;
        this.value = value;
    }

    /** Variable is undefined / not yet seen. */
    public static ConstValue top() {
        return new ConstValue(latticeValue.TOP, null);
    }

    /** Variable holds a known constant value. */
    public static ConstValue ofConstant(Object val) {
        return new ConstValue(latticeValue.CONSTANT, val);
    }

    /**
     * Variable may hold different values depending on
     * the path — we cannot determine it statically.
     */
    public static ConstValue bottom() {
        return new ConstValue(latticeValue.BOTTOM, null);
    }

    public latticeValue getKind() { return latticevalue; }

    public boolean isTop()      { return latticevalue == latticeValue.TOP;      }
    public boolean isConstant() { return latticevalue == latticeValue.CONSTANT; }
    public boolean isBottom()   { return latticevalue == latticeValue.BOTTOM;   }

    public Object getValue() {
        if (latticevalue != latticeValue.CONSTANT) {
            throw new IllegalStateException(
                "getValue() called on non-constant: " + latticevalue);
        }
        return value;
    }

    // ----------------------------------------------------------------
    // meet operation — the core lattice operation
    //
    // Used when two CFG paths merge (at join points).
    // ----------------------------------------------------------------
    public ConstValue meet(ConstValue other) {

        // TOP meet X = X  (TOP is the identity element)
        if (this.isTop())  return other;
        if (other.isTop()) return this;

        // BOTTOM meet X = BOTTOM  (BOTTOM absorbs everything)
        if (this.isBottom() || other.isBottom()) return bottom();

        // CONST(a) meet CONST(b):
        // if both are the same constant → still constant
        // if they differ → BOTTOM (value depends on which path was taken)
        if (this.value.equals(other.value)) {
            return ofConstant(this.value);
        } else {
            return bottom();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ConstValue)) return false;
        ConstValue other = (ConstValue) obj;
        if (this.latticevalue != other.latticevalue) return false;
        if (this.value == null) return other.value == null;
        return this.value.equals(other.value);
    }

    @Override
    public int hashCode() {
        int h = latticevalue.hashCode();
        if (value != null) h = 31 * h + value.hashCode();
        return h;
    }

    @Override
    public String toString() {
        switch (latticevalue  ) {
            case TOP:      return "TOP";
            case BOTTOM:   return "BOTTOM";
            case CONSTANT: return "CONST(" + value + ")";
            default:       return "?";
        }
    }
}
