import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String currentDir = System.getProperty("user.dir");
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            if (!scanner.hasNextLine()) {
                break;
            }
            
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            
            if (input.equals("exit")) {
                break;
            } else if (input.equals("pwd")) {
                System.out.println(currentDir);
            } else if (input.startsWith("cd ")) {
                String targetPath = input.substring(3).trim();
                File targetDir = new File(targetPath);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    currentDir = targetDir.getAbsolutePath();
                } else {
                    System.out.println("cd: " + targetPath + ": No such file or directory");
                }
            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.startsWith("type ")) {
                String arg = input.substring(5).trim();
                if (arg.equals("echo") || arg.equals("exit") || arg.equals("type") || arg.equals("pwd") || arg.equals("cd")) {
                    System.out.println(arg + " is a shell builtin");
                } else {
                    String fullPath = getPath(arg);
                    if (fullPath != null) {
                        System.out.println(arg + " is " + fullPath);
                    } else {
                        System.out.println(arg + ": not found");
                    }
                }
            } else {
                String[] parts = input.split("\\s+");
                String command = parts[0];
                String fullPath = getPath(command);
                
                if (fullPath != null) {
                    List<String> commandList = new ArrayList<>();
                    commandList.add("sh");
                    commandList.add("-c");
                    commandList.add(input);
                    
                    ProcessBuilder pb = new ProcessBuilder(commandList);
                    pb.directory(new File(currentDir));
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static String getPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] directories = pathEnv.split(File.pathSeparator);
            for (String dir : directories) {
                Path fullPath = Paths.get(dir, command);
                if (Files.isExecutable(fullPath)) {
                    return fullPath.toString();
                }
            }
        }
        return null;
    }
}