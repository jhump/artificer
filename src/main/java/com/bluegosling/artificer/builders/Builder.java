package com.bluegosling.artificer.builders;

import java.lang.annotation.Annotation;

/**
 * The abstract base class for annotation builders.
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 *
 * @param <A> the type of annotation built
 */
public abstract class Builder<A extends Annotation> {
   /**
    * Builds an instance of an annotation using the values provided. Any fields that have no
    * default value must be provided before calling this method.
    *
    * @return a new annotation instance
    * @throws IllegalStateException if any fields have no default value and have not been provided
    */
   public abstract A build();
}
