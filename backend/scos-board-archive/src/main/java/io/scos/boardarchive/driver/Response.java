package io.scos.boardarchive.driver;

public record Response(int status, String body) {
  public Response requireOk() {
    if (status < 200 || status >= 300) throw new HttpStatusException(status);
    return this;
  }
}
