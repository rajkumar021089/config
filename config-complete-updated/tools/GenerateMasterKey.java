import java.security.SecureRandom;
import java.util.Base64;

/** Generate a fresh 256-bit encryption key. Redirect output to a protected external file. */
public class GenerateMasterKey {
  public static void main(String[] args) {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    System.out.println(Base64.getEncoder().encodeToString(key));
  }
}
