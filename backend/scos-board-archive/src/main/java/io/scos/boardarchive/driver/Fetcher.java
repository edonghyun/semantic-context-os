package io.scos.boardarchive.driver;

import java.net.URI;

public interface Fetcher {
  Response get(URI uri);
}
