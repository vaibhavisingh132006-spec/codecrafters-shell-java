import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        // Use a continuous loop to handle multiple inputs
        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            
            // Check if the command is to exit
            if (input.equals("exit 0")) {
                System.exit(0); // Immediately terminates the shell
            }
            
            // If it's any other command, print command not found
            System.out.println(input + ": command not found");
        }
    }
}