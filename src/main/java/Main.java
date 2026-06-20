import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine();

            // For now, treat every command as invalid
            System.out.println(input + ": command not found");
        }
    }
}