import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush(); // CRITICAL: Forces Java to print "$ " immediately so CodeCrafters can see it
            
            if (!scanner.hasNextLine()) {
                break; // Gracefully handle EOF if the tester closes the stream
            }
            
            String input = scanner.nextLine().trim();
            
            // Check if the command is "exit 0"
            if (input.equals("exit 0")) {
                System.exit(0);
            }
            
            // For any other command, print the error
            System.out.println(input + ": command not found");
        }
    }
}