import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            if (!scanner.hasNextLine()) {
                break;
            }
            
            String input = scanner.nextLine().trim();
            
            // Check if the command is exactly "exit"
            if (input.equals("exit")) {
                System.exit(0); // Forces the JVM to shutdown instantly
            }
            
            System.out.println(input + ": command not found");
        }
    }
}