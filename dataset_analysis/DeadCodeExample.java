public class DeadCodeExample {
    
    // We use a class field now. Soot's basic optimizers cannot delete this!
    Object ignoredField; 

    public static void main(String[] args) {
        DeadCodeExample test = new DeadCodeExample();
        test.runProcess();
    }

    public Object calculate() {
        return new Object();
    }

    public void runProcess() {
        // DEAD ASSIGNMENT: We assign it to a field, but no other method ever reads this field.
        this.ignoredField = calculate(); 
    }
}