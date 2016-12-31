/**
 * Provides a facility for generating bridge classes for annotation interfaces.
 *
 * <h3>{@code Bridged}</h3>
 * In the simplest form, just put {@link com.bluegosling.artificer.bridges.Bridged} on an
 * annotation, and a bridge class will be automatically generated. Let's take the following class
 * for example:
 * <pre>
 *  {@literal @}Bridged
 *  {@literal @}interface Foo {
 *     Class<? extends PolicyFactory> value();
 *     SomeEnum enumField() default SomeEnum.SUPERDUPER;
 *     int[] multipliers();
 *     double adder();
 *   }
 * </pre>
 * The presence of {@link com.bluegosling.artificer.bridges.Bridged} will cause a bridge to be
 * generated like the following:
 * <pre>
 * class Foo$Bridge extends Bridge&lt;Foo&gt; {
 *    private final TypeElement value;
 *    private final SomeEnum enumField;
 *    private final List&lt;Integer&gt; multipliers;
 *    private final double adder;
 *    
 *    public Foo$Bridge(AnnotationMirror mirror) {
 *       super(mirror, Foo.class);
 *       TypeElement value = null;
 *       SomeEnum enumField = null;
 *       List&lt;Integer&gt; multipliers = null;
 *       Double adder = null;
 *       Map<? extends ExecutableElement, ? extends AnnotationValue> values =
 *             mirror.getElementValues();
 *       for (Element e : mirror.getAnnotationType().asElement().getEnclosedElements()) {
 *          if (e.getKind() != ElementKind.METHOD) {
 *             continue;
 *          }
 *          ExecutableElement ex = (ExecutableElement) e;
 *          if (ex.getModifiers().contains(Modifier.STATIC)
 *                || !ex.getParameters().isEmpty()
 *                || ex.getSimpleName().contentsEquals("hashCode")
 *                || ex.getSimpleName().contentsEquals("toString")
 *                || ex.getSimpleName().contentsEquals("annotationType")) {
 *             continue;
 *          }
 *          AnnotationValue v = values.get(ex);
 *          if (v == null) {
 *             v = ex.getDefaultValue();
 *          }
 *          if (v == null) {
 *             throw new IllegalStateException("Invalid mirror: no value for "
 *                   + ex.getSimpleName());
 *          }
 *          switch (ex.getSimpleName().toString()) {
 *             case "value":
 *                value = (TypeElement) ((DeclaredType) v.getValue()).asElement();
 *                break;
 *             case "enumField":
 *                enumField = SomeEnum.valueOf(
 *                      ((VariableElement) v.getValue()).getSimpleName().toString());
 *                break;
 *             case "multipliers":
 *                List&lt;?&gt; avs = (List&lt;?&gt;) v.getValue();
 *                multipliers = new ArrayList<>(avs.size());
 *                for (Object o : avs) {
 *                   AnnotationValue av = (AnnotationValue) o;
 *                   multipliers.add((Integer) av.getValue());
 *                }
 *                break;
 *             case "adder":
 *                adder = (Double) v.getValue();
 *                break;
 *             default:
 *                throw new IllegalStateException("Unrecognized method: " + ex.getSimpleName());
 *          }
 *       }
 *       if (value == null || enumField == null || multipliers == null || adder == null) {
 *          throw new IllegalStateException("one or more fields missing");
 *       }
 *       this.value = value;
 *       this.enumField = enumField;
 *       this.multipliers = Collections.unmodifiableList(multipliers);
 *       this.adder = adder;
 *    }
 *    
 *    public TypeElement value() {
 *       return value;
 *    }
 * 
 *    public SomeEnum enumField() {
 *       return enumField;
 *    }
 * 
 *    public List&lt;Integer&gt; multipliers() {
 *       return multipliers;
 *    }
 * 
 *    public double adder() {
 *       return adder;
 *    }
 *
 *    // ...
 *    // equals and hashCode
 *    // ...
 * }
 * </pre>
 *
 * <h3>{@code BridgeMarker}</h3>
 * This library also allows you to create your own meta-annotations that indicate that a bridge
 * should be generated.
 *
 * <p>For example, let's say you have a meta-annotation named {@code @Resource} and always want a
 * bridge for every resource annotation. Instead of needing to mark resource annotations with both
 * {@code @Resource} and {@code Bridged}, you can instead add
 * {@literal @}{@link com.bluegosling.artificer.builders.BridgeMarker} to the {@code @Resource}
 * meta-annotation. This will then cause every annotation marked as a {@code @Resource} to have a
 * bridge generated.
 */
package com.bluegosling.artificer.bridges;