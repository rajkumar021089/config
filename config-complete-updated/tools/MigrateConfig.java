import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * One command-line migration client; no external libraries. java tools/MigrateConfig.java
 * https://is-host:port username old.namespace:getValue NBK [--apply] Password is entered
 * interactively, never as a command-line argument. A dry run always precedes apply; TLS
 * trust/certificate checks are never disabled.
 */
public class MigrateConfig {
  private final String base, authorization;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

  private MigrateConfig(String base, String user, char[] password) {
    URI origin = URI.create(base);
    if (!"https".equalsIgnoreCase(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
      throw new IllegalArgumentException("Use an HTTPS origin only, e.g. https://is-host:6655");
    this.base = base.replaceAll("/$", "");
    authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (user + ":" + new String(password)).getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, String> invoke(String service, Map<String, String> fields) throws Exception {
    StringJoiner form = new StringJoiner("&");
    for (Map.Entry<String, String> e : fields.entrySet())
      form.add(
          URLEncoder.encode(e.getKey(), "UTF-8") + "=" + URLEncoder.encode(e.getValue(), "UTF-8"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base + "/invoke/config.services/" + service))
            .timeout(Duration.ofMinutes(20))
            .header("Authorization", authorization)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/xml")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IOException(
          "IS returned HTTP "
              + response.statusCode()
              + "; check login/ACLs and server log. Response body not printed.");
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Document doc =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
    Map<String, String> values = new HashMap<>();
    NodeList all = doc.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if (e.getTagName().equals("value") && e.hasAttribute("name"))
        values.put(e.getAttribute("name"), e.getTextContent());
      else if (Arrays.asList("token", "result", "planId", "migrationStatus")
          .contains(e.getTagName())) values.put(e.getTagName(), e.getTextContent());
    }
    return values;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || args.length > 5 || (args.length == 5 && !args[4].equals("--apply")))
      throw new IllegalArgumentException(
          "Usage: java tools/MigrateConfig.java https://is-host:port username"
              + " old.namespace:getValue NBK [--apply]");
    Console console = System.console();
    if (console == null)
      throw new IllegalStateException(
          "Run interactively in CMD/terminal so password entry is hidden");
    char[] password = console.readPassword("IS password: ");
    if (password == null) throw new IllegalStateException("Password input cancelled");
    MigrateConfig client;
    try {
      client = new MigrateConfig(args[0], args[1], password);
    } finally {
      Arrays.fill(password, '\0');
    }
    String token = client.invoke("getAdminToken", Collections.emptyMap()).get("token");
    if (token == null)
      throw new IOException(
          "IS did not return an admin token; verify service loading and XML response");
    Map<String, String> params = new LinkedHashMap<>();
    params.put("token", token);
    params.put("sourceService", args[2]);
    params.put("packagePrefix", args[3]);
    params.put("dryRun", "true");
    params.put("activate", "true");
    Map<String, String> plan = client.invoke("migrate", params);
    if (!"DRY_RUN".equals(plan.get("migrationStatus")))
      throw new IOException("Dry run failed; check IS service output/server log");
    System.out.println(plan.get("result"));
    if (args.length == 4) {
      System.out.println("Dry run only. Add --apply after reviewing the plan.");
      return;
    }
    params.put("dryRun", "false");
    params.put("planId", Objects.requireNonNull(plan.get("planId"), "Missing migration plan ID"));
    Map<String, String> applied = client.invoke("migrate", params);
    if (!"APPLIED".equals(applied.get("migrationStatus")))
      throw new IOException("Apply did not complete; inspect server state before retrying");
    System.out.println(applied.get("result"));
    System.out.println(
        "Completed on this node. Validate wrapper results and deploy reviewed output to other"
            + " nodes.");
  }
}
