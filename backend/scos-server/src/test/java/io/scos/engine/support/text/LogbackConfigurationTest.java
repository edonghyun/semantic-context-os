package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * logback-spring.xml의 class 속성은 컴파일러가 보지 못하는 FQN이다(단계 3 Review Focus 1). 패키지를 옮기고 이 값을 놓치면 logback이
 * 인코더를 만들지 못해 데몬 로그가 JSON 한 줄 형식을 잃고 status.sh의 `"level":"warn|error"` grep이 조용히 0이 된다.
 */
@DisplayName("logback 설정의 io.scos class 값은 실제 클래스다(놓치면 JSON 로그를 잃는다)")
class LogbackConfigurationTest {
  static final String CONFIG = "/logback-spring.xml";

  static List<String> scosClassAttributes() {
    try (InputStream in = LogbackConfigurationTest.class.getResourceAsStream(CONFIG)) {
      if (in == null) throw new IllegalStateException("given 준비 실패: " + CONFIG + "가 클래스패스에 없다");
      NodeList all =
          DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(in)
              .getElementsByTagName("*");
      List<String> names = new ArrayList<>();
      for (int i = 0; i < all.getLength(); i++) {
        String value = ((Element) all.item(i)).getAttribute("class");
        if (value.startsWith("io.scos.")) names.add(value);
      }
      if (names.isEmpty())
        throw new IllegalStateException("given 준비 실패: " + CONFIG + "에 io.scos class 값이 없다");
      return names;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("모든 io.scos class 값을 클래스로 읽을 수 있다")
  void everyClassAttributeResolves() {
    // given
    List<String> names = scosClassAttributes();

    // when
    List<String> missing = names.stream().filter(name -> !loadable(name)).toList();

    // then
    assertThat(missing).isEmpty();
  }

  static boolean loadable(String name) {
    try {
      Class.forName(name, false, LogbackConfigurationTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
