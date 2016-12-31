package com.bluegosling.artificer.internal;

import com.google.auto.common.BasicAnnotationProcessor;
import com.google.auto.common.MoreElements;
import com.google.auto.common.SuperficialValidation;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.squareup.javapoet.AnnotationSpec;

import java.lang.annotation.Annotation;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import javax.annotation.Generated;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic.Kind;

import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * An abstract annotation processor for meta-meta-annotations.
 *
 * <p>A meta-annotation is one that annotates other annotations. A meta-meta-annotation, therefore,
 * is one that annotates other meta-annotations. This processor examines all annotations in a round
 * and finds the elements marked with the meta-meta-annotation of interest. It then finds all
 * elements marked with those meta-annotations and processes them. Concrete sub-classes are
 * responsible for this processing and for supplying the meta-meta-annotation of interest.
 *
 * <p>Similar to {@link BasicAnnotationProcessor}, this class reduces implementation burden in
 * sub-classes by smartly handling unresolved elements. For example, if a type element appears in a
 * processing round, but one of its methods has an {@link TypeKind#ERROR} return type, this abstract
 * class intercepts that type and defers it to a subsequent processing round (in which the method's
 * return type may be resolved).
 */
public abstract class AbstractMetaMetaProcessor extends AbstractProcessor {
   private static final DateFormat ISO_8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

   private static final Splitter ON_COMMA = Splitter.on(',');

   /**
    * Packages to exclude from consideration. We can't generate code in the "java" package (or any
    * sub-packages) since that requires a boot classpath to load. So we don't even try. Also, a
    * system property ({@code artificer.packages-to-exclude}) can be defined with a comma-separated
    * list of other packages to ignore. These could include 3rd-party digitally signed packages (in
    * which we can't generate code since it would be invalid per package signature).
    */
   private static final Set<String> PACKAGES_TO_EXCLUDE;
   static {
      Set<String> pkgs = new HashSet<>();
      pkgs.add("java");
      String property = System.getProperty("artificer.packages-to-exclude");
      if (property != null) {
         Iterables.addAll(pkgs, ON_COMMA.split(property));
      }
      PACKAGES_TO_EXCLUDE = Collections.unmodifiableSet(pkgs);
   }

   /**
    * The queue of elements to process. It survives across processing rounds so that types that
    * are invalid in one round (e.g. unresolvable elements) can be deferred to a subsequent one.
    */
   private final AnnotationQueue queue = new AnnotationQueue();

   @Override
   public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      queue.newRound(annotations, roundEnv);

      while (!queue.isEmpty()) {
         processAnnotation(queue.poll());
      }

      return false;
   }

   @Override
   public Set<String> getSupportedAnnotationTypes() {
      // we have to see all of them to find possibly user-defined meta-annotations
      return ImmutableSet.of("*");
   }

   /** Returns the meta-meta-annotation of interest. */
   protected abstract Class<? extends Annotation> metaMetaAnnotation();

   /** Processes a single annotation that is (indirectly) marked with the meta-meta-annotation. */
   protected abstract void processAnnotation(TypeElement annotation);

   /**
    * Creates an {@literal @}{@link Generated} annotation for source code generated on behalf of the
    * given type.
    * 
    * @param onBehalfOfType the type on whose behalf code is generated
    * @return an annotation spec
    */
   protected AnnotationSpec generatedAnnotation(TypeElement onBehalfOfType) {
      return AnnotationSpec.builder(Generated.class)
            .addMember("value", "$S", getClass().getSimpleName())
            .addMember("comments", "\"Generated for $L\"",
                  onBehalfOfType.getQualifiedName().toString())
            .addMember("date", "$S", ISO_8601.format(new Date()))
            .build();
   }
   
   /**
    * Adds an annotation to the queue to be processed. If a processor recursively processes nested
    * annotation types, for example, then it can use this method to enqueue those nested types.
    */
   protected void enqueueAnnotation(TypeElement annotation, ExecutableElement source) {
      queue.add(annotation, source);
   }

   /**
    * Returns the name of the package if a builder cannot be generated for the given type due to
    * being in a disallowed package. If a builder can be generated, {@code null} is returned.
    */
   private static String forbiddenPackage(TypeElement type) {
      for (String pkg : PACKAGES_TO_EXCLUDE) {
         if (type.getQualifiedName().toString().startsWith(pkg + ".")) {
            return pkg;
         }
      }
      return null;
   }

   /**
    * Queue of annotation types to be processed. This will not return enqueued elements that are
    * {@linkplain SuperficialValidation#validateElement(Element) invalid} but instead defer them
    * to subsequent rounds. It also discards elements that are either not allowed to be processed
    * (due to the declaring package) or that do not need to be processed (non-root elements that
    * already have associated builders).
    */
   private class AnnotationQueue {
      private final Set<String> deferred = new LinkedHashSet<>();
      private final Set<TypeElement> alreadySeen = new HashSet<>();
      private final Queue<TypeElement> queue = new LinkedList<>();

      AnnotationQueue() {
      }

      public TypeElement poll() {
         return queue.remove();
      }

      public void add(TypeElement e, ExecutableElement source) {
         if (alreadySeen.add(e)) {
            String pkg = forbiddenPackage(e);
            if (pkg != null) {
               processingEnv.getMessager().printMessage(Kind.WARNING,
                     String.format("Cannot generate builder for %s because it is in package %s",
                           e.getQualifiedName(), pkg),
                     source);
               return;
            }
            if (SuperficialValidation.validateElement(e)) {
               queue.add(e);
            } else {
               deferred.add(e.getQualifiedName().toString());
            }
         }
      }

      public boolean isEmpty() {
         return queue.isEmpty();
      }

      /**
       * Seeds the queue with elements from a new processing round. If any elements from prior
       * rounds
       * were deferred, they will be included in the queue if they are now valid in the new round.
       */
      public void newRound(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
         if (roundEnv.processingOver()) {
            reportMissing();
            return;
         }

         // first get any deferred elements that are now valid
         for (Iterator<String> iter = deferred.iterator(); iter.hasNext();) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(iter.next());
            if (type != null && SuperficialValidation.validateElement(type)) {
               alreadySeen.add(type);
               queue.add(type);
               iter.remove();
            }
         }

         Class<? extends Annotation> metaMeta = metaMetaAnnotation();
         // then add any new elements for this round
         for (TypeElement annotation : annotations) {
            if (!MoreElements.isAnnotationPresent(annotation, metaMeta)) {
               continue;
            }
            processingEnv.getMessager().printMessage(Kind.NOTE, "Processing " + annotation + " (marked with " + metaMeta + ")");
            for (Element e : roundEnv.getElementsAnnotatedWith(annotation)) {
               if (e.getKind() == ElementKind.ANNOTATION_TYPE) {
                  TypeElement type = MoreElements.asType(e);
                  String pkg = forbiddenPackage(type);
                  if (pkg != null) {
                     processingEnv.getMessager().printMessage(Kind.WARNING,
                           String.format(
                                 "Cannot generate builder for %s because it is in package %s",
                                 type.getQualifiedName(), pkg),
                           type);
                  } else if (alreadySeen.add(type)) {
                     if (SuperficialValidation.validateElement(type)) {
                        queue.add(type);
                     } else {
                        deferred.add(type.getQualifiedName().toString());
                     }
                  }
               }
            }
         }
      }

      private void reportMissing() {
         for (String name : deferred) {
            TypeElement element = processingEnv.getElementUtils().getTypeElement(name);
            if (element != null) {
               processingEnv.getMessager().printMessage(ERROR,
                     processingErrorMessage("this " + Ascii.toLowerCase(element.getKind().name())),
                     element);
            } else {
               processingEnv.getMessager().printMessage(ERROR, processingErrorMessage(name));
            }
         }
      }

      private String processingErrorMessage(String target) {
         return String.format(
               "%s was unable to process %s because not all of its dependencies could be resolved."
                     + "Check for compilation errors or a circular dependency with generated code.",
               getClass().getCanonicalName(), target);
      }
   }
}
