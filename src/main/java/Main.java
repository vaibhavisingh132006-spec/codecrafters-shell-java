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
            
            String[] parts = input.split("\\s+");
            String command = parts[0];
            
            if (command.equals("exit")) {
                break;
            } else if (command.equals("pwd")) {
                System.out.println(currentDir);
            } else if (command.equals("cd")) {
                if (parts.length > 1) {
                    String targetPath = parts[1];
                    try {
                        Path basePat = Paths.get(currentDir);
                        Path resolvedPath = basePat.resolve(targetPath).normalize();
                        File targetDir = resolvedPath.toFile();
                        
                        if (targetDir.exists() && targetDir.isDirectory()) {
                            currentDir = resolvedPath.toString();
                        } else {
                            System.out.println("cd: " + targetPath + ": No such file or directory");
                        }
                    } catch (Exception e) {
                        System.out.println("cd: " + targetPath + ": No such file or directory");
                    }
                }
            } else if (command.equals("echo")) {
                if (input.length() > 5) {
                    System.out.println(input.substring(5));
                } else {
                    System.out.println();
                }
            } else if (command.equals("type")) {
                if (parts.length > 1) {
                    String arg = parts[1];
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
                }
            } else {
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