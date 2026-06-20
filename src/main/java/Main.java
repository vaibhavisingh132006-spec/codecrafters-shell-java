import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            
            if (input.equals("exit")) {
                break;
            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.startsWith("type ")) {
                String arg = input.substring(5).trim();
                if (arg.equals("echo") || arg.equals("exit") || arg.equals("type")) {
                    System.out.println(arg + " is a shell builtin");
                } else {
                    String pathEnv = System.getenv("PATH");
                    boolean found = false;
                    
                    if (pathEnv != null) {
                        String[] directories = pathEnv.split(File.pathSeparator);
                        for (String dir : directories) {
                            Path fullPath = Paths.get(dir, arg);
                            if (Files.isExecutable(fullPath)) {
                                System.out.println(arg + " is " + fullPath.toString());
                                found = true;
                                break;
                            }
                        }
                    }
                    
                    if (!found) {
                        System.out.println(arg + ": not found");
                    }
                }
            } else {
                System.out.println(input + ": command not found");
            }
        }
    }
}