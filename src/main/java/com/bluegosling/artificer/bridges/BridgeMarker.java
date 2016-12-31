package com.bluegosling.artificer.bridges;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used to indicate when annotations should have bridge classes generated. This can be used on
 * other meta-annotations that indicate an annotation will be used during annotation processing, in
 * such a way that using a bridge API instead of directly using an annotation mirror will be useful.
 *
 * <p>(Most developers will probably just use {@link Bridged}.)
 *
 * @see com.bluegosling.artificer.bridges Package summary
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BridgeMarker {
}
