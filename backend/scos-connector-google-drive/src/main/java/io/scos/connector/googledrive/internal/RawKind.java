package io.scos.connector.googledrive.internal;

/** Drive 원본의 종류: 외부 id 접두(`<prefix>:<id>`)와 resourceType(`gdrive.<prefix>`). */
enum RawKind {
  FILE("file"),
  DRIVE("drive"),
  SHEET("sheet"),
  BINARY("binary");

  private final String prefix;

  RawKind(String prefix) {
    this.prefix = prefix;
  }

  String externalId(String id) {
    return prefix + ":" + id;
  }

  String resourceType() {
    return "gdrive." + prefix;
  }
}
