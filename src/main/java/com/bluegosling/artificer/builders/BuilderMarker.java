package com.bluegosling.artificer.builders;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used to indicate when annotations should have builder classes generated. This can be used on
 * other meta-annotations that indicate an annotation will be used at runtime, in such a way that
 * programmatically creating instances is useful.
 *
 * <p>(Most developers will probably just use {@link HasBuilder}.)
 *
 * @see com.bluegosling.artificer.builders Package summary
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BuilderMarker {
}
