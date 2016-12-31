package com.bluegosling.artificer.builders;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bluegosling.artificer.internal.AbstractMetaMetaProcessor;
import org.junit.Test;

import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.processing.Processor;

public class BuilderTest {

   @Test public void mustInitializeAllFieldsWithoutDefault() {
      {
         Foo$Builder builder = new Foo$Builder();
         try {
            builder.build();
            fail("Expecting an IllegalStateException but nothing thrown");
         } catch (IllegalStateException e) {
         }
         
         // after we set value, build will work because all fields without defaults are set
         builder.value("fubar").build();
      }
      
      {
         TestAnnotation$Inner1$Builder builder = new TestAnnotation$Inner1$Builder();
         try {
            builder.build();
            fail("Expecting an IllegalStateException but nothing thrown");
         } catch (IllegalStateException e) {
         }
         
         builder.str("snafu");
         builder.rp(RetentionPolicy.CLASS);
         builder.l(1010101);
         builder.bool(false);
         builder.d(6789.0);

         // still one more unset
         try {
            builder.build();
            fail("Expecting an IllegalStateException but nothing thrown");
         } catch (IllegalStateException e) {
         }
         
         @SuppressWarnings("rawtypes")
         List<Class<? extends List>> empty = Collections.emptyList();
         builder.inner2(new TestAnnotation$Inner2$Builder()
               .lists(empty)
               .p(Processor.class)
               .build());

         // now it will work
         builder.build();
      }
   }
   
   @Test public void annotationType() {
      Foo foo = new Foo$Builder().value("abc").build();
      assertEquals(Foo.class, foo.annotationType());
      assertEquals(Foo.OtherAnnotation.class, foo.details()[0].annotationType());
      
      TestAnnotation anno = new TestAnnotation$Builder().build();
      assertEquals(TestAnnotation.class, anno.annotationType());
      assertEquals(TestAnnotation.Inner1.class, anno.whoah()[0].annotationType());
      assertEquals(TestAnnotation.Inner2.class, anno.whoah()[0].inner2().annotationType());
   }

   @Foo("abc")
   @TestAnnotation
   @Test public void defaultValues() throws Exception {
      Foo fooBuilt = new Foo$Builder().value("abc").build();
      Foo fooLoaded = BuilderTest.class.getMethod("defaultValues").getAnnotation(Foo.class);
      // built annotation gets same default values as actual annotation use above
      assertEquals(fooLoaded.value(), fooBuilt.value());
      assertEquals(fooLoaded.flag(), fooBuilt.flag());
      assertArrayEquals(fooLoaded.details(), fooBuilt.details());

      TestAnnotation annoBuilt = new TestAnnotation$Builder().build();
      TestAnnotation annoLoaded = BuilderTest.class.getMethod("defaultValues")
            .getAnnotation(TestAnnotation.class);
      assertArrayEquals(annoLoaded.whoah(), annoBuilt.whoah());
   }

   @Test public void setters() {
      Foo foo = new Foo$Builder()
            .value("foo")
            .flag(true)
            .details(new Foo$OtherAnnotation$Builder().value("frobnitz").build())
            .build();
      assertEquals("foo", foo.value());
      assertTrue(foo.flag());
      assertEquals(1, foo.details().length);
      assertEquals("frobnitz", foo.details()[0].value());
      
      @SuppressWarnings("unchecked") // generic array creation in var-args :(
      TestAnnotation anno = new TestAnnotation$Builder()
            .whoah(new TestAnnotation$Inner1$Builder()
                  .bool(true)
                  .l(42L)
                  .d(3.14159)
                  .bytes((byte)1, (byte)2, (byte)3, (byte)4, (byte)5, (byte)6, (byte)7, (byte)8)
                  .str("strisselspalt")
                  .rp(RetentionPolicy.SOURCE)
                  .inner2(new TestAnnotation$Inner2$Builder()
                        .p(AbstractMetaMetaProcessor.class)
                        .lists(ArrayList.class, LinkedList.class)
                        .build())
                  .build())
            .build();
      assertEquals(1, anno.whoah().length);
      assertTrue(anno.whoah()[0].bool());
      assertEquals(42L, anno.whoah()[0].l());
      assertEquals(3.14159, anno.whoah()[0].d(), 0.0);
      assertArrayEquals(
            new byte[] { (byte)1, (byte)2, (byte)3, (byte)4, (byte)5, (byte)6, (byte)7, (byte)8 },
            anno.whoah()[0].bytes());
      assertEquals("strisselspalt", anno.whoah()[0].str());
      assertEquals(RetentionPolicy.SOURCE, anno.whoah()[0].rp());
      assertEquals(AbstractMetaMetaProcessor.class, anno.whoah()[0].inner2().p());
      assertArrayEquals(new Class<?>[] { ArrayList.class, LinkedList.class },
            anno.whoah()[0].inner2().lists());
   }

   @Test public void arraysAreDefensivelyCloned() {
      Foo foo = new Foo$Builder().value("string").build();
      assertNotSame(foo.details(), foo.details());
      
      // modifying returned array does not change the annotation
      Foo.OtherAnnotation[] details = foo.details();
      details[0] = new Foo$OtherAnnotation$Builder().value("whatchamacallit").build();
      assertFalse(Arrays.equals(foo.details(), details));
      assertEquals("baz", foo.details()[0].value());
   }

