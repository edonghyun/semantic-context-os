package io.scos;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.util.regex.Pattern;

/**
 * ArchUnit 가져오기 범위: 제품 코드와 테스트 코드를 클래스 파일 위치로 가른다. ArchUnit 기본
 * 옵션(DoNotIncludeTests·OnlyIncludeTests)은 testFixtures
 * 디렉터리·`*-test-fixtures.jar`·`scos-test-support`를 제품 쪽으로 센다(단계 4 Review Focus 3).
 */
final class TestLocations {
  /** 제품 클래스 디렉터리(main·harness 소스셋). scos-test-support의 main은 테스트 코드다. */
  static final Pattern PRODUCT_DIR = Pattern.compile("/build/classes/java/(main|harness)/");

  /** 테스트 클래스 디렉터리(test·testFixtures 소스셋). */
  static final Pattern TEST_DIR = Pattern.compile("/build/classes/java/(test|testFixtures)/");

  static final Pattern TEST_SUPPORT_DIR =
      Pattern.compile("/scos-test-support/build/classes/java/main/");

  /** 제품 jar 이름(server는 classifier가 없다). testFixtures jar는 뺀다. */
  static final Pattern PRODUCT_JAR = Pattern.compile("^scos-(?!test-support)[a-z-]+\\.jar$");

  static final Pattern TEST_SUPPORT_JAR = Pattern.compile("^scos-test-support[a-z-]*\\.jar$");

  static final String TEST_FIXTURES_JAR_SUFFIX = "-test-fixtures.jar";

  private TestLocations() {}

  /** 제품 코드: main·harness 클래스 디렉터리이거나 제품 jar. */
  static final class ProductCode implements ImportOption {
    @Override
    public boolean includes(Location location) {
      String uri = location.asURI().toString();
      String jar = jarName(uri);
      if (jar != null)
        return PRODUCT_JAR.matcher(jar).matches() && !jar.endsWith(TEST_FIXTURES_JAR_SUFFIX);
      return PRODUCT_DIR.matcher(uri).find() && !TEST_SUPPORT_DIR.matcher(uri).find();
    }
  }

  /** 테스트 코드: test·testFixtures 클래스 디렉터리, testFixtures jar, scos-test-support(jar나 그 main 디렉터리). */
  static final class TestCode implements ImportOption {
    @Override
    public boolean includes(Location location) {
      String uri = location.asURI().toString();
      String jar = jarName(uri);
      if (jar != null)
        return jar.endsWith(TEST_FIXTURES_JAR_SUFFIX) || TEST_SUPPORT_JAR.matcher(jar).matches();
      return TEST_DIR.matcher(uri).find() || TEST_SUPPORT_DIR.matcher(uri).find();
    }
  }

  /** `jar:file:/…/name.jar!/…`의 jar 파일 이름. jar 안이 아니면 null. */
  static String jarName(String uri) {
    int bang = uri.indexOf("!/");
    if (!uri.startsWith("jar:") || bang < 0) return null;
    String path = uri.substring(0, bang);
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
