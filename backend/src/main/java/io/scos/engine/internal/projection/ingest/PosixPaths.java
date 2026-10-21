package io.scos.engine.internal.projection.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Node `path.posix`의 dirname·join·normalize 의미(링크 정규화 G4가 의존). 파일 시스템을 만지지 않는다. */
final class PosixPaths {
  private static final Pattern SLASH = Pattern.compile("/");

  private PosixPaths() {}

  static String dirname(String path) {
    if (path.isEmpty()) return ".";
    boolean rooted = path.charAt(0) == '/';
    int end = -1;
    boolean matchedSlash = true;
    for (int i = path.length() - 1; i >= 1; i--) {
      if (path.charAt(i) == '/') {
        if (!matchedSlash) {
          end = i;
          break;
        }
      } else matchedSlash = false;
    }
    if (end == -1) return rooted ? "/" : ".";
    if (rooted && end == 1) return "//";
    return path.substring(0, end);
  }

  static String join(String... parts) {
    List<String> nonEmpty = new ArrayList<>();
    for (String p : parts) if (!p.isEmpty()) nonEmpty.add(p);
    if (nonEmpty.isEmpty()) return ".";
    return normalize(String.join("/", nonEmpty));
  }

  static String normalize(String path) {
    if (path.isEmpty()) return ".";
    boolean absolute = path.charAt(0) == '/';
    boolean trailing = path.charAt(path.length() - 1) == '/';
    List<String> stack = new ArrayList<>();
    for (String seg : SLASH.split(path, -1)) {
      if (seg.isEmpty() || seg.equals(".")) continue;
      if (seg.equals("..")) {
        if (!stack.isEmpty() && !stack.get(stack.size() - 1).equals(".."))
          stack.remove(stack.size() - 1);
        else if (!absolute) stack.add("..");
        continue;
      }
      stack.add(seg);
    }
    String joined = String.join("/", stack);
    if (joined.isEmpty() && !absolute) joined = ".";
    if (!joined.isEmpty() && trailing && !joined.equals(".")) joined += "/";
    if (trailing && joined.equals(".")) joined = "./";
    return absolute ? "/" + (joined.equals(".") ? "" : joined) : joined;
  }
}
