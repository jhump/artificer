package com.bluegosling.artificer.bridges;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

/**
 * Base class for annotation bridges. Generated bridges extend this base class.
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 *
 * @param <A> the annotation type represented by a bridge
 */
public abstract class Bridge<A extends Annotation> {
   private final AnnotationMirror mirror;
   private final Class<A> annotationType;
   
   /**
    * Creates a new annotation bridge for the given annotation type and that wraps the given mirror.
    * 
    * @param mirror an annotation mirror
    * @param annotationType the annotation type for this bridge
    */
   protected Bridge(AnnotationMirror mirror, Class<A> annotationType) {
      // verify that the mirror is for the same annotation type
      TypeElement annotationElement = (TypeElement) mirror.getAnnotationType().asElement();
      if (!annotationElement.getQualifiedName().contentEquals(annotationType.getCanonicalName())) {
         throw new IllegalArgumentException("Mirror should be for annotation type "
               + annotationType.getCanonicalName() + " but was instead for "
               + annotationElement.getQualifiedName().toString());
      }
      this.mirror = mirror;
      this.annotationType = requireNonNull(annotationType);
   }
   
   /**
    * Returns the annotation type for this bridge.
    * 
    * @return the annotation type for this bridge
    */
   public Class<A> annotationType() {
      return annotationType;
   }
   
   /**
    * Returns the underlying annotation mirror.
    * 
    * @return the underlying annotation mirror
    */
   public AnnotationMirror asMirror() {
      return mirror;
   }
   
   /**
    * Determines if the given object is equal to this one. An annotation bridge is equal to another
    * object if that object is also a bridge for the same annotation type and with all of the same
    * values.
    */
   @Override
   public abstract boolean equals(Object o);

   /**
    * Computes the hash code for an annotation bridge. The hash code for a bridge is basically the
    * same as the process for computing the hash code for an {@linkplain Annotation#hashCode()
    * annotation}. For each annotation method, the hash code of its value (which could be a
    * {@link List} or a {@link TypeElement} instead of an array of {@link Class}) is XOR'ed with the
    * hash code of the method name (as computed by {@link String#hashCode()}). The sum of all such
    * results, for each annotation method, is the bridge's hash code.
    */
   @Override
   public abstract int hashCode();
   
   @Override
   public String toString() {
      return mirror.toString();
   }
}
