package com.bluegosling.artificer.bridges;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import com.bluegosling.artificer.bridges.TestAnnotation.TestEnum1;
import com.bluegosling.artificer.bridges.TestAnnotation.TestEnum2;
import com.google.auto.common.MoreElements;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;

public class BridgeTest {
   @Rule public CompilerRule compiler = new CompilerRule.Builder()
         .addSupportedAnnotation(TestAnnotation.class)
         .addCompilationUnit("Test",
               "import com.bluegosling.artificer.bridges.TestAnnotation;\n"
               + "import com.bluegosling.artificer.bridges.TestAnnotation.Nested;\n"
               + "import com.bluegosling.artificer.bridges.TestAnnotation.TestEnum1;\n"
               + "@TestAnnotation\n"
               + "public class Test {\n"
               + "  @TestAnnotation(\n"
               + "    getBytes = { 127, 126, 125, 124 },\n"
               + "    getString = \"shave the yak\",\n"
               + "    getEnum = TestEnum1.XYZ,\n"
               + "    getClazz = TestEnum1.class,\n"
               + "    getAnno = @Nested(\"test\")\n"
               + "  )"
               + "  public Test() {\n"
               + "  }\n"
               + "  @TestAnnotation\n"
               + "  public Test(boolean flag) {\n"
               + "  }\n"
               + "  @TestAnnotation(\n"
               + "    getBytes = { 127, 126, 125, 124 },\n"
               + "    getString = \"shave the yak\",\n"
               + "    getEnum = TestEnum1.XYZ,\n"
               + "    getClazz = TestEnum1.class,\n"
               + "    getAnno = @Nested(\"test\")\n"
               + "  )"
               + "  public Test(String someString) {\n"
               + "  }\n"
               + "}")
         .build();

   TestAnnotation$Bridge classAnno;
   TestAnnotation$Bridge ctorAnno1;
   TestAnnotation$Bridge ctorAnno2;
   TestAnnotation$Bridge ctorAnno3;
   
   @Before public void setup() {
      // extract annotation mirrors and wrap them in bridges
      int count = 0;
      for (Element e : 
            compiler.roundEnv()
                  .getElementsAnnotatedWith(compiler.annotations().iterator().next())) {
         count++;
         if (e.getKind() == ElementKind.CLASS) {
            classAnno = new TestAnnotation$Bridge(
                  MoreElements.getAnnotationMirror(e, TestAnnotation.class).get());
         } else if (e.getKind() == ElementKind.CONSTRUCTOR) {
            ExecutableElement ex = (ExecutableElement) e;
            if (ex.getParameters().isEmpty()) {
               ctorAnno1 = new TestAnnotation$Bridge(
                     MoreElements.getAnnotationMirror(e, TestAnnotation.class).get());
            } else if (ex.getParameters().get(0).asType().getKind() == TypeKind.BOOLEAN) {
               ctorAnno2 = new TestAnnotation$Bridge(
                     MoreElements.getAnnotationMirror(e, TestAnnotation.class).get());
            } else {
               ctorAnno3 = new TestAnnotation$Bridge(
                     MoreElements.getAnnotationMirror(e, TestAnnotation.class).get());
            }
         }
      }
      assertEquals(4, count);
      assertNotNull(classAnno);
      assertNotNull(ctorAnno1);
      assertNotNull(ctorAnno2);
      assertNotNull(ctorAnno3);
   }
   
   @Test public void accessors_defaultValues() {
      // test getting default values from the mirror
      assertFalse(classAnno.getBool());
      assertEquals((byte) 1, classAnno.getByte());
      assertEquals((short) 2, classAnno.getShort());
      assertEquals((char) 3, classAnno.getChar());
      assertEquals(4, classAnno.getInt());
      assertEquals(5L, classAnno.getLong());
      assertEquals(6.0, classAnno.getDouble(), 0.0);
      assertEquals(7.0f, classAnno.getFloat(), 0.0);
      assertEquals("string", classAnno.getString());
      assertEquals(TestEnum1.ABC, classAnno.getEnum());
      assertEquals(asTypeElement(Package.class), classAnno.getClazz());
      assertEquals("123", classAnno.getAnno().value());
      
      assertEquals(Arrays.asList(false, true, false, true), classAnno.getBools());
      assertEquals(Arrays.asList((byte) 0, (byte) 1, (byte) 2), classAnno.getBytes());
      assertEquals(Arrays.asList((short) 1, (short) 2, (short) 3), classAnno.getShorts());
      assertEquals(Arrays.asList((char) 2, (char) 3, (char) 4), classAnno.getChars());
      assertEquals(Arrays.asList(3, 4, 5), classAnno.getInts());
      assertEquals(Arrays.asList(4L, 5L, 6L), classAnno.getLongs());
      assertEquals(Arrays.asList(5.0, 6.0, 7.0), classAnno.getDoubles());
      assertEquals(Arrays.asList(6.0f, 7.0f, 8.0f), classAnno.getFloats());
      assertEquals(Arrays.asList("str", "ing"), classAnno.getStrings());
      assertEquals(Arrays.asList(TestEnum2.FOO, TestEnum2.FOO), classAnno.getEnums());
      assertEquals(Arrays.asList(asTypeElement(Object.class), asTypeElement(Throwable.class),
            asTypeElement(Error.class), asTypeElement(Exception.class)),
            classAnno.getClazzes());
      assertEquals(3, classAnno.getAnnos().size());
      assertEquals("foo", classAnno.getAnnos().get(0).value());
      assertEquals("bar", classAnno.getAnnos().get(1).value());
      assertEquals("baz", classAnno.getAnnos().get(2).value());
   }
   
   @Test public void accessors_specifiedValues() {
      assertEquals(Arrays.asList((byte) 127, (byte) 126, (byte) 125, (byte) 124),
            ctorAnno1.getBytes());
      assertEquals("shave the yak", ctorAnno1.getString());
      assertEquals(TestEnum1.XYZ, ctorAnno1.getEnum());
      assertEquals(asTypeElement(TestEnum1.class), ctorAnno1.getClazz());
      assertEquals("test", ctorAnno1.getAnno().value());
   }
   
   private TypeElement asTypeElement(Class<?> clazz) {
      return compiler.processingEnv().getElementUtils().getTypeElement(clazz.getCanonicalName());
   }
   
   @Test public void equalsAndHashCode() {
      assertEquals(classAnno, ctorAnno2);
      assertEquals(ctorAnno2, classAnno);
      assertEquals(classAnno.hashCode(), ctorAnno2.hashCode());

      assertEquals(ctorAnno1, ctorAnno3);
      assertEquals(ctorAnno3, ctorAnno1);
      assertEquals(ctorAnno1.hashCode(), ctorAnno3.hashCode());
      
      assertNotEquals(classAnno, ctorAnno1);
      assertNotEquals(ctorAnno1, ctorAnno2);
      assertNotEquals(ctorAnno2, ctorAnno3);
   }
}
