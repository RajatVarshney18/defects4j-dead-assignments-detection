public class UserProfile {
    public int accessCount;

    public void printAccess() {
        // [READ]: This registers 'accessCount' as read for THIS specific object memory.
        System.out.println("Accesses: " + this.accessCount);
    }

    public void resetAndFail() {
        // [DEAD WRITE]: This assignment is mathematically dead.
        // Old Pass: Would mark it LIVE because printAccess() reads 'accessCount' somewhere else.
        // New Pass: Will mark it DEAD because this specific object's memory never reads it.
        this.accessCount = 0; 
    }

    public static void main(String[] args) {
        // --- OBJECT 1: The "Live" Memory Space ---
        UserProfile profile1 = new UserProfile();
        profile1.accessCount = 5; // LIVE assignment (read by printAccess below)
        profile1.printAccess();

        // --- OBJECT 2: The "Dead" Memory Space ---
        UserProfile profile2 = new UserProfile();
        profile2.resetAndFail();
    }
}