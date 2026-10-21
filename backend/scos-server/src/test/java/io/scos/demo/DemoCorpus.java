package io.scos.demo;

import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공개 데모 코퍼스(demo/, 가상 회사의 합성 Slack·Notion·Jira 문서): 마크다운 원본 {@code corpus/docs/<출처>/…md}를 제품 수집
 * 파이프라인(IngestPipeline)으로 유닛으로 만들어 적재 파일 {@code corpus/units.json}(`import` 입력, 임베딩 없음)과 판정셋 pack
 * {@code eval/ottercart-demo-pack.json}(질의 원본 {@code eval/queries.json} + 문서)을 낸다. 두 파일은 커밋된 파생물이고
 * DemoCorpusTest가 원본과 같은지 확인한다. 원본을 고친 뒤 다시 만들기: {@code main(<demo 디렉터리>)}(demo/README.md).
 */
public final class DemoCorpus {
  private static final Logger log = LoggerFactory.getLogger(DemoCorpus.class);
  static final String COMPANY_HOST = "ottercart.example";
  static final String UNITS_FILE = "corpus/units.json";
  static final String QUERIES_FILE = "eval/queries.json";
  static final String PACK_FILE = "eval/ottercart-demo-pack.json";
  static final String GENERATED_BY =
      "backend/scos-server/src/test/java/io/scos/demo/DemoCorpus.java";
  private static final Pattern UPDATED = Pattern.compile("(?m)^updated:\\s*(\\S+)\\s*$");
  private static final Pattern TITLE = Pattern.compile("(?m)^# (.+)$");
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  /** 원본 문서 하나. documentId는 {@code docs/} 아래 경로(확장자 없음), 첫 경로 조각이 출처(slack·notion·jira)다. */
  public record Document(
      String source, String documentId, String title, String updatedAt, String markdown) {
    String sourceId() {
      return "demo-" + source;
    }

    /** 출처 분류(MCP SourceKind)는 URI 스킴으로 정해진다 — 가상 호스트 아래 문서 경로. */
    String uri() {
      return source + "://" + COMPANY_HOST + "/" + documentId.substring(source.length() + 1);
    }

    SourceDocument toSource() {
      return new SourceDocument(
          sourceId(), documentId, uri(), title, markdown, updatedAt, updatedAt);
    }
  }

  private final Path root;
  private final List<Document> documents;

  private DemoCorpus(Path root, List<Document> documents) {
    this.root = root;
    this.documents = documents;
  }

  /** {@code root}는 demo 디렉터리. 문서는 documentId 순(적재 순서가 실행마다 같다). */
  public static DemoCorpus read(Path root) {
    Path docs = root.resolve("corpus/docs");
    try (Stream<Path> files = Files.walk(docs)) {
      List<Document> documents = new ArrayList<>();
      for (Path file : files.filter(f -> f.toString().endsWith(".md")).sorted().toList())
        documents.add(document(docs, file));
      return new DemoCorpus(root, List.copyOf(documents));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 테스트·데모가 쓰는 위치: Gradle이 주는 {@code scos.demo.dir}, 없으면 backend 기준 {@code ../demo}. */
  public static Path defaultRoot() {
    return Path.of(System.getProperty("scos.demo.dir", "../demo"));
  }

  private static Document document(Path docs, Path file) throws IOException {
    String relative = docs.relativize(file).toString().replace('\\', '/');
    String documentId = relative.substring(0, relative.length() - ".md".length());
    String markdown = Files.readString(file, StandardCharsets.UTF_8);
    return new Document(
        documentId.substring(0, documentId.indexOf('/')),
        documentId,
        required(TITLE.matcher(markdown), "# 제목", file),
        required(UPDATED.matcher(markdown), "updated:", file),
        markdown);
  }

  private static String required(Matcher m, String what, Path file) {
    if (!m.find()) throw new IllegalArgumentException(what + " 줄이 없다: " + file);
    return m.group(1).strip();
  }

  public List<Document> documents() {
    return documents;
  }

  /** 제품 수집 파이프라인(헤딩 분할·토큰·해시·링크·태그)이 만든 유닛. 임베딩은 적재 직전에 붙인다(demo/embedder). */
  public List<ContextUnit> units() {
    IngestPipeline pipeline = new IngestPipeline(new JtokkitTokenizer());
    return documents.stream().flatMap(d -> pipeline.apply(d.toSource()).stream()).toList();
  }

  /** `import --file`이 읽는 형식(UnitJsonImport: TS ContextUnit JSON의 {@code units} 배열). */
  public String unitsJson() {
    Map<String, Object> file = new LinkedHashMap<>();
    file.put("version", 1);
    file.put("generatedBy", GENERATED_BY);
    file.put("units", units().stream().map(DemoCorpus::unitJson).toList());
    return JSON.writeValueAsString(file) + "\n";
  }

  private static Map<String, Object> unitJson(ContextUnit u) {
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("sourceId", u.provenance().sourceId());
    provenance.put("documentId", u.documentId());
    provenance.put("uri", u.provenance().uri());
    provenance.put("capturedAt", u.provenance().capturedAt());
    Map<String, Object> n = new LinkedHashMap<>();
    n.put("id", u.id());
    n.put("partition", u.partition());
    n.put("title", u.title());
    n.put("content", u.content());
    n.put("contentHash", u.contentHash());
    n.put("tokens", u.tokens());
    n.put("tier", u.tier().wire());
    n.put("provenance", provenance);
    n.put("importance", u.importance());
    n.put("links", u.links());
    n.put("tags", u.tags());
    return n;
  }

  /** 판정 질의 원본(eval/queries.json)의 {@code queries}. */
  public JsonNode queries() {
    return readJson(QUERIES_FILE).get("queries");
  }

  /**
   * 판정셋(judgment pack) 형식(TS bench PackLoader, O2 비교 도구가 읽는다): {@code
   * name·version·generatedBy·docs[{id,title,body}]·queries}.
   */
  public String packJson() {
    JsonNode source = readJson(QUERIES_FILE);
    Map<String, Object> pack = new LinkedHashMap<>();
    pack.put("name", source.get("name").asString());
    pack.put("version", source.get("version").asInt());
    pack.put("generatedBy", GENERATED_BY);
    pack.put("docs", documents.stream().map(DemoCorpus::packDoc).toList());
    pack.put("queries", source.get("queries"));
    return JSON.writeValueAsString(pack) + "\n";
  }

  private static Map<String, Object> packDoc(Document d) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("id", d.documentId());
    doc.put("title", d.title());
    doc.put("body", d.markdown());
    return doc;
  }

  String committed(String file) {
    try {
      return Files.readString(root.resolve(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private JsonNode readJson(String file) {
    return JSON.readTree(committed(file));
  }

  /** 파생 파일을 다시 쓴다. 인자: demo 디렉터리(없으면 {@link #defaultRoot()}). */
  public static void main(String[] args) throws IOException {
    DemoCorpus corpus = read(args.length > 0 ? Path.of(args[0]) : defaultRoot());
    Files.writeString(corpus.root.resolve(UNITS_FILE), corpus.unitsJson(), StandardCharsets.UTF_8);
    Files.writeString(corpus.root.resolve(PACK_FILE), corpus.packJson(), StandardCharsets.UTF_8);
    log.info("demo corpus: documents={} units={}", corpus.documents.size(), corpus.units().size());
  }
}