   @Test public void appendElements() {
      // set list and then add extra elements
      @SuppressWarnings("unchecked") // generic array creation due to var-args :(
      TestAnnotation.Inner2 anno =
            new TestAnnotation$Inner2$Builder()
                  .lists(ArrayList.class, LinkedList.class)
                  .addLists(CopyOnWriteArrayList.class)
                  .p(Processor.class)
                  .build();
      assertArrayEquals(
            new Class<?>[] { ArrayList.class, LinkedList.class, CopyOnWriteArrayList.class },
            anno.lists());
   }
   
   @Test public void appendElementsWithoutDefaultValue() {
      // list is initialized to empty (since it has no default) before elements added
      TestAnnotation.Inner2 anno =
            new TestAnnotation$Inner2$Builder()
                  .addLists(ArrayList.class)
                  .addLists(LinkedList.class)
                  .p(Processor.class)
                  .build();
      assertArrayEquals(new Class<?>[] { ArrayList.class, LinkedList.class },
            anno.lists());
   }

   @Test public void appendElementsToDefaultValue() {
      Foo foo = new Foo$Builder()
            .value("value")
            .addDetails(new Foo$OtherAnnotation$Builder().value("wonk").build())
            .addDetails(new Foo$OtherAnnotation$Builder().value("fail").build())
            .build();
      assertEquals(3, foo.details().length);
      // wonk and fail elements appended to default element
      assertEquals("baz", foo.details()[0].value());
      assertEquals("wonk", foo.details()[1].value());
      assertEquals("fail", foo.details()[2].value());
   }
   
   @Foo("abc")
   @Test public void copyConstructor() throws Exception {
      Foo fooLoaded = BuilderTest.class.getMethod("copyConstructor").getAnnotation(Foo.class);
      Foo newFoo = new Foo$Builder(fooLoaded).value("def").build();
      assertNotEquals(fooLoaded, newFoo);
      assertArrayEquals(fooLoaded.details(), newFoo.details());
      
      Foo anotherFoo = new Foo$Builder(newFoo)
            .addDetails(new Foo$OtherAnnotation$Builder().value("word").build())
            .build();
      assertNotEquals(newFoo, anotherFoo);
      assertEquals(newFoo.value(), anotherFoo.value());
      assertEquals(newFoo.details().length + 1, anotherFoo.details().length);
   }

   @Foo("abc")
   @TestAnnotation
   @Test public void equalsAndHashCode() throws Exception {
      // check that our built instances correctly compute same hash code and are considered equal
      // to the instances provided by core reflection

      Foo fooBuilt = new Foo$Builder().value("abc").build();
      Foo fooLoaded = BuilderTest.class.getMethod("equalsAndHashCode").getAnnotation(Foo.class);
      assertEquals(fooBuilt, fooLoaded);
      assertEquals(fooLoaded, fooBuilt);
      assertEquals(fooBuilt.hashCode(), fooLoaded.hashCode());

      TestAnnotation annoBuilt = new TestAnnotation$Builder().build();
      TestAnnotation annoLoaded = BuilderTest.class.getMethod("equalsAndHashCode")
            .getAnnotation(TestAnnotation.class);
      assertEquals(annoBuilt, annoLoaded);
      assertEquals(annoLoaded, annoBuilt);
      assertEquals(annoBuilt.hashCode(), annoLoaded.hashCode());
   }

   @Test public void testToString() {
      assertEquals("@com.bluegosling.artificer.builders.Foo("
            + "value=\"the \\\"string\\\" value\","
            + "flag=false,"
            + "details={"
            + "@com.bluegosling.artificer.builders.Foo.OtherAnnotation(value=\"baz\"),"
            + "})",
            new Foo$Builder().value("the \"string\" value").build().toString());
      assertEquals("@com.bluegosling.artificer.builders.TestAnnotation("
            + "whoah={"
               + "@com.bluegosling.artificer.builders.TestAnnotation.Inner1("
                  + "str=\"string1\","
                  + "bool=true,"
                  + "l=12345,"
                  + "d=56789.1234,"
                  + "rp=java.lang.annotation.RetentionPolicy.SOURCE,"
                  + "inner2=@com.bluegosling.artificer.builders.TestAnnotation.Inner2("
                     + "lists={},"
                     + "p=javax.annotation.processing.Processor.class,"
                     + "s=\"foo\","
                     + "i=-23,"
                     + "l=4000000000,"
                     + "ch='Z',"
                     + "f=1.23),"
                  + "bytes={0,1,2,3,}),"
               + "@com.bluegosling.artificer.builders.TestAnnotation.Inner1("
                  + "str=\"string2\","
                  + "bool=false,"
                  + "l=1111,"
                  + "d=2.34567890123457888E17,"
                  + "rp=java.lang.annotation.RetentionPolicy.RUNTIME,"
                  + "inner2=@com.bluegosling.artificer.builders.TestAnnotation.Inner2("
                     + "lists={java.util.ArrayList.class,java.util.LinkedList.class,"
                        + "java.util.concurrent.CopyOnWriteArrayList.class,},"
                     + "p=javax.annotation.processing.AbstractProcessor.class,"
                     + "s=\"foo\","
                     + "i=-23,"
                     + "l=4000000000,"
                     + "ch='Z',"
                     + "f=1.23),"
                  + "bytes={0,1,2,3,}),"
               + "})",
            new TestAnnotation$Builder().build().toString());
   }
}
