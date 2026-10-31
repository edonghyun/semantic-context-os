package io.scos.collection.internal.postgres;

import io.scos.spi.connector.RawContent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;

/**
 * 원본 콘텐츠 ⇄ 열 다섯(`<prefix>_storage`·`_media_type`·`_inline`·`_sha256`·`_size`). 저장 방식은
 * "inline"·"blob", 콘텐츠가 없으면 다섯 모두 NULL이다. 쓰기 문장은 이름 있는 매개변수 :storage·:media·:inline·:sha·:size를 쓴다.
 */
final class ContentColumns {
  static final ContentColumns RAW_ITEM = new ContentColumns("content");
  static final ContentColumns QUARANTINED = new ContentColumns("quarantined");

  private final String prefix;

  private ContentColumns(String prefix) {
    this.prefix = prefix;
  }

  StatementSpec bind(StatementSpec spec, Optional<RawContent> content) {
    RawContent c = content.orElse(null);
    return spec.param(
            "storage", c == null ? null : c instanceof RawContent.Inline ? "inline" : "blob")
        .param("media", c == null ? null : c.mediaType())
        .param("inline", c instanceof RawContent.Inline i ? i.valueJson() : null)
        .param("sha", c instanceof RawContent.Blob b ? b.sha256() : null)
        .param("size", c instanceof RawContent.Blob b ? b.size() : null);
  }

  /** 읽은 blob은 참조만 싣는다(데이터는 raw_blobs). */
  Optional<RawContent> read(ResultSet rs) throws SQLException {
    String storage = rs.getString(prefix + "_storage");
    if (storage == null) return Optional.empty();
    String media = rs.getString(prefix + "_media_type");
    if (storage.equals("inline"))
      return Optional.of(new RawContent.Inline(media, rs.getString(prefix + "_inline")));
    return Optional.of(
        new RawContent.Blob(
            media,
            rs.getString(prefix + "_sha256"),
            rs.getLong(prefix + "_size"),
            Optional.empty()));
  }
}
