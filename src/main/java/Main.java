import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            // 1. Print the prompt FIRST inside the loop
            System.out.print("$ ");
            System.out.flush(); 
            
            // 2. Check for EOF before reading
            if (!scanner.hasNextLine()) {
                break; 
            }
            
            // 3. Read the input line
            String input = scanner.nextLine().trim();
            
            // 4. Handle empty inputs (if the user just presses Enter)
            if (input.isEmpty()) {
                continue;
            }
            
            // 5. Check for exit command
            if (input.equals("exit 0")) {
                System.exit(0);
            }
            
            // 6. Print the command not found error
            System.out.println(input + ": command not found");
            // The loop will now immediately cycle back to step 1 and print the next "$ "
        }
    }
}