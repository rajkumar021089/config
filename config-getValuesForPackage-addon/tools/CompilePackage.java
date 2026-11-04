import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compile the visible Java source against YOUR installation's real IS libraries. This writes
 * ordinary code/classes files. It does not create or need a custom JAR. Run using a JDK 11+ Java
 * executable (not a standalone JRE).
 */
public class CompilePackage {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "Usage: java tools/CompilePackage.java <IS-suite-root> <config-package-directory>");
    }
    Path installation = Paths.get(args[0]).toRealPath();
    Path pkg = Paths.get(args[1]).toRealPath();
    if (!Files.isRegularFile(pkg.resolve("manifest.v3"))
        || !Files.isRegularFile(pkg.resolve("code/source/config/services.java"))) {
      throw new IllegalArgumentException("The second path must be the delivered config package");
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
          "No Java compiler available. Run using the IS-supported JDK.");
    }
    List<String> jars;
    try (Stream<Path> files = Files.walk(installation)) {
      jars =
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".jar"))
              .filter(p -> !p.getFileName().toString().equals("config-manager.jar"))
              .map(Path::toString)
              .collect(Collectors.toList());
    }
    if (jars.isEmpty())
      throw new IllegalArgumentException("No IS libraries found under first path");
    Path output = pkg.resolve("code/classes");
    Files.createDirectories(output);
    List<String> arguments =
        new ArrayList<>(
            Arrays.asList(
                "--release",
                "11",
                "-encoding",
                "UTF-8",
                "-cp",
                String.join(File.pathSeparator, jars),
                "-d",
                output.toString(),
                pkg.resolve("code/source/config/services.java").toString()));
    int result = compiler.run(null, System.out, System.err, arguments.toArray(new String[0]));
    if (result != 0)
      throw new IllegalStateException("Compilation failed; inspect compiler errors above");
    System.out.println("Compiled visible Java source to " + output);
    System.out.println("Load/reload the config package on the development IS.");
  }
}
