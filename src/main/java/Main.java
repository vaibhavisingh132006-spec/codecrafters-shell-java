import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush(); // Keep this to make sure the stream flushes correctly
            
            if (!scanner.hasNextLine()) {
                break;
            }
            
            String input = scanner.nextLine().trim();
            
            // CodeCrafters checks for exactly "exit" based on your screenshot
            if (input.equals("exit")) {
                break; // Break the loop to let the main method finish and terminate cleanly
            }
            
            System.out.println(input + ": command not found");
        }
    }
}