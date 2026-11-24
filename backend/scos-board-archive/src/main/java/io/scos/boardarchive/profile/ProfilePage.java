package io.scos.boardarchive.profile;

import java.net.URI;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/** Hides HTML/CSS and JSON/Pointer representations behind the fields a profile reads. */
sealed interface ProfilePage {
  List<ProfilePage> select(String selector);

  String text();

  String link();

  boolean marked();

  String sanitizedRaw();

  static ProfilePage parse(String format, String raw, URI uri, Selectors selectors) {
    if (format.equals("html"))
      return new Html(cleanHtml(Jsoup.parse(raw, uri.toString()), selectors));
    JsonNode json = JsonMapper.builder().build().readTree(raw);
    for (String pointer : selectors.identitySelectors()) removePointer(json, pointer);
    cleanJson(json, selectors);
    return new Json(json);
  }

  private static boolean identity(String name, Selectors selectors) {
    String key = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    if (key.startsWith("data")) key = key.substring(4);
    if (Set.of(
            "memberid",
            "userid",
            "accountid",
            "internalid",
            "email",
            "emailaddress",
            "phone",
            "phonenumber",
            "telephonenumber",
            "contact")
        .contains(key)) return true;
    for (String extra : selectors.identityFields())
      if (extra.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").equals(key)) return true;
    return false;
  }

  private static Element cleanHtml(Element element, Selectors selectors) {
    element
        .select("script,input[type=email],input[type=tel],a[href^=mailto:],a[href^=tel:]")
        .remove();
    for (String selector : selectors.identitySelectors()) element.select(selector).remove();
    for (Element e : element.getAllElements()) {
      if (e.hasAttr("name") && identity(e.attr("name"), selectors)) {
        e.remove();
        continue;
      }
      for (Attribute attr : new ArrayList<>(e.attributes().asList()))
        if (identity(attr.getKey(), selectors)) e.removeAttr(attr.getKey());
    }
    return element;
  }

  private static void cleanJson(JsonNode node, Selectors selectors) {
    if (node instanceof ObjectNode object) {
      List<String> names = new ArrayList<>();
      object.properties().forEach(e -> names.add(e.getKey()));
      for (String name : names)
        if (identity(name, selectors)) object.remove(name);
        else cleanJson(object.path(name), selectors);
    } else if (node.isArray()) for (JsonNode item : node) cleanJson(item, selectors);
  }

  private static void removePointer(JsonNode root, String pointer) {
    int split = pointer.lastIndexOf('/');
    if (split < 0) throw new IllegalArgumentException("Identity field must be a JSON Pointer");
    JsonNode parent = root.at(pointer.substring(0, split));
    if (parent instanceof ObjectNode object)
      object.remove(pointer.substring(split + 1).replace("~1", "/").replace("~0", "~"));
    else if (parent instanceof ArrayNode array) {
      int index = Integer.parseInt(pointer.substring(split + 1));
      if (index < 0 || index >= array.size()) return;
      // Keep indexes stable so subsequent identity pointers cannot skip a shifted element.
      array.set(index, NullNode.getInstance());
    }
  }

  record Html(Element element) implements ProfilePage {
    @Override
    public List<ProfilePage> select(String selector) {
      return element.select(selector).stream().map(e -> (ProfilePage) new Html(e)).toList();
    }

    @Override
    public String text() {
      return element.text();
    }

    @Override
    public String link() {
      return element.attr("href");
    }

    @Override
    public boolean marked() {
      return true;
    }

    @Override
    public String sanitizedRaw() {
      return element.outerHtml();
    }
  }

  record Json(JsonNode node) implements ProfilePage {
    @Override
    public List<ProfilePage> select(String selector) {
      JsonNode found = node.at(selector);
      List<ProfilePage> out = new ArrayList<>();
      if (found.isArray()) for (JsonNode item : found) out.add(new Json(item));
      else if (!found.isMissingNode() && !found.isNull()) out.add(new Json(found));
      return List.copyOf(out);
    }

    @Override
    public String text() {
      return node.asString("");
    }

    @Override
    public String link() {
      return text();
    }

    @Override
    public boolean marked() {
      return node.asBoolean(false);
    }

    @Override
    public String sanitizedRaw() {
      return node.toString();
    }
  }
}
