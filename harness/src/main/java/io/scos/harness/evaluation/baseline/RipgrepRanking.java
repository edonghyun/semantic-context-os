package io.scos.harness.evaluation.baseline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rg 기준선: `rg -uu -c -i -F -e t1 [-e t2 …] <roots>`의 파일별 매치 줄 수를 문서 id별로 합산해 줄 수 내림차순(동점은 id
 * 오름차순)으로 줄 세운다. 서브에이전트 파일은 부모 세션으로 합쳐진다. 실행 파일은 생성자 인자(기본 SCOS_RG 또는 rg)다.
 */
public final class RipgrepRanking {
  private final String executable;
  private final ExternalCommand externalCommand = new ExternalCommand("rg", Set.of(1));

  public RipgrepRanking(String executable) {
    this.executable = executable;
  }

  public List<String> rank(List<String> terms, Path projects, Map<String, Path> docRoots) {
    List<String> command = new ArrayList<>(List.of(executable, "-uu", "-c", "-i", "-F"));
    for (String term : terms) {
      command.add("-e");
      command.add(term);
    }
    command.add("--with-filename");
    command.add("--");
    List<Path> roots = new ArrayList<>(List.of(projects));
    roots.addAll(docRoots.values());
    for (Path root : roots)
      if (Files.isDirectory(root)) command.add(root.toAbsolutePath().normalize().toString());
    Map<String, Long> counts = new HashMap<>();
    for (String line : externalCommand.run(command).split("\n")) {
      int colon = line.lastIndexOf(':');
      if (colon <= 0) continue;
      PathDocumentIds.of(projects, docRoots, Path.of(line.substring(0, colon)))
          .ifPresent(id -> counts.merge(id, Long.parseLong(line.substring(colon + 1)), Long::sum));
    }
    return RankedCounts.descending(counts);
  }
}
