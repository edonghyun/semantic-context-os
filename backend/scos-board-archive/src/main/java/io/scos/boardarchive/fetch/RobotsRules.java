package io.scos.boardarchive.fetch;

import java.util.*;
import java.util.regex.Pattern;

/** Longest non-wildcard match wins; equal specificity prefers Allow. */
public final class RobotsRules {
  private record Rule(Pattern pattern, int length, boolean allow) {}

  private record Group(List<String> agents, List<Rule> rules) {}

  private final List<Rule> rules;

  private RobotsRules(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  public static RobotsRules allowAll() {
    return new RobotsRules(List.of());
  }

  public static RobotsRules denyAll() {
    return new RobotsRules(List.of(rule("/", false)));
  }

  public static RobotsRules parse(String text, String userAgent) {
    List<Group> groups = groups(text);
    String ua = userAgent.toLowerCase(Locale.ROOT);
    int best =
        groups.stream()
            .flatMap(g -> g.agents().stream())
            .filter(a -> !a.equals("*") && ua.contains(a))
            .mapToInt(String::length)
            .max()
            .orElse(0);
    List<Rule> selected = new ArrayList<>();
    for (Group g : groups) {
      boolean matches =
          g.agents().stream()
              .anyMatch(a -> best == 0 ? a.equals("*") : a.length() == best && ua.contains(a));
      if (matches) selected.addAll(g.rules());
    }
    return new RobotsRules(selected);
  }

  private static List<Group> groups(String text) {
    List<Group> groups = new ArrayList<>();
    List<String> agents = new ArrayList<>();
    List<Rule> rules = new ArrayList<>();
    boolean hasRule = false;
    for (String raw : text.split("\\R", -1)) {
      String line = raw.split("#", 2)[0].strip();
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
          value = line.substring(colon + 1).strip();
      if (key.equals("user-agent")) {
        if (hasRule) {
          groups.add(new Group(agents, rules));
          agents = new ArrayList<>();
          rules = new ArrayList<>();
          hasRule = false;
        }
        agents.add(value.toLowerCase(Locale.ROOT));
      } else if (key.equals("allow") || key.equals("disallow")) {
        hasRule = true;
        if (!value.isEmpty()) rules.add(rule(value, key.equals("allow")));
      }
    }
    groups.add(new Group(agents, rules));
    return groups;
  }

  private static Rule rule(String value, boolean allow) {
    boolean end = value.endsWith("$");
    String path = end ? value.substring(0, value.length() - 1) : value;
    String regex =
        Arrays.stream(path.split("\\*", -1))
            .map(Pattern::quote)
            .reduce((a, b) -> a + ".*" + b)
            .orElse("");
    return new Rule(
        Pattern.compile("^" + regex + (end ? "$" : "")), path.replace("*", "").length(), allow);
  }

  public boolean allows(String pathAndQuery) {
    return rules.stream()
        .filter(r -> r.pattern().matcher(pathAndQuery).find())
        .max(Comparator.comparingInt(Rule::length).thenComparing(Rule::allow))
        .map(Rule::allow)
        .orElse(true);
  }
}
