package com.bluegosling.artificer.internal;

import com.bluegosling.artificer.bridges.Bridge;
import com.bluegosling.artificer.bridges.BridgeMarker;
import com.google.auto.common.MoreElements;
import com.google.auto.service.AutoService;
import com.google.common.base.Throwables;
//import com.google.googlejavaformat.java.Formatter;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.BufferedWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import javax.tools.Diagnostic.Kind;

/**
 * A processor that generates a bridge class to accompany annotations indirectly marked with the
 * meta-meta-annotation {@link BridgeMarker}, as well as any annotations nested therein. The
 * generated class has the same name as the source annotation but with a "$Bridge" suffix.
 */
@AutoService(Processor.class)
public class AnnotationBridgeProcessor extends AbstractMetaMetaProcessor {
   private static final String BRIDGE_NAME_SUFFIX = "$Bridge";

   DeclaredType javaLangAnnotation;
   DeclaredType javaLangString;
   DeclaredType javaLangClass;
   DeclaredType javaLangEnum;

   @Override
   public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
   }

   @Override
   protected Class<? extends Annotation> metaMetaAnnotation() {
      return BridgeMarker.class;
   }
   
   @Override
   public void init(ProcessingEnvironment processingEnv) {
      super.init(processingEnv);
      javaLangAnnotation = declaredTypeFor(Annotation.class);
      javaLangString = declaredTypeFor(String.class);
      javaLangClass = declaredTypeFor(Class.class);
      javaLangEnum = declaredTypeFor(Enum.class);
   }
   
   private DeclaredType declaredTypeFor(Class<?> type) {
      return processingEnv.getTypeUtils()
            .getDeclaredType(processingEnv.getElementUtils()
                  .getTypeElement(type.getCanonicalName()));
   }

   @Override
   protected void processAnnotation(TypeElement annotation) {
      try {
         BridgeGenerator generator = new BridgeGenerator(annotation);
         JavaFile javaFile = generator.generate();
         JavaFileObject outputFile = processingEnv.getFiler().createSourceFile(
               generator.packageName + "." + generator.annotationName + BRIDGE_NAME_SUFFIX);
         
         try (Writer writer = new BufferedWriter(outputFile.openWriter())) {
            javaFile.writeTo(writer);
            //writer.write(new Formatter().formatSource(javaFile.toString()));
         }
      } catch (Exception e) {
         processingEnv.getMessager().printMessage(Kind.ERROR, Throwables.getStackTraceAsString(e));
      }
   }

   /**
    * Generates a bridge class for a given annotation.
    */
   private class BridgeGenerator {
      // the annotation, for which a bridge is generated
      private final TypeElement annotation;
      private final String packageName;
      private final String annotationName;
      private final TypeName annotationType;

      // the generated builder class
      private final TypeName bridgeType;
      private TypeSpec.Builder bridge;

      // code blocks which accumulate per-method statements
      private CodeBlock.Builder ctorLocalVariables;
      private CodeBlock.Builder ctorProcessValues;
      private CodeBlock.Builder ctorInitializeFields;
      private CodeBlock.Builder equalsImpl;
      private CodeBlock.Builder hashCodeImpl;

      BridgeGenerator(TypeElement annotation) {
         this.annotation = annotation;
         this.packageName = getPackageName(annotation);
         this.annotationType = TypeName.get(annotation.asType());
         this.bridgeType = bridgeClassName(annotation);

         // Get simple name for the annotation. If it's a nested type, dots become dollars in the
         // generated class names: e.g. Outer.Inner produces Outer$Inner.
         this.annotationName = typeSimpleName(
               processingEnv.getElementUtils().getBinaryName(annotation).toString(), packageName);
      }

      /**
       * Runs the generator and returns the resulting Java file.
       */
      public JavaFile generate() {
         bridge = TypeSpec.classBuilder(annotationName + BRIDGE_NAME_SUFFIX)
               .addModifiers(Modifier.PUBLIC)
               .addAnnotation(generatedAnnotation(annotation))
               .superclass(ParameterizedTypeName.get(ClassName.get(Bridge.class), annotationType))
               .addJavadoc("A bridge for interacting with mirrors of {@link $T} annotations.",
                     annotationType);

         // we accumulate numerous code blocks that have per-method code all in a single sweep
         // over the annotation's methods
         ctorLocalVariables = CodeBlock.builder();
         ctorProcessValues = CodeBlock.builder();
         ctorInitializeFields = CodeBlock.builder();
         equalsImpl = CodeBlock.builder();
         hashCodeImpl = CodeBlock.builder();

         boolean first = true;

         // process each annotation method
         for (Element e : annotation.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && !e.getModifiers().contains(Modifier.STATIC)) {
               ExecutableElement method = MoreElements.asExecutable(e);
               
               if (first) {
                  first = false;
                  equalsImpl.add("return ");
                  hashCodeImpl.add("return ");
               } else {
                  equalsImpl.add("\n    && ");
                  hashCodeImpl.add("\n    + ");
               }
               
               new MethodProcessor(method).process();
            }
         }

         // after processing all methods, we can now generate non-method-specific code
         generateBridgeMethods();

         // BOOM! done
         return JavaFile.builder(packageName, bridge.build()).build();
      }

      private void generateBridgeMethods() {
         // constructor
         bridge.addMethod(MethodSpec.constructorBuilder()
               .addModifiers(Modifier.PUBLIC)
               .addParameter(AnnotationMirror.class, "mirror")
               .addStatement("super(mirror, $T.class)", annotationType)
               .addCode(ctorLocalVariables.build())
               .addStatement("$T<? extends $T, ? extends $T> __values = mirror.getElementValues()",
                     Map.class, ExecutableElement.class, AnnotationValue.class)
               .beginControlFlow("for ($T __e : mirror.getAnnotationType().asElement().getEnclosedElements())",
                     Element.class)
                  .beginControlFlow("if (__e.getKind() != $T.METHOD)", ElementKind.class)
                     .addStatement("continue")
                  .endControlFlow()
                  .addStatement("$T __ex = ($T) __e", ExecutableElement.class, ExecutableElement.class)
                  .beginControlFlow("if (__ex.getModifiers().contains($T.STATIC)"
                        + " || !__ex.getParameters().isEmpty()"
                        + " || __ex.getSimpleName().contentEquals(\"hashCode\")"
                        + " || __ex.getSimpleName().contentEquals(\"toString\")"
                        + " || __ex.getSimpleName().contentEquals(\"annotationType\"))", Modifier.class)
                     .addStatement("continue")
                  .endControlFlow()
                  .addStatement("$T __v = __values.get(__ex)", AnnotationValue.class)
                  .beginControlFlow("if (__v == null)")
                     .addStatement("__v = __ex.getDefaultValue()")
                  .endControlFlow()
                  .beginControlFlow("if (__v == null)")
                     .addStatement("throw new $T(\"Invalid mirror: no value for \" + __ex.getSimpleName())",
                           IllegalStateException.class)
                  .endControlFlow()
                  .beginControlFlow("switch (__ex.getSimpleName().toString())")
                     .addCode(ctorProcessValues.build())
                     .addCode("default:\n")
                     .addStatement("throw new $T(\"Unrecognized method: \" + __ex.getSimpleName())",
                           IllegalStateException.class)
                  .endControlFlow()
               .endControlFlow()
               .addCode(ctorInitializeFields.build())
               .addJavadoc("Creates a new bridge that wraps the given mirror")
               .build());
         
         bridge.addMethod(MethodSpec.methodBuilder("equals")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(boolean.class)
               .addParameter(Object.class, "o")
               .addStatement("if (this == o) return true")
               .addStatement("if (!(o instanceof $T)) return false", bridgeType)
               .addStatement("$T other = ($T) o", bridgeType, bridgeType)
               .addStatement("if (other.annotationType() != $T.class) return false", annotationType)
               .addCode(equalsImpl.add(";\n").build())
               .build());

         bridge.addMethod(MethodSpec.methodBuilder("hashCode")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(int.class)
               .addCode(hashCodeImpl.add(";\n").build())
               .build());
      }

      /**
       * Processes a single method on the annotation. Each method results in fields and methods on
       * the generated builder and implementation class.
       */
      private class MethodProcessor {
         private final ExecutableElement method;
         private final String methodName;
         private final TypeMirror methodTypeMirror;

         // can vary from methodType, e.g. list instead of array, TypeElement instead of Class
         private final TypeName bridgeFieldType;

         // non-null if method type is an array
         private final TypeMirror componentTypeMirror;
         private final TypeName componentType;

         MethodProcessor(ExecutableElement method) {
            this.method = method;

            // We use the annotation method name extensively. Accessor and setter methods in
            // the bridge class, as well as associated fields and local variables, are all
            // named after it.
            this.methodName = method.getSimpleName().toString();
            this.methodTypeMirror = method.getReturnType();

            if (methodTypeMirror.getKind() == TypeKind.ARRAY) {
               this.componentTypeMirror = ((ArrayType) methodTypeMirror).getComponentType();
               this.componentType = getBridgeType(componentTypeMirror);
               this.bridgeFieldType = ParameterizedTypeName.get(ClassName.get(List.class),
                     componentType.box());
            } else {
               this.componentTypeMirror = null;
               this.componentType = null;
               this.bridgeFieldType = getBridgeType(methodTypeMirror);
            }
         }
         
         private TypeName getBridgeType(TypeMirror typeMirror) {
            if (typeMirror.getKind() == TypeKind.DECLARED) {
               TypeElement element = (TypeElement) ((DeclaredType) typeMirror).asElement();
               if (element.getKind() == ElementKind.ANNOTATION_TYPE) {
                  return bridgeClassName(element);
               } else if (element.getQualifiedName()
                     .contentEquals(Class.class.getCanonicalName())) {
                  return ClassName.get(TypeElement.class);
               }
            }
            return TypeName.get(typeMirror);
         }
            
         /** Generates code related to the current annotation method. */
         public void process() {
            // if the method's return type is also an annotation we should produce a bridge for it,
            // too
            TypeElement methodTypeElement = getMethodTypeElement(methodTypeMirror);
            if (methodTypeElement != null
                  && methodTypeElement.getKind() == ElementKind.ANNOTATION_TYPE
                  && !bridgeExists(methodTypeElement)) {
               enqueueAnnotation(methodTypeElement, method);
            }

            generateBridgeCode();
         }

         /** Returns true if the current method's return type is an array. */
         private boolean isArray() {
            return componentTypeMirror != null;
         }

         private void generateBridgeCode() {
            // Field declaration
            bridge.addField(
                  FieldSpec.builder(bridgeFieldType, methodName, Modifier.PRIVATE, Modifier.FINAL)
                  .build());

            // Constructor code sections
            ctorLocalVariables.addStatement("$T __tmp$L = null", bridgeFieldType.box(), methodName);

            ctorProcessValues.add("case \"$L\":\n", methodName);
            addFieldFromAnnotationValue(ctorProcessValues, "__tmp" + methodName, false,
                  methodTypeMirror, "__v");
            ctorProcessValues.addStatement("break");
            
            ctorInitializeFields.beginControlFlow("if (__tmp$L == null)", methodName)
                  .addStatement("throw new $T(\"Invalid mirror: no value for $L\")",
                        IllegalStateException.class, methodName)
                  .endControlFlow();
            if (isArray()) {
               ctorInitializeFields.addStatement("this.$L = $T.unmodifiableList(__tmp$L)",
                     methodName, Collections.class, methodName);
            } else {
               ctorInitializeFields.addStatement("this.$L = __tmp$L", methodName, methodName);
            }

            // Accessor method
            bridge.addMethod(
                  MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(bridgeFieldType)
                        .addStatement("return $L", methodName)
                        .build());

            // equals and hashCode
            addEquals(methodName, methodTypeMirror, equalsImpl);

            hashCodeImpl.add("(127 * $S.hashCode() ^ ", methodName);
            addHashCode(methodName, methodTypeMirror, hashCodeImpl);
            hashCodeImpl.add(")");
         }

         private TypeElement getMethodTypeElement(TypeMirror mirror) {
            if (mirror.getKind() == TypeKind.ARRAY) {
               return getMethodTypeElement(((ArrayType) mirror).getComponentType());
            } else if (mirror.getKind() == TypeKind.DECLARED) {
               return MoreElements.asType(((DeclaredType) mirror).asElement());
            } else {
               return null;
            }
         }

         /**
          * Returns the raw type corresponding to the given mirror. If the given type is not
          * generic, it is returned. Otherwise, all type arguments are stripped.
          */
         private TypeMirror rawComponentType(TypeMirror t) {
            // We don't need to check for TypeKind.ARRAY and recurse because annotation values can
            // only have one-dimensional array types.
            return t.getKind() == TypeKind.DECLARED ? rawComponentType((DeclaredType) t) : t;
         }

         private DeclaredType rawComponentType(DeclaredType t) {
            // recreate the declared type, but without any type args
            TypeMirror owner = t.getEnclosingType();
            TypeMirror rawOwner = rawComponentType(owner);
            List<? extends TypeMirror> args = t.getTypeArguments();
            if (owner == rawOwner && args.isEmpty()) {
               // not a generic type
               return t;
            }

            assert rawOwner.getKind() == TypeKind.DECLARED || rawOwner.getKind() == TypeKind.NONE;

            return rawOwner.getKind() == TypeKind.DECLARED
                  ? processingEnv.getTypeUtils().getDeclaredType((DeclaredType) rawOwner,
                        (TypeElement) t.asElement())
                  : processingEnv.getTypeUtils().getDeclaredType((TypeElement) t.asElement());
         }

         private void addFieldFromAnnotationValue(CodeBlock.Builder block, String dest,
               boolean destIsList, TypeMirror expectedType, String source) {
            if (expectedType.getKind() == TypeKind.ARRAY) {
               if (destIsList) {
                  throw new AssertionError("Nested arrays not allowed in annotation values");
               }
               block.addStatement(dest + " = new $T<>((($T<?>) " + source + ".getValue()).size())",
                     ArrayList.class, List.class);
               block.beginControlFlow("for ($T __o : ($T<?>) " + source + ".getValue())",
                     Object.class, List.class);
               block.addStatement("$T __av = ($T) __o",
                     AnnotationValue.class, AnnotationValue.class);
               addFieldFromAnnotationValue(block, dest, true,
                     ((ArrayType) expectedType).getComponentType(), "__av");
               block.endControlFlow();
               return;
            }
            
            String prefix, suffix;
            if (destIsList) {
               prefix = dest + ".add(";
               suffix = ")";
            } else {
               prefix = dest + " = ";
               suffix = "";
            }
            if (expectedType.getKind().isPrimitive()
                  || processingEnv.getTypeUtils().isSameType(expectedType, javaLangString)) {
               block.addStatement(prefix + "($T) " + source + ".getValue()" + suffix,
                     TypeName.get(expectedType).box());
               return;
            }
            if (processingEnv.getTypeUtils().isSubtype(expectedType, javaLangClass)) {
               block.addStatement(
                     prefix + "($T) (($T) " + source + ".getValue()).asElement()" + suffix,
                     TypeElement.class, DeclaredType.class);
               return;
            }
            if (processingEnv.getTypeUtils().isSubtype(expectedType, javaLangEnum)) {
               block.addStatement(
                     prefix + "$T.valueOf((($T) " + source + ".getValue()).getSimpleName().toString())" + suffix,
                     TypeName.get(expectedType), VariableElement.class);
               return;
            }
            
            if (!processingEnv.getTypeUtils().isSubtype(expectedType, javaLangAnnotation)) {
               throw new AssertionError("Invalid type of annotation value: " + expectedType);
            }
            TypeElement annotationElement =
                  (TypeElement) ((DeclaredType) expectedType).asElement();
            block.addStatement(prefix + "new $T(($T) " + source + ".getValue())" + suffix,
                  bridgeClassName(annotationElement), AnnotationMirror.class);
         }
         
         /**
          * Emits a portion of the {@link #equals} method's {@code return} statement. The portion
          * just compares the given annotation method.
          */
         private void addEquals(String methodName, TypeMirror methodTypeMirror,
               CodeBlock.Builder equalsImpl) {
            switch (methodTypeMirror.getKind()) {
               case DECLARED: case ARRAY:
                  equalsImpl.add("$L.equals(other.$L)", methodName, methodName);
                  break;
               case FLOAT:
                  equalsImpl.add("$T.valueOf($L).equals($T.valueOf(other.$L))", Float.class,
                        methodName, Float.class, methodName);
                  break;
               case DOUBLE:
                  equalsImpl.add("$T.valueOf($L).equals($T.valueOf(other.$L))", Double.class,
                        methodName, Double.class, methodName);
                  break;
               default:
                  equalsImpl.add("$L == other.$L", methodName, methodName);
                  break;
            }
         }

         /**
          * Emits a portion of the {@link #hashCode} method's {@code return} statement. The portion
          * just get the hash code contribution for the given annotation method.
          */
         private void addHashCode(String methodName, TypeMirror methodTypeMirror,
               CodeBlock.Builder hashCodeImpl) {
            if (methodTypeMirror.getKind().isPrimitive()) {
               Class<?> boxedType = boxClass((PrimitiveType) methodTypeMirror);
               hashCodeImpl.add("$T.valueOf($L).hashCode()", boxedType, methodName);
            } else {
               hashCodeImpl.add("$L.hashCode()", methodName);
            }
         }
      }
   }

   /** Returns the class token for the box type that corresponds to the given primitive type. */
   private static Class<?> boxClass(PrimitiveType typeMirror) {
      switch (typeMirror.getKind()) {
         case BOOLEAN:
            return Boolean.class;
         case BYTE:
            return Byte.class;
         case SHORT:
            return Short.class;
         case CHAR:
            return Character.class;
         case INT:
            return Integer.class;
         case LONG:
            return Long.class;
         case FLOAT:
            return Float.class;
         case DOUBLE:
            return Double.class;
         default:
            throw new AssertionError(
                  "Unsupported type kind in annotation! " + typeMirror.getKind().name());
      }
   }

   /**
    * Returns the name of the bridge that would be generated for the given annotation type.
    */
   private ClassName bridgeClassName(TypeElement annotationType) {
      String packageName = getPackageName(annotationType);
      String annotationName = typeSimpleName(
            processingEnv.getElementUtils().getBinaryName(annotationType).toString(), packageName);
      return ClassName.get(packageName, annotationName + BRIDGE_NAME_SUFFIX);
   }

   /**
    * Returns true if a bridge for the given annotation already exists.
    */
   private boolean bridgeExists(TypeElement type) {
      String bridgeName =
            processingEnv.getElementUtils().getBinaryName(type) + BRIDGE_NAME_SUFFIX;
      return processingEnv.getElementUtils().getTypeElement(bridgeName) != null;
   }

   /**
    * Returns the simple name of the class with the given binary name. This effectively just strips
    * off the given package prefix. For nested classes, this will return a name that uses dollar
    * signs ($) to separate nested from enclosing class names.
    */
   private static String typeSimpleName(String typeBinaryName, String packageName) {
      if (!packageName.isEmpty()) {
         assert typeBinaryName.startsWith(packageName + ".");
         typeBinaryName = typeBinaryName.substring(packageName.length() + 1);
      }
      return typeBinaryName;
   }

   /**
    * Returns the name of the package for the given type.
    */
   private static String getPackageName(TypeElement type) {
      return MoreElements.getPackage(type).getQualifiedName().toString();
   }
}
