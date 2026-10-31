package io.scos.harness.evaluation.baseline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * git log 기준선: 저장소마다 커밋 메시지 검색(`--grep`, 대소문자 무시·고정 문자열)과 term별 변경 내용 검색(`-S`)의 합집합을 커밋 시각 내림차순(동점은
 * id 오름차순)으로 줄 세운다. 문서 id는 git/&lt;repo 이름&gt;/&lt;sha 앞 8자&gt;다.
 */
public final class GitLogRanking {
  private final String executable;
  private final ExternalCommand externalCommand = new ExternalCommand("git", Set.of());

  public GitLogRanking(String executable) {
    this.executable = executable;
  }

  public List<String> rank(List<String> terms, Map<String, Path> repos) {
    Map<String, Long> commitTimes = new HashMap<>();
    for (Map.Entry<String, Path> repo : repos.entrySet()) {
      List<String> grep = base(repo.getValue());
      grep.addAll(List.of("-i", "--fixed-strings"));
      for (String term : terms) grep.add("--grep=" + term);
      collect(repo.getKey(), grep, commitTimes);
      for (String term : terms) {
        List<String> pickaxe = base(repo.getValue());
        pickaxe.add("-S" + term);
        collect(repo.getKey(), pickaxe, commitTimes);
      }
    }
    return RankedCounts.descending(commitTimes);
  }

  private List<String> base(Path repo) {
    return new ArrayList<>(
        List.of(executable, "-C", repo.toString(), "log", "--all", "--format=%H%x09%ct"));
  }

  private void collect(String repoName, List<String> command, Map<String, Long> commitTimes) {
    for (String line : externalCommand.run(command).split("\n")) {
      String[] fields = line.split("\t");
      if (fields.length != 2 || fields[0].length() < 8) continue;
      commitTimes.put(
          "git/" + repoName + "/" + fields[0].substring(0, 8), Long.parseLong(fields[1]));
    }
  }
}
