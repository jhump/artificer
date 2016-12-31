package com.bluegosling.artificer.builders;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A simple meta-annotation to indicate when an annotation should have an associated builder.
 * Other annotations that are annotated with this one will have such a builder automatically
 * generated during compilation (via an annotation processor).
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@BuilderMarker
public @interface HasBuilder {
}
