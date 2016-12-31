package com.bluegosling.artificer.builders;

import com.bluegosling.artificer.builders.HasBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An annotation used to to test builders. */
@HasBuilder
@Retention(RetentionPolicy.RUNTIME)
public @interface Foo {
  String value();
  boolean flag() default false;
  OtherAnnotation[] details() default { @OtherAnnotation("baz") };

  @interface OtherAnnotation {
    String value() default "snafu";
  }
}
