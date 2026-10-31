package io.scos.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** 그 역할의 공급자 설정이 이 값일 때만 조립한다. 비었거나 공백이면 그 역할의 기본 공급자로 본다({@link ProviderSelected}). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(ProviderSelected.class)
@interface ConditionalOnProvider {
  ProviderSelected.Role role();

  String value();
}
