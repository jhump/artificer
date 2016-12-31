package com.bluegosling.artificer.bridges;

@Bridged
public @interface TestAnnotation {
   boolean getBool() default false;
   byte getByte() default 1;
   short getShort() default 2;
   char getChar() default 3;
   int getInt() default 4;
   long getLong() default 5;
   double getDouble() default 6;
   float getFloat() default 7;
   String getString() default "string";
   TestEnum1 getEnum() default TestEnum1.ABC;
   Class<?> getClazz() default Package.class;
   Nested getAnno() default @Nested("123");
   
   boolean[] getBools() default { false, true, false, true };
   byte[] getBytes() default { 0, 1, 2 };
   short[] getShorts() default { 1, 2, 3 };
   char[] getChars() default { 2, 3, 4 };
   int[] getInts() default { 3, 4, 5 };
   long[] getLongs() default { 4, 5, 6 };
   double[] getDoubles() default { 5, 6, 7 };
   float[] getFloats() default { 6, 7, 8 };
   String[] getStrings() default { "str", "ing" };
   TestEnum2[] getEnums() default { TestEnum2.FOO, TestEnum2.FOO };
   Class<?>[] getClazzes() default { Object.class, Throwable.class, Error.class, Exception.class };
   Nested[] getAnnos() default { @Nested("foo"), @Nested("bar"), @Nested("baz") };
   
   @interface Nested {
      String value() default "42";
   }
   
   enum TestEnum1 {
      ABC, XYZ
   }
   
   enum TestEnum2 {
      FOO, BAR, BAZ
   }
}
