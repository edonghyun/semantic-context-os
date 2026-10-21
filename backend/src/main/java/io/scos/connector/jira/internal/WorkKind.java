package io.scos.connector.jira.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Jira 작업 큐의 작업 종류(TS jira-adapter.ts의 JiraCollectionTask.kind와
 * ROOT_TASKS·REQUIRED_LISTINGS·listing 경로표). 작업 JSON의 `kind` 값, 모양(루트 목록·자식 목록·이슈·첨부), 필수 여부, 원본
 * 종류, 경로, 봉투 키, 고정 쿼리를 한 곳에 둔다 — 새 종류나 필수 여부는 여기만 바꾼다. 작업 JSON 자체(`ObjectNode`)는 TS 체크포인트와 같은 모양을
 * 지키려고 그대로 두고, 종류만 {@link #of}로 읽는다.
 *
 * <p>선언 순서의 앞 다섯(루트)은 TS ROOT_TASKS 순서다: 처음 수집과 큐가 빈 체크포인트는 이 순서로 시작한다.
 */
enum WorkKind {
  FIELDS(
      "fields",
      Shape.ROOT_LISTING,
      Availability.REQUIRED,
      "field",
      "/rest/api/3/field",
      "values",
      null),
  PROJECTS(
      "projects",
      Shape.ROOT_LISTING,
      Availability.REQUIRED,
      "project",
      "/rest/api/3/project/search",
      "values",
      "description"),
  BOARDS(
      "boards",
      Shape.ROOT_LISTING,
      Availability.OPTIONAL,
      "board",
      "/rest/agile/1.0/board",
      "values",
      null),
  FILTERS(
      "filters",
      Shape.ROOT_LISTING,
      Availability.OPTIONAL,
      "filter",
      "/rest/api/3/filter/search",
      "values",
      "description,jql"),
  DASHBOARDS(
      "dashboards",
      Shape.ROOT_LISTING,
      Availability.OPTIONAL,
      "dashboard",
      "/rest/api/3/dashboard",
      "dashboards",
      null),
  /** 프로젝트의 이슈 검색(nextPageToken) — 원본을 내지 않고 이슈 작업을 낸다. 필수: 빠지면 커버리지가 조용히 준다. */
  ISSUES(
      "issues",
      Shape.CHILD_LISTING,
      Availability.REQUIRED,
      null,
      "/rest/api/3/search/jql",
      "issues",
      null),
  COMPONENTS(
      "components",
      Shape.CHILD_LISTING,
      Availability.OPTIONAL,
      "component",
      "/rest/api/3/project/{id}/components",
      "values",
      null),
  VERSIONS(
      "versions",
      Shape.CHILD_LISTING,
      Availability.OPTIONAL,
      "version",
      "/rest/api/3/project/{id}/versions",
      "values",
      null),
  SPRINTS(
      "sprints",
      Shape.CHILD_LISTING,
      Availability.OPTIONAL,
      "sprint",
      "/rest/agile/1.0/board/{id}/sprint",
      "values",
      null),
  ISSUE("issue", Shape.ISSUE, Availability.OPTIONAL, "issue", null, null, null),
  ATTACHMENT("attachment", Shape.ATTACHMENT, Availability.OPTIONAL, "attachment", null, null, null);

  /** 작업이 무엇을 읽는가. 이슈·첨부는 같은 종류가 이어지는 동안 함께(동시) 읽는다. */
  enum Shape {
    ROOT_LISTING,
    CHILD_LISTING,
    ISSUE,
    ATTACHMENT
  }

  /** 400·403·404를 받았을 때: 필수면 run 실패, 선택이면 거부로 남기고 계속한다(TS REQUIRED_LISTINGS). */
  enum Availability {
    REQUIRED,
    OPTIONAL
  }

  final String wire;
  final Shape shape;
  final Availability availability;

  /** 원본 종류(`jira.<값>`·외부 id `<값>:<id>`). 이슈 검색은 원본을 내지 않는다. */
  final Optional<String> resource;

  /** 고정 expand 쿼리. */
  final Optional<String> expand;

  private final String pathTemplate;
  private final String envelopeKey;

  WorkKind(
      String wire,
      Shape shape,
      Availability availability,
      String resource,
      String pathTemplate,
      String envelopeKey,
      String expand) {
    this.wire = wire;
    this.shape = shape;
    this.availability = availability;
    this.resource = Optional.ofNullable(resource);
    this.pathTemplate = pathTemplate;
    this.envelopeKey = envelopeKey;
    this.expand = Optional.ofNullable(expand);
  }

  /** 처음 수집·빈 큐에서 시작하는 루트 작업(TS ROOT_TASKS 순서). */
  static List<WorkKind> roots() {
    return Arrays.stream(values()).filter(k -> k.shape == Shape.ROOT_LISTING).toList();
  }

  static Optional<WorkKind> fromWire(String wire) {
    return Arrays.stream(values()).filter(k -> k.wire.equals(wire)).findFirst();
  }

  /** 검증을 마친 작업 JSON(체크포인트 검증 또는 이 수집이 만든 작업)의 종류. */
  static WorkKind of(JsonNode work) {
    return fromWire(work.get("kind").asString()).orElseThrow();
  }

  boolean batched() {
    return shape == Shape.ISSUE || shape == Shape.ATTACHMENT;
  }

  /** 목록 경로(인코딩한 부모 id를 넣는다). 목록 작업만 부른다(이슈·첨부에는 경로 틀이 없다). */
  String path(String encodedId) {
    return pathTemplate.replace("{id}", encodedId);
  }

  /** 목록 봉투에서 항목 배열의 키(TS readPage의 key). 목록 작업만 부른다. */
  String envelopeKey() {
    return envelopeKey;
  }
}
