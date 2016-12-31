/**
 * Provides a facility for generating builder classes for annotation interfaces. The builder pattern
 * is a very common pattern for constructing value objects that have many properties. It is more
 * readable and scalable than constructors with long and telescoping argument lists. The result of
 * building is an instance that properly implements the annotation interface.
 *
 * <h3>{@code HasBuilder}</h3>
 * In the simplest form, just put {@link com.bluegosling.artificer.builders.HasBuilder} on an
 * annotation, and a builder class will be automatically generated. Let's take the following class
 * for example:
 * <pre>
 *  {@literal @}HasBuilder
 *  {@literal @}interface Foo {
 *     String value();
 *     boolean flag() default false;
 *     OtherAnnotation[] details() default { {@literal @}OtherAnnotation("baz") };
 *   }
 * </pre>
 * The presence of {@link com.bluegosling.artificer.builders.HasBuilder} will cause a builder to be
 * generated like the following:
 * <pre>
 * class Foo$Builder {
 *    private String value;
 *    private boolean flag;
 *    private List&lt;OtherAnnotation&gt; details;
 *
 *    public Foo$Builder() {
 *       this.flag = false;
 *       this.details =
 *             new ArrayList&lt;&gt;(Arrays.asList(new OtherAnnotation$Builder().value("baz").build()));
 *    }
 * 
 *    public Foo$Builder(Foo a) {
 *       this.value = a.value();
 *       this.flag = a.flag();
 *       this.details = new ArrayList&lt;&gt;(Arrays.asList(a.details()));
 *    }
 *
 *    public Foo$Builder value(String value) {
 *       if (value == null) {
 *          throw new NullPointerException("value");
 *       }
 *       this.value = value;
 *       return this;
 *    }
 * 
 *    public Foo$Builder flag(boolean flag) {
 *       this.flag = flag;
 *       return this;
 *    }
 * 
 *    public Foo$Builder details(OtherAnnotation... details) {
 *       this.details = new ArrayList&lt;&gt;(Arrays.asList(details));
 *       return this;
 *    }
 * 
 *    public Foo$Builder details(List&lt;OtherAnnotation&gt; details) {
 *       this.details = new ArrayList&lt;&gt;(details);
 *       return this;
 *    }
 * 
 *    public Foo$Builder addDetails(OtherAnnotation details) {
 *       if (details == null) {
 *          throw new NullPointerException("details");
 *       }
 *       this.details.add(details);
 *       return this;
 *    }
 *
 *    public Foo build() {
 *       if (value == null) {
 *          throw new IllegalStateException("value cannot be null");
 *       }
 *       return new Foo$Impl(this);
 *    }
 *
 *    private static class Foo$Impl implements Foo {
 *       private final String value;
 *       private final boolean flag;
 *       private final OtherAnnotation[] details;
 *
 *       Foo$Impl(Foo$Builder b) {
 *          this.value = b.value;
 *          this.flag = b.flag;
 *          this.details = b.details.toArray(new Foo.OtherAnnotation[b.details.size()]);
 *       }
 *
 *       public Class&lt;Foo&gt; annotationType() {
 *          return Foo.class;
 *       }
 *
 *       public String value() {
 *          return value;
 *       }
 * 
 *       public boolean flag() {
 *          return flag;
 *       }
 * 
 *       public OtherAnnotation[] details() {
 *          return details.clone();
 *       }
 *
 *       // ...
 *       // equals, hashCode, and toString
 *       // ...
 *    }
 * }
 * </pre>
 *
 * <h3>{@code BuilderMarker}</h3>
 * This library also allows you to create your own meta-annotations that indicate that a builder
 * should be generated.
 *
 * <p>For example, let's say you have a meta-annotation named {@code @Resource} and always want a
 * builder for every resource annotation. Instead of needing to mark resource annotations with both
 * {@code @Resource} and {@code @HasBuilder}, you can instead add
 * {@literal @}{@link com.bluegosling.artificer.builders.BuilderMarker} to the {@code @Resource}
 * meta-annotation. This will then cause every annotation marked as a {@code @Resource} to have a
 * builder generated.
 */
package com.bluegosling.artificer.builders;
