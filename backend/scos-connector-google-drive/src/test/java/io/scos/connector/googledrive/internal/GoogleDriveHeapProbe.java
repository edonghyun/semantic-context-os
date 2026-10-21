package io.scos.connector.googledrive.internal;

import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.support.FakeHttpServer.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Drive 큰 Workspace 응답 하나의 힙 프로브(F7 리뷰 B I1, 자식 JVM — 부모가 `-Xmx`를 정한다). args: 응답 바이트 수, 글자
 * 종류(ascii|hangul), fixture 디렉터리. 가짜 Google이 Docs 본문으로 그 크기의 JSON을 답하고, 제품 어댑터가 그 파일 하나만 남은 큐에서
 * 수집한다(UTF-8 디코드 → Jackson 트리 → JS 순서 직렬화·해시 → 원본 캡처, 페이지가 원본을 든 채로 끝난다). 결과 줄: `RESULT heap ok=…
 * stored=… bytes=…` — ok는 본문이 수집 제한 없이 원본에 다 들어갔다는 뜻이다. OOM이면 `ok=false`로 끝난다(종료 3). 가짜 서버가 같은 JVM에
 * 응답 바이트를 들고 있어 응답 크기만큼 보수적이다.
 */
public final class GoogleDriveHeapProbe {
  static final String DOC = "doc-1";

  /** 문단 하나의 글자 수 — Docs API 모양(body.content[].paragraph.elements[].textRun.content). */
  static final int PARAGRAPH_CHARS = 1000;

  public static void main(String[] args) {
    long bytes = Long.parseLong(args[0]);
    String alphabet = args[1];
    System.setProperty("scos.fixtures.google-drive.dir", args[2]);
    try (GoogleDriveScenario drive = new GoogleDriveScenario()) {
      drive.pathAnswers("/v1/documents/" + DOC, docsBody(bytes, alphabet));
      List<ItemView> upserted =
          drive.collectFrom(GoogleDriveScenario.resumeFile(DOC)).upsertedItems();
      ItemView file = upserted.getFirst();
      // 본문이 수집 제한으로 빠지지 않고 원본(envelope)에 다 들어갔는지: 저장 크기가 응답보다 크다
      boolean complete = file.content().size() >= bytes;
      System.out.println(
          "RESULT heap ok="
              + complete
              + " stored="
              + file.content().storage()
              + " bytes="
              + file.content().size());
    } catch (OutOfMemoryError e) {
      System.out.println("RESULT heap ok=false stored=none bytes=0");
      System.exit(3);
    }
    System.exit(0);
  }

  /** 대략 bytes 크기의 Docs 응답: 같은 글자로 채운 문단을 이어 붙인다(ascii는 1바이트, hangul은 3바이트 글자). */
  static Response docsBody(long bytes, String alphabet) {
    String unit = alphabet.equals("hangul") ? "가" : "a";
    String text = unit.repeat(PARAGRAPH_CHARS);
    String paragraph =
        "{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"" + text + "\"}}]}}";
    long paragraphs = bytes / paragraph.getBytes(StandardCharsets.UTF_8).length;
    StringBuilder b =
        new StringBuilder(
            "{\"documentId\":\"" + DOC + "\",\"title\":\"큰 문서\",\"body\":{\"content\":[");
    for (long i = 0; i < paragraphs; i++) {
      if (i > 0) b.append(',');
      b.append(paragraph);
    }
    b.append("]}}");
    byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
    return Response.binary(200, body, Map.of("Content-Type", "application/json"));
  }
}
