package com.bluegosling.artificer.builders;

import com.bluegosling.artificer.builders.HasBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;

/** An annotation used to to test builders. */
@HasBuilder
@Retention(RetentionPolicy.RUNTIME)
public @interface TestAnnotation {

  Inner1[] whoah() default
      {
          @Inner1(
              str = "string1",
              bool = true,
              l = 12345,
              d = 56789.1234,
              rp = RetentionPolicy.SOURCE,
              inner2 = @Inner2(
                  lists = {},
                  p = Processor.class
              )
          ),
          @Inner1(
              str = "string2",
              bool = false,
              l = 1111,
              d = 234567890123457890.0,
              rp = RetentionPolicy.RUNTIME,
              inner2 = @Inner2(
                  lists = { ArrayList.class, LinkedList.class, CopyOnWriteArrayList.class },
                  p = AbstractProcessor.class
              )
          ),
      };

  @interface Inner1 {
    String str();
    boolean bool();
    long l();
    double d();
    RetentionPolicy rp();
    Inner2 inner2();
    byte[] bytes() default { 0, 1, 2, 3 };
  }

  @interface Inner2 {
    @SuppressWarnings("rawtypes") Class<? extends List>[] lists();
    Class<? extends Processor> p();
    String s() default "foo";
    int i() default -23;
    long l() default 4_000_000_000L;
    char ch() default 'Z';
    float f() default 1.23f;
  }
}
